package fr.vertexvelocity.webpanel.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.*;
import com.velocitypowered.api.proxy.*;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import fr.vertexvelocity.webpanel.config.PanelConfig;
import fr.vertexvelocity.webpanel.db.PanelDatabase;
import fr.vertexvelocity.webpanel.db.PanelDatabase.UserRecord;
import fr.vertexvelocity.webpanel.security.PasswordUtil;
import fr.vertexvelocity.webpanel.security.Totp;
import fr.vertexvelocity.webpanel.service.MaintenanceService;
import fr.vertexvelocity.webpanel.service.MotdService;
import fr.vertexvelocity.webpanel.service.TlsCertificateDetector;
import fr.vertexvelocity.webpanel.service.AcmeCertificateService;
import fr.vertexvelocity.webpanel.commands.CommandAliasService;

import java.io.*;
import java.net.*;
import javax.net.ssl.*;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class WebPanelServer {
    private final ProxyServer proxy; private final Logger logger; private final PanelConfig config; private final PanelDatabase db; private final Instant startedAt; private final Path dataDirectory;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, PendingLogin> pendingLogins = new ConcurrentHashMap<>();
    private final Map<String, ArrayDeque<Long>> loginAttempts = new ConcurrentHashMap<>();
    private final Map<String, FailureState> failedLogins = new ConcurrentHashMap<>();
    private volatile String lastAcmeError = "No ACME error recorded";
    private volatile String lastAcmeCheck = "Not checked yet";
    private volatile String lastCertificateFingerprint = "";
    private final Map<Boolean, HttpServer> webServers = new ConcurrentHashMap<>();
    private final ExecutorService webExecutor = new ThreadPoolExecutor(
            4, 32, 60L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(512),
            r -> { Thread t = new Thread(r, "vertex-web-panel-http"); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.CallerRunsPolicy());
    private AcmeCertificateService acme;
    private final AtomicInteger httpErrors = new AtomicInteger();
    private final MaintenanceService maintenance;
    private final MotdService motd;
    private final CommandAliasService aliases;
    private final Map<String, BackendHealth> backendHealth = new ConcurrentHashMap<>();
    private final Map<String, Long> alertCooldowns = new ConcurrentHashMap<>();
    private final ExecutorService alertExecutor = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "vertex-web-panel-alerts"); t.setDaemon(true); return t; });
    private Path effectiveKeystore;
    private volatile TlsCertificateDetector.Material tlsMaterial;
    private String activeMode = "HTTP";

    public WebPanelServer(ProxyServer proxy, Logger logger, PanelConfig config, PanelDatabase db, Instant startedAt, Path dataDirectory, MaintenanceService maintenance, MotdService motd, CommandAliasService aliases) throws IOException { this.proxy=proxy;this.logger=logger;this.config=config;this.db=db;this.startedAt=startedAt;this.dataDirectory=dataDirectory; this.maintenance = maintenance; this.motd = motd; this.aliases = aliases; }

    public void start() throws Exception {
        if (!config.webEnabled()) { logger.info("Web panel is disabled in config.yml"); return; }
        String requestedMode = config.webMode();
        if (!Set.of("AUTO", "HTTP", "HTTPS", "BOTH").contains(requestedMode)) throw new IllegalStateException("web-panel.mode must be AUTO, HTTP, HTTPS or BOTH");
        TlsCertificateDetector detector = new TlsCertificateDetector(dataDirectory);
        if (config.acmeEnabled()) {
            acme = new AcmeCertificateService(config, logger, dataDirectory);
            acme.validateConfiguration();
            // HTTP-01 validation needs a live HTTP listener before Certbot can request the challenge URL.
            activeMode = "HTTP";
            startWebServer(false);
            int hours = Math.max(6, Math.min(168, config.acmeRenewalCheckHours()));
            proxy.getScheduler().buildTask(getPluginOwner(), this::checkAcmeRenewal)
                    .repeat(hours, TimeUnit.HOURS).schedule();
            logger.info("Automatic ACME enabled for {}. HTTP-01 challenges served from {}; issuance/renewal checked every {} hours.",
                    config.acmeDomain(), acme.challengeRoot(), hours);
            // Do not block Velocity's proxy-initialization thread while Certbot performs network validation.
            Thread initialAcme = new Thread(this::checkAcmeRenewal, "vertex-acme-initial-issuance");
            initialAcme.setDaemon(true);
            initialAcme.start();
        } else {
            if (!requestedMode.equals("HTTP")) tlsMaterial = detector.detect(config.keystore(), config.keystoreType(), config.keystorePassword(), config.certificate(), config.privateKey()).orElse(null);
            if (requestedMode.equals("AUTO")) {
                activeMode = tlsMaterial == null ? "HTTP" : "HTTPS";
                if (tlsMaterial == null) logger.warn("TLS auto-detection found no usable certificate/private-key pair or keystore. Starting HTTP on port {}. Place fullchain.pem + privkey.pem in the plugin tls/ directory to enable HTTPS automatically.", config.httpPort());
                else logger.info("TLS auto-detection found a valid certificate (subject={}, expires={}); selecting HTTPS automatically.", tlsMaterial.subject(), tlsMaterial.expiresAt());
            } else {
                activeMode = requestedMode;
                if ((requestedMode.equals("HTTPS") || requestedMode.equals("BOTH")) && tlsMaterial == null) throw new IllegalStateException("HTTPS was explicitly requested but no valid TLS certificate/key pair or usable keystore was detected. Put fullchain.pem + privkey.pem in the plugin tls/ folder or use AUTO mode.");
            }
            if (activeMode.equals("BOTH") && config.httpPort() == config.httpsPort()) throw new IllegalStateException("HTTP and HTTPS ports must differ in BOTH mode");
            try {
                if (activeMode.equals("HTTP") || activeMode.equals("BOTH")) startWebServer(false);
                if (activeMode.equals("HTTPS") || activeMode.equals("BOTH")) startWebServer(true);
            } catch (Exception e) {
                stopListeners();
                if (requestedMode.equals("AUTO") && tlsMaterial != null) {
                    logger.warn("TLS material was discovered but HTTPS could not start. Falling back to HTTP on port {}. Check certificate/key matching, permissions and certificate chain.", config.httpPort(), e);
                    activeMode = "HTTP";
                    startWebServer(false);
                } else throw e;
            }
        }
        if (webServers.isEmpty()) throw new IllegalStateException("No web listener started.");
        long interval = Math.max(5, config.sampleSeconds());
        proxy.getScheduler().buildTask(getPluginOwner(), () -> {
            db.sampleMetric(proxy.getPlayerCount(), proxy.getAllServers().size(), Math.max(0, Instant.now().getEpochSecond() - startedAt.getEpochSecond()));
            db.prune(config.retentionDays());
            try { db.pruneAlerts(config.alertHistoryLimit()); } catch (SQLException e) { logger.warn("Could not prune alert history", e); }
            probeBackends();
            cleanupSecurityState();
        }).repeat(interval, TimeUnit.SECONDS).schedule();
    }

    private synchronized void checkAcmeRenewal() {
        if (acme == null) return;
        try {
            boolean operationOk = tlsMaterial == null ? acme.obtainInitialCertificate() : acme.renew();
            lastAcmeCheck = Instant.now().toString();
            if (!operationOk) lastAcmeError = "Certbot did not complete successfully; see the private ACME log files."; else lastAcmeError = "";
            if (!operationOk) return;
            TlsCertificateDetector detector = new TlsCertificateDetector(dataDirectory);
            Optional<TlsCertificateDetector.Material> renewed = detector.detectPemPair(acme.certificatePath(), acme.privateKeyPath());
            if (renewed.isEmpty()) {
                logger.error("Certbot completed, but the configured ACME certificate could not be loaded. Keeping the currently running HTTPS listener.");
                return;
            }
            TlsCertificateDetector.Material next = renewed.get();
            boolean changed = tlsMaterial == null || !next.expiresAt().equals(tlsMaterial.expiresAt()) || !certificateFingerprint(next).equals(lastCertificateFingerprint);
            lastCertificateFingerprint = certificateFingerprint(next);
            if (!changed) return;
            TlsCertificateDetector.Material previous = tlsMaterial;
            HttpServer old = webServers.remove(true);
            if (old != null) old.stop(0);
            tlsMaterial = next;
            try {
                startWebServer(true);
                activeMode = webServers.containsKey(false) ? "BOTH" : "HTTPS";
                logger.info("HTTPS listener loaded/reloaded with the ACME certificate (expires {}).", next.expiresAt());
            } catch (Exception reloadFailure) {
                tlsMaterial = previous;
                logger.error("Could not start HTTPS with the renewed certificate; attempting to restore the previous HTTPS listener.", reloadFailure);
                if (previous != null) {
                    try {
                        startWebServer(true);
                        activeMode = webServers.containsKey(false) ? "BOTH" : "HTTPS";
                        logger.warn("Previous HTTPS certificate listener restored (expires {}).", previous.expiresAt());
                    } catch (Exception restoreFailure) {
                        logger.error("Could not restore the previous HTTPS listener. HTTP may remain available if configured.", restoreFailure);
                    }
                }
            }
        } catch (Exception error) {
            lastAcmeCheck = Instant.now().toString(); lastAcmeError = error.getClass().getSimpleName()+": "+String.valueOf(error.getMessage());
            logger.error("Automatic ACME issuance/renewal/reload failed. Detailed error is available in TLS status; inspect private Certbot logs for more information.", error);
        }
    }

    /** Starts the embedded JDK HTTP server, avoiding optional web-framework classpath discovery
     * inside Velocity's plugin classloader. */
    private void startWebServer(boolean https) throws Exception {
        int port = https ? config.httpsPort() : config.httpPort();
        InetAddress bindAddress = InetAddress.getByName(config.bindAddress());
        HttpServer server = null;
        try {
            if (https) {
                if (tlsMaterial == null) throw new IllegalStateException("HTTPS listener requested without detected TLS material");
                SSLContext sslContext = createSslContext(tlsMaterial);
                HttpsServer secureServer = HttpsServer.create(new InetSocketAddress(bindAddress, port), 0);
                server = secureServer;
                secureServer.setHttpsConfigurator(new HttpsConfigurator(sslContext) {
                    @Override public void configure(HttpsParameters parameters) {
                        SSLParameters sslParameters = getSSLContext().getDefaultSSLParameters();
                        sslParameters.setProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
                        parameters.setSSLParameters(sslParameters);
                    }
                });
            } else {
                server = HttpServer.create(new InetSocketAddress(bindAddress, port), 0);
            }
            server.createContext("/", this::handleExchange);
            server.setExecutor(webExecutor);
            server.start();
            HttpServer previous = webServers.put(https, server);
            if (previous != null && previous != server) previous.stop(0);
            logger.info("Vertex Velocity Web Panel {} listener started on {}:{}",
                    https ? "HTTPS" : "HTTP", config.bindAddress(), port);
        } catch (Exception failure) {
            if (server != null) try { server.stop(0); } catch (Exception ignored) { }
            throw failure;
        }
    }

    private SSLContext createSslContext(TlsCertificateDetector.Material material) throws Exception {
        char[] password = randomToken().toCharArray();
        KeyStore keyStore;
        if (material.kind() == TlsCertificateDetector.Material.Kind.KEYSTORE) {
            keyStore = KeyStore.getInstance(material.keyStoreType());
            Path path = material.keyStore();
            char[] configuredPassword = material.keyStorePassword() == null ? new char[0] : material.keyStorePassword().toCharArray();
            try (InputStream input = Files.newInputStream(path)) {
                try { keyStore.load(input, configuredPassword); }
                catch (Exception firstFailure) {
                    try (InputStream retry = Files.newInputStream(path)) { keyStore.load(retry, null); }
                }
            }
            password = configuredPassword;
        } else {
            CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
            Collection<? extends Certificate> parsedCertificates;
            try (InputStream certInput = Files.newInputStream(material.certificate())) {
                parsedCertificates = certificateFactory.generateCertificates(certInput);
            }
            if (parsedCertificates.isEmpty()) throw new IllegalArgumentException("TLS certificate file contains no X.509 certificates");
            Certificate[] chain = parsedCertificates.toArray(Certificate[]::new);
            PrivateKey privateKey = readPkcs8PrivateKey(material.privateKey());
            if (privateKey == null) throw new IllegalArgumentException("TLS private key must be an unencrypted PKCS#8 PEM key");
            keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(null, password);
            keyStore.setKeyEntry("vertex-panel", privateKey, password, chain);
        }
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keyStore, password);
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(keyManagers.getKeyManagers(), null, new java.security.SecureRandom());
        return sslContext;
    }

    private PrivateKey readPkcs8PrivateKey(Path path) throws Exception {
        String pem = Files.readString(path, StandardCharsets.US_ASCII).trim();
        String marker = "-----BEGIN PRIVATE KEY-----";
        String endMarker = "-----END PRIVATE KEY-----";
        if (!pem.contains(marker) || !pem.contains(endMarker)) {
            throw new IllegalArgumentException("Unsupported TLS private key format: expected unencrypted PKCS#8 PEM");
        }
        String encoded = pem.substring(pem.indexOf(marker) + marker.length(), pem.indexOf(endMarker)).replaceAll("\\s", "");
        byte[] keyBytes = Base64.getDecoder().decode(encoded);
        Exception lastFailure = null;
        for (String algorithm : List.of("RSA", "EC", "Ed25519", "DSA")) {
            try { return KeyFactory.getInstance(algorithm).generatePrivate(new PKCS8EncodedKeySpec(keyBytes)); }
            catch (Exception failure) { lastFailure = failure; }
        }
        throw new IllegalArgumentException("Could not parse TLS private key", lastFailure);
    }

    private Object pluginOwner;
    public void setPluginOwner(Object owner) { this.pluginOwner=owner; }
    private Object getPluginOwner() { if(pluginOwner==null) throw new IllegalStateException("Plugin scheduler owner was not provided"); return pluginOwner; }

    public void handleExchange(HttpExchange x) throws IOException {
        handle(x);
    }

    private void handle(HttpExchange x) throws IOException {
        try {
            addSecurityHeaders(x); String path=x.getRequestURI().getPath(); String method=x.getRequestMethod().toUpperCase(Locale.ROOT);
            if (hasSessionCookie(x) && !Set.of("GET", "HEAD", "OPTIONS").contains(method) && !csrfOriginAllowed(x)) { json(x, 403, Map.of("error", "CSRF validation failed: Origin must match this panel host.")); return; }
            if (acme != null && !x.getRequestMethod().equalsIgnoreCase("HEAD")) {
                Optional<byte[]> challenge = acme.challenge(path);
                if (challenge.isPresent()) {
                    byte[] body = challenge.get();
                    x.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                    x.getResponseHeaders().set("Cache-Control", "no-store");
                    if (body.length == 0) { x.sendResponseHeaders(404, -1); return; }
                    x.sendResponseHeaders(200, body.length); try (OutputStream out = x.getResponseBody()) { out.write(body); } return;
                }
            }
            if(path.equals("/")||path.equals("/index.html")){serveResource(x,"/web/index.html","text/html; charset=utf-8");return;}
            if(path.startsWith("/assets/")){
                if(path.contains("..") || !path.matches("/assets/[A-Za-z0-9_./-]{1,180}")){json(x,400,Map.of("error","Invalid asset path"));return;}
                serveResource(x,"/web"+path,"application/octet-stream");return;
            }
            if(path.equals("/api/v1/health")&&method.equals("GET")){json(x,200,Map.of("ok",true,"plugin","Vertex Velocity Web Panel","version","4.3.0","timestamp",Instant.now().getEpochSecond()));return;}
            if(path.equals("/api/v1/setup-status")&&method.equals("GET")){json(x,200,Map.of("needsSetup",db.userCount()==0));return;}
            if(path.equals("/api/v1/setup")&&method.equals("POST")){setup(x);return;}
            if(path.equals("/api/v1/login")&&method.equals("POST")){login(x);return;}
            if(path.equals("/api/v1/login/2fa")&&method.equals("POST")){completeTotpLogin(x);return;}
            if(path.equals("/api/v1/logout")&&method.equals("POST")){logout(x);return;}
            if(path.equals("/api/v1/integrations/keys")&&method.equals("GET")) { Session owner=authenticate(x); if(owner==null){json(x,401,Map.of("error","Authentication required"));return;} requireOwner(x,owner); json(x,200,db.apiKeys()); return; }
            if(path.equals("/api/v1/integrations/keys")&&method.equals("POST")) { Session owner=authenticate(x); if(owner==null){json(x,401,Map.of("error","Authentication required"));return;} requireOwner(x,owner); createApiKey(x,owner); return; }
            if(path.startsWith("/api/v1/integrations/keys/")&&method.equals("DELETE")) { Session owner=authenticate(x); if(owner==null){json(x,401,Map.of("error","Authentication required"));return;} requireOwner(x,owner); revokeApiKey(x,owner,path); return; }
            Session session=authenticate(x);
            if(session==null){json(x,401,Map.of("error","Authentication required"));return;}
            if (session.apiKey && path.startsWith("/api/v1/2fa")) { json(x,403,Map.of("error","Two-factor account settings require a browser session")); return; }
            if (session.apiKey && !Set.of("GET", "HEAD", "OPTIONS").contains(method)) {
                if (!session.apiWrite || !apiKeyWriteAllowed(path, method)) {
                    json(x, 403, Map.of("error", session.apiWrite
                            ? "This API key scope does not allow this write operation. Use an interactive owner/admin session for configuration changes."
                            : "This API key has READ scope only"));
                    return;
                }
            }
            if (!session.apiKey && !Set.of("GET","HEAD","OPTIONS").contains(method) && !Set.of("OWNER","ADMINISTRATOR").contains(session.role) && !(session.role.equals("MODERATOR") && path.startsWith("/api/v1/players/")) && !path.equals("/api/v1/logout")) { json(x,403,Map.of("error","Insufficient role permissions")); return; }
            if(path.equals("/api/v1/status")&&method.equals("GET")){Map<String,Object> state=new LinkedHashMap<>();state.put("ok",true);state.put("stats",stats());state.put("maintenance",maintenance.view());state.put("motd",motd.view());state.put("tls",tlsStatus());state.put("servers",proxy.getAllServers().size());json(x,200,state);return;}
            if(path.equals("/api/v1/tls")&&method.equals("GET")){json(x,200,tlsStatus());return;}
            if(path.equals("/api/v1/2fa")&&method.equals("GET")){json(x,200,Map.of("enabled",db.totpEnabled(session.username)));return;}
            if(path.equals("/api/v1/2fa/setup")&&method.equals("POST")){String secret=Totp.newBase32Secret();db.createPendingTotp(session.username,secret);db.audit(session.username,"TOTP_SETUP_STARTED",session.username,clientIp(x),"Pending TOTP secret created");json(x,200,Map.of("secret",secret,"otpauthUri",Totp.uri(session.username,secret),"message","Add this account to your authenticator app, then confirm with a current six-digit code."));return;}
            if(path.equals("/api/v1/2fa/enable")&&method.equals("POST")){Map<?,?> b=body(x);String secret=db.pendingTotpSecret(session.username);if(secret==null||!Totp.verify(secret,string(b,"code",""))){json(x,400,Map.of("error","Invalid code or no pending setup. Start setup again."));return;}List<String> recoveryCodes=new ArrayList<>();List<String> recoveryHashes=new ArrayList<>();for(int i=0;i<10;i++){String recovery=java.util.UUID.randomUUID().toString().replace("-", "").substring(0,16).toUpperCase(Locale.ROOT);recoveryCodes.add(recovery.substring(0,4)+"-"+recovery.substring(4,8)+"-"+recovery.substring(8,12)+"-"+recovery.substring(12));recoveryHashes.add(PanelDatabase.hashApiKey(recovery));}db.enableTotp(session.username,recoveryHashes);db.audit(session.username,"TOTP_ENABLED",session.username,clientIp(x),"TOTP enabled; 10 one-time recovery codes generated");json(x,200,Map.of("ok",true,"enabled",true,"recoveryCodes",recoveryCodes,"warning","Save these recovery codes now. They are shown only once; each code can be used once."));return;}
            if(path.equals("/api/v1/2fa/disable")&&method.equals("POST")){Map<?,?> b=body(x);Optional<UserRecord> u=db.findUser(session.username);String password=string(b,"password","");String code=string(b,"code","");String secret=db.totpSecret(session.username);if(u.isEmpty()||!PasswordUtil.verify(password,u.get().salt(),u.get().passwordHash())||secret==null||!Totp.verify(secret,code)){json(x,400,Map.of("error","Password or authenticator code is invalid"));return;}db.disableTotp(session.username);db.audit(session.username,"TOTP_DISABLED",session.username,clientIp(x),"TOTP disabled");json(x,200,Map.of("ok",true,"enabled",false));return;}
            if(path.equals("/api/v1/integrations/keys")&&method.equals("GET")){requireOwner(x,session);json(x,200,db.apiKeys());return;}
            if(path.equals("/api/v1/integrations/keys")&&method.equals("POST")){requireOwner(x,session);createApiKey(x,session);return;}
            if(path.startsWith("/api/v1/integrations/keys/")&&method.equals("DELETE")){requireOwner(x,session);revokeApiKey(x,session,path);return;}
            if(path.equals("/api/v1/me")&&method.equals("GET")){json(x,200,Map.of("username",session.username,"role",session.role,"expiresAt",session.expiresAt));return;}
            if(path.equals("/api/v1/sessions/revoke-all")&&method.equals("POST")){requireOwner(x,session);int count=sessions.size();sessions.clear();db.audit(session.username,"ALL_SESSIONS_REVOKED",null,clientIp(x),"Globally revoked "+count+" web sessions");x.getResponseHeaders().add("Set-Cookie",sessionCookie("",0,x));json(x,200,Map.of("ok",true,"revoked",count,"message","All web sessions were revoked. Sign in again."));return;}
            if(path.equals("/api/v1/session/rotate")&&method.equals("POST")){if(session.apiKey){json(x,400,Map.of("error","Session rotation is only available to browser sessions"));return;}String old=cookie(x,"vv_session");if(old!=null)sessions.remove(old);String next=randomToken();long expiry=Instant.now().getEpochSecond()+config.sessionMinutes()*60L;sessions.put(next,new Session(session.username,session.role,expiry,false,false));x.getResponseHeaders().add("Set-Cookie",sessionCookie(next,config.sessionMinutes()*60,x));db.audit(session.username,"SESSION_ROTATED",null,clientIp(x),"Browser session token rotated");json(x,200,Map.of("ok",true,"expiresAt",expiry,"message","Session token rotated."));return;}
            if(path.equals("/api/v1/sessions")&&method.equals("GET")){requireOwner(x,session);List<Map<String,Object>> active=new ArrayList<>();for(Map.Entry<String,Session> entry:sessions.entrySet()){Session value=entry.getValue();Map<String,Object> item=new LinkedHashMap<>();try{item.put("id",PanelDatabase.hashApiKey(entry.getKey()).substring(0,16));}catch(Exception ignored){}item.put("username",value.username);item.put("role",value.role);item.put("expiresAt",value.expiresAt);item.put("current",value.username.equals(session.username)&&value.expiresAt==session.expiresAt);active.add(item);}json(x,200,active);return;}
            if(path.startsWith("/api/v1/sessions/")&&method.equals("DELETE")){requireOwner(x,session);String id=path.substring("/api/v1/sessions/".length());boolean removed=false;for(Map.Entry<String,Session> entry:sessions.entrySet()){try{if(PanelDatabase.hashApiKey(entry.getKey()).startsWith(id)&&id.matches("[a-f0-9]{16}")){if(sessions.remove(entry.getKey(),entry.getValue()))removed=true;}}catch(Exception ignored){}}if(!removed){json(x,404,Map.of("error","Session not found"));return;}db.audit(session.username,"SESSION_REVOKED",id,clientIp(x),"Web session revoked");json(x,200,Map.of("ok",true));return;}
            if(path.equals("/api/v1/stats")&&method.equals("GET")){json(x,200,stats());return;}
            if(path.equals("/api/v1/players")&&method.equals("GET")){json(x,200,players(query(x)));return;}
            if(path.equals("/api/v1/player-intelligence")&&method.equals("GET")){json(x,200,db.playerDirectory(query(x).getOrDefault("search",""),queryInt(x,"limit",100)));return;}
            if(path.startsWith("/api/v1/players/")&&method.equals("GET")){String uuid=path.substring("/api/v1/players/".length());try{UUID.fromString(uuid);}catch(IllegalArgumentException bad){json(x,400,Map.of("error","Invalid UUID"));return;}json(x,200,db.playerProfile(uuid,queryInt(x,"limit",100)));return;}
            if(path.startsWith("/api/v1/players/")&&method.equals("POST")){playerAction(x,session,path);return;}
            if(path.equals("/api/v1/velocity-config/backups")&&method.equals("GET")){json(x,200,velocityConfigBackups());return;}
            if(path.equals("/api/v1/velocity-config/backups/restore")&&method.equals("POST")){requireOwner(x,session);restoreVelocityConfigBackup(x,session);return;}
            if(path.equals("/api/v1/servers/config")&&method.equals("GET")){json(x,200,serverConfigurationView());return;}
            if(path.equals("/api/v1/servers")&&method.equals("POST")){requireOwner(x,session);createBackend(x,session);return;}
            if(path.startsWith("/api/v1/servers/")&&path.endsWith("/config")&&method.equals("GET")){json(x,200,serverConfigurationView());return;}
            if(path.startsWith("/api/v1/servers/")&&method.equals("PUT")){requireOwner(x,session);updateBackend(x,session,URLDecoder.decode(path.substring("/api/v1/servers/".length()),StandardCharsets.UTF_8));return;}
            if(path.startsWith("/api/v1/servers/")&&method.equals("DELETE")){requireOwner(x,session);deleteBackend(x,session,URLDecoder.decode(path.substring("/api/v1/servers/".length()),StandardCharsets.UTF_8));return;}
            if(path.equals("/api/v1/routing")&&method.equals("GET")){requireRole(x,session,"OWNER","ADMINISTRATOR");json(x,200,readRouting());return;}
            if(path.equals("/api/v1/routing")&&method.equals("PUT")){requireOwner(x,session);Map<?,?> b=body(x);Object raw=b.get("fallbackOrder");if(!(raw instanceof List<?> list)||list.isEmpty()){json(x,400,Map.of("error","fallbackOrder must be a non-empty array of backend names"));return;}List<String> order=new ArrayList<>();Set<String> known=new HashSet<>();for(RegisteredServer rs:proxy.getAllServers())known.add(rs.getServerInfo().getName());for(Object item:list){String name=String.valueOf(item);if(!known.contains(name)||order.contains(name)){json(x,400,Map.of("error","Fallback order contains an unknown or duplicate backend: "+name));return;}order.add(name);}writeRouting(order);db.audit(session.username,"ROUTING_UPDATED",order.get(0),clientIp(x),gson.toJson(order));json(x,200,Map.of("ok",true,"defaultServer",order.get(0),"fallbackOrder",order,"message","Saved to velocity.toml. Restart Velocity to apply routing changes."));return;}
            if(path.equals("/api/v1/servers")&&method.equals("GET")){json(x,200,serversView());return;}
            if(path.startsWith("/api/v1/servers/")&&method.equals("GET")){serverDetails(x, URLDecoder.decode(path.substring("/api/v1/servers/".length()), StandardCharsets.UTF_8));return;}
            if(path.equals("/api/v1/commands")&&method.equals("GET")){json(x,200,Map.of("scope","proxy-wide","commands",List.of(Map.of("name","vstats","description","Global proxy statistics","permission","public"),Map.of("name","vservers","description","Backend list and player counts","permission","public"),Map.of("name","vplayers","description","Online player list, optionally filtered by backend","permission","vertexvelocity.command.players"),Map.of("name","vserver","description","Current or target player's backend","permission","vertexvelocity.command.server for target lookup"),Map.of("name","vping","description","Current player's proxy ping","permission","public"),Map.of("name","vhelp","description","Command help","permission","public"))));return;}
            if(path.equals("/api/v1/network")&&method.equals("GET")){json(x,200,networkView());return;}
            if(path.equals("/api/v1/activity")&&method.equals("GET")){json(x,200,db.events(queryInt(x,"limit",250)));return;}
            if(path.equals("/api/v1/analytics")&&method.equals("GET")){Map<String,Object> a=new LinkedHashMap<>();a.put("metrics",db.metrics(queryInt(x,"limit",240)));a.put("events",db.events(queryInt(x,"events",100)));a.put("summary",db.analyticsSummary(queryInt(x,"days",7)));json(x,200,a);return;}
            if(path.equals("/api/v1/monitoring")&&method.equals("GET")){json(x,200,monitoringView());return;}
            if(path.equals("/api/v1/monitoring/config")&&method.equals("GET")){requireRole(x,session,"OWNER","ADMINISTRATOR");json(x,200,Map.of("highLatencyMs",config.highLatencyMs(),"alertCooldownSeconds",config.alertCooldownSeconds(),"alertHistoryLimit",config.alertHistoryLimit()));return;}
            if(path.equals("/api/v1/monitoring/config")&&method.equals("PUT")){requireOwner(x,session);Map<?,?> values=body(x);config.updateEditable(values);db.audit(session.username,"MONITORING_CONFIG_UPDATED",null,clientIp(x),"highLatencyMs="+config.highLatencyMs()+"; alertCooldownSeconds="+config.alertCooldownSeconds());json(x,200,Map.of("ok",true,"highLatencyMs",config.highLatencyMs(),"alertCooldownSeconds",config.alertCooldownSeconds(),"alertHistoryLimit",config.alertHistoryLimit()));return;}
            if(path.equals("/api/v1/monitoring/probe")&&method.equals("POST")){requireWriteAdmin(x,session);probeBackends();db.audit(session.username,"BACKEND_PROBE_REQUESTED",null,clientIp(x),"Manual backend probe");json(x,202,Map.of("ok",true,"message","Backend probes submitted."));return;}
            if(path.equals("/api/v1/alerts")&&method.equals("GET")){requireRole(x,session,"OWNER","ADMINISTRATOR","MODERATOR","VIEWER","API_WRITE");json(x,200,db.alerts(queryInt(x,"limit",100),query(x).getOrDefault("status","ALL")));return;}
            if(path.equals("/api/v1/alerts/ack-all")&&method.equals("POST")){requireWriteAdmin(x,session);int changed=db.acknowledgeAllAlerts(session.username);db.audit(session.username,"ALERTS_ACKNOWLEDGED",null,clientIp(x),"count="+changed);json(x,200,Map.of("ok",true,"acknowledged",changed));return;}
            if(path.matches("/api/v1/alerts/[0-9]+/ack")&&method.equals("POST")){requireWriteAdmin(x,session);String[] parts=path.split("/");long id=Long.parseLong(parts[4]);boolean changed=db.acknowledgeAlert(id,session.username);if(!changed){json(x,404,Map.of("error","Open alert not found"));return;}db.audit(session.username,"ALERT_ACKNOWLEDGED",String.valueOf(id),clientIp(x),"");json(x,200,Map.of("ok",true));return;}
            if(path.equals("/api/v1/diagnostics")&&method.equals("GET")){requireRole(x,session,"OWNER","ADMINISTRATOR");json(x,200,diagnosticsView());return;}
            if(path.equals("/api/v1/diagnostics/export")&&method.equals("GET")){requireRole(x,session,"OWNER","ADMINISTRATOR");jsonDownload(x,"vertex-diagnostics.json",diagnosticsBundle());return;}
            if(path.equals("/api/v1/search")&&method.equals("GET")){json(x,200,globalSearch(query(x).getOrDefault("q","")));return;}
            if(path.equals("/api/v1/command-center")&&method.equals("GET")){requireRole(x,session,"OWNER","ADMINISTRATOR");json(x,200,Map.of("actions",List.of(Map.of("id","probe_backends","label","Probe all backends","risk","LOW"),Map.of("id","reload_aliases","label","Reload command aliases","risk","LOW"),Map.of("id","database_check","label","Check database integrity","risk","LOW")),"note","Only predefined safe operations are available. Arbitrary console commands are intentionally not exposed."));return;}
            if(path.equals("/api/v1/command-center")&&method.equals("POST")){executeSafeCommand(x,session);return;}
            if(path.equals("/api/v1/exports/metrics.csv")&&method.equals("GET")){csv(x,"vertex-metrics.csv",metricsCsv());return;}
            if(path.equals("/api/v1/exports/events.csv")&&method.equals("GET")){csv(x,"vertex-events.csv",eventsCsv());return;}
            if(path.equals("/api/v1/exports/players.csv")&&method.equals("GET")){csv(x,"vertex-players.csv",playersCsv());return;}
            if(path.equals("/api/v1/exports/backends.csv")&&method.equals("GET")){csv(x,"vertex-backends.csv",backendsCsv());return;}
            if(path.equals("/api/v1/exports/audit.csv")&&method.equals("GET")){requireRole(x,session,"OWNER","ADMINISTRATOR");csv(x,"vertex-audit.csv",auditCsv());return;}
            if(path.equals("/api/v1/backups/database")&&method.equals("GET")){requireOwner(x,session);json(x,200,databaseBackups());return;}
            if(path.equals("/api/v1/backups/database")&&method.equals("POST")){requireOwner(x,session);createDatabaseBackup(x,session);return;}
            if(path.startsWith("/api/v1/backups/database/")&&method.equals("GET")){requireOwner(x,session);downloadDatabaseBackup(x,path);return;}
            if(path.equals("/api/v1/openapi.json")&&method.equals("GET")){serveResource(x,"/api/openapi.json","application/json; charset=utf-8");return;}
            if(path.equals("/api/v1/events")&&method.equals("GET")){json(x,200,db.events(queryInt(x,"limit",100)));return;}
            if(path.equals("/api/v1/audit")&&method.equals("GET")){requireRole(x,session,"OWNER","ADMINISTRATOR");json(x,200,db.auditLogs(queryInt(x,"limit",100)));return;}
            if(path.equals("/api/v1/config")&&method.equals("GET")){requireRole(x,session,"OWNER","ADMINISTRATOR");json(x,200,config.publicView());return;}
            if(path.equals("/api/v1/config")&&method.equals("PUT")){requireOwner(x,session);updateConfig(x,session);return;}
            if(path.equals("/api/v1/users")&&method.equals("GET")){requireRole(x,session,"OWNER");json(x,200,db.users());return;}
            if(path.equals("/api/v1/users")&&method.equals("POST")){requireOwner(x,session);createUser(x,session);return;}
            if(path.startsWith("/api/v1/users/")&&method.equals("POST")){requireOwner(x,session);disableUser(x,session,path);return;}
            if(path.equals("/api/v1/motd")&&method.equals("GET")){json(x,200,motd.view());return;}
            if(path.equals("/api/v1/motd/preview")&&method.equals("GET")){Map<String,Object> preview=new LinkedHashMap<>(motd.view());preview.put("maintenanceActive",maintenance.enabled());preview.put("effectiveProfile",maintenance.enabled()?"maintenance":"normal");json(x,200,preview);return;}
            if(path.equals("/api/v1/aliases")&&method.equals("GET")){requireRole(x,session,"OWNER","ADMINISTRATOR");json(x,200,aliases.view());return;}
            if(path.equals("/api/v1/aliases")&&method.equals("PUT")){requireRole(x,session,"OWNER");int count=aliases.update(body(x));db.audit(session.username,"ALIASES_UPDATED",null,clientIp(x),"Registered aliases="+count);json(x,200,Map.of("ok",true,"registered",count,"aliases",aliases.view()));return;}
            if(path.equals("/api/v1/aliases/reload")&&method.equals("POST")){requireWriteAdmin(x,session);int count=aliases.reload();db.audit(session.username,"ALIASES_RELOADED",null,clientIp(x),"Registered aliases="+count);json(x,200,Map.of("ok",true,"registered",count));return;}
            if(path.equals("/api/v1/motd")&&method.equals("PUT")){requireWriteAdmin(x,session);motd.update(body(x));db.audit(session.username,"MOTD_UPDATED",null,clientIp(x),"MOTD configuration changed");json(x,200,Map.of("ok",true,"motd",motd.view()));return;}
            if(path.equals("/api/v1/maintenance")&&method.equals("GET")){json(x,200,maintenance.view());return;}
            if(path.equals("/api/v1/maintenance/status")&&method.equals("GET")){Map<String,Object> status=new LinkedHashMap<>(maintenance.view());status.put("onlinePlayers",proxy.getPlayerCount());status.put("whitelistedOnlinePlayers",proxy.getAllPlayers().stream().filter(p->maintenance.isWhitelisted(p.getUniqueId().toString())).count());status.put("effectiveMotdProfile",maintenance.enabled()?"maintenance":"normal");json(x,200,status);return;}
            if(path.equals("/api/v1/maintenance")&&method.equals("PUT")){requireWriteAdmin(x,session);setMaintenance(x,session);return;}
            if(path.equals("/api/v1/maintenance/whitelist") && method.equals("POST")) {
                requireWriteAdmin(x,session);
                Map<?,?> request = body(x); String uuid = string(request,"uuid","").trim(); String playerName = string(request,"playerName","").trim();
                if (uuid.isBlank() && !playerName.isBlank()) {
                    Optional<Player> online = proxy.getPlayer(playerName);
                    if (online.isPresent()) uuid = online.get().getUniqueId().toString();
                    else { json(x,404,Map.of("error","Player is not online. Provide a UUID for offline players.")); return; }
                }
                try { boolean changed = maintenance.add(uuid); kickPlayersNotAllowedByMaintenance(); db.audit(session.username,"MAINTENANCE_WHITELIST_ADD",uuid,clientIp(x),"changed="+changed); json(x,200,Map.of("ok",true,"changed",changed,"maintenance",maintenance.view())); }
                catch (IllegalArgumentException invalidUuid) { json(x,400,Map.of("error",invalidUuid.getMessage())); }
                return;
            }
            if(path.startsWith("/api/v1/maintenance/whitelist/") && (method.equals("POST") || method.equals("DELETE"))) {
                requireWriteAdmin(x,session);
                String uuid = URLDecoder.decode(path.substring("/api/v1/maintenance/whitelist/".length()), StandardCharsets.UTF_8);
                try {
                    boolean changed = method.equals("POST") ? maintenance.add(uuid) : maintenance.remove(uuid);
                    kickPlayersNotAllowedByMaintenance();
                    db.audit(session.username, method.equals("POST") ? "MAINTENANCE_WHITELIST_ADD" : "MAINTENANCE_WHITELIST_REMOVE", uuid, clientIp(x), "changed="+changed);
                    json(x, 200, Map.of("ok",true,"changed",changed,"maintenance",maintenance.view()));
                } catch (IllegalArgumentException invalidUuid) { json(x,400,Map.of("error",invalidUuid.getMessage())); }
                return;
            }
            json(x,404,Map.of("error","Not found"));
        } catch (ForbiddenException e) { safeJson(x,403,Map.of("error","Insufficient permissions")); }
        catch (IllegalArgumentException e) { safeJson(x,400,Map.of("error",e.getMessage()==null?"Invalid request":e.getMessage())); }
        catch (Exception e) { httpErrors.incrementAndGet(); logger.warn("Web panel request failed: {} {}",x.getRequestMethod(),x.getRequestURI().getPath(),e); safeJson(x,500,Map.of("error","Internal server error")); }
        finally { x.close(); }
    }

    private void setup(HttpExchange x) throws Exception {
        if(db.userCount()>0){json(x,409,Map.of("error","Owner account already exists"));return;}
        Map<?,?> body=body(x);String token=string(body,"setupToken","");String username=string(body,"username","");String password=string(body,"password","");
        if(password.length()<12||password.length()>256){json(x,400,Map.of("error","Password must be 12 to 256 characters"));return;}
        String salt=PasswordUtil.newSalt(),hash=PasswordUtil.hash(password,salt);
        if(!db.setupOwner(token,username,salt,hash)){json(x,400,Map.of("error","Invalid setup token or username"));return;}
        db.audit(username,"OWNER_CREATED",username,clientIp(x),"Initial owner created");json(x,201,Map.of("ok",true,"message","Owner created. Sign in now."));
    }
    private void login(HttpExchange x) throws Exception {
        String ip=clientIp(x); if(!allowAttempt(ip)){json(x,429,Map.of("error","Too many login attempts. Try again later."));return;} if (isProgressivelyBlocked(ip)) { json(x,429,Map.of("error","Login temporarily blocked after repeated failures.","retryAfterSeconds",retryAfter(ip))); return; }
        Map<?,?> body=body(x);String username=string(body,"username","");String password=string(body,"password","");
        Optional<UserRecord> found=db.findUser(username);
        if(found.isEmpty()||found.get().disabled()||!PasswordUtil.verify(password,found.get().salt(),found.get().passwordHash())){recordFailedLogin(ip);db.audit(username,"LOGIN_FAILED",null,ip,"Invalid credentials");json(x,401,Map.of("error","Invalid username or password"));return;}
        failedLogins.remove(ip); UserRecord user=found.get();
        if(db.totpEnabled(user.username())){long now=Instant.now().getEpochSecond();pendingLogins.entrySet().removeIf(e->e.getValue().expiresAt<now);String challenge=randomToken();pendingLogins.put(challenge,new PendingLogin(user.username(),user.role(),now+300,ip));json(x,202,Map.of("requiresTotp",true,"challengeId",challenge,"message","Enter the six-digit code from your authenticator app."));return;}
        createSession(x,user.username(),user.role(),ip);
    }
    private void completeTotpLogin(HttpExchange x) throws Exception {
        String ip=clientIp(x);if(!allowAttempt(ip)){json(x,429,Map.of("error","Too many login attempts. Try again later."));return;} if (isProgressivelyBlocked(ip)) { json(x,429,Map.of("error","Login temporarily blocked after repeated failures.","retryAfterSeconds",retryAfter(ip))); return; }
        Map<?,?> b=body(x);String id=string(b,"challengeId","");String code=string(b,"code","");PendingLogin pending=pendingLogins.get(id);
        if(pending==null||pending.expiresAt<Instant.now().getEpochSecond()||!pending.ip.equals(ip)){pendingLogins.remove(id);json(x,401,Map.of("error","Authentication challenge expired or does not match this client. Sign in again."));return;}
        String secret=db.totpSecret(pending.username);boolean valid=secret!=null&&Totp.verify(secret,code);if(!valid&&code!=null){String normalized=code.replace("-", "").replace(" ", "").toUpperCase(Locale.ROOT);if(normalized.matches("[A-F0-9]{16}")){valid=db.consumeTotpRecoveryCode(pending.username,PanelDatabase.hashApiKey(normalized));if(valid)db.audit(pending.username,"TOTP_RECOVERY_USED",pending.username,ip,"One-time TOTP recovery code consumed");}}if(!valid){recordFailedLogin(ip);db.audit(pending.username,"LOGIN_2FA_FAILED",null,ip,"Invalid TOTP or recovery code");json(x,401,Map.of("error","Invalid authenticator or recovery code"));return;}
        failedLogins.remove(ip);
        pendingLogins.remove(id);createSession(x,pending.username,pending.role,ip);
    }
    private void createSession(HttpExchange x,String username,String role,String ip) throws Exception {
        String token=randomToken();long expiry=Instant.now().getEpochSecond()+config.sessionMinutes()*60L;sessions.put(token,new Session(username,role,expiry,false,false));
        db.audit(username,"LOGIN_SUCCESS",null,ip,"Web panel login");x.getResponseHeaders().add("Set-Cookie",sessionCookie(token,config.sessionMinutes()*60,x));json(x,200,Map.of("ok",true,"username",username,"role",role,"expiresAt",expiry));
    }
    private String sessionCookie(String token,long maxAge,HttpExchange x){boolean secure=(x instanceof HttpsExchange)||config.reverseProxyMode();return "vv_session="+token+"; Path=/; HttpOnly; SameSite=Strict"+(secure?"; Secure":"")+"; Max-Age="+maxAge;}
    private void logout(HttpExchange x) throws IOException {String token=cookie(x,"vv_session");if(token!=null){Session s=sessions.remove(token);if(s!=null)db.audit(s.username,"LOGOUT",null,clientIp(x),"Web panel logout");}x.getResponseHeaders().add("Set-Cookie",sessionCookie("",0,x));json(x,200,Map.of("ok",true));}
    private Session authenticate(HttpExchange x){
        String authorization=x.getRequestHeaders().getFirst("Authorization");
        if(authorization!=null && authorization.startsWith("Bearer ")){
            String key=authorization.substring(7).trim();
            if(key.startsWith("vv_live_") && key.length()>=40) try { String scope=db.authenticateApiKeyScope(PanelDatabase.hashApiKey(key)); if(scope!=null) { boolean write=scope.equals("READ_WRITE"); return new Session("api-key",write?"API_WRITE":"VIEWER",Long.MAX_VALUE,true,write); } } catch(Exception ignored) {}
            return null;
        }
        String token=cookie(x,"vv_session");if(token==null)return null;Session s=sessions.get(token);if(s==null)return null;
        if(s.expiresAt<Instant.now().getEpochSecond()){sessions.remove(token,s);return null;}
        try {
            Optional<UserRecord> current=db.findUser(s.username);
            if(current.isEmpty()||current.get().disabled()){sessions.remove(token,s);return null;}
            if(!current.get().role().equals(s.role)){
                Session refreshed=new Session(s.username,current.get().role(),s.expiresAt,false,false);
                sessions.replace(token,s,refreshed);return sessions.get(token);
            }
        } catch(SQLException failure) { logger.warn("Could not validate the account backing a web session",failure);return null; }
        return s;
    }
    private boolean allowAttempt(String ip){long now=System.currentTimeMillis();ArrayDeque<Long> q=loginAttempts.computeIfAbsent(ip,k->new ArrayDeque<>());synchronized(q){while(!q.isEmpty()&&now-q.peekFirst()>60_000)q.removeFirst();if(q.size()>=Math.max(3,Math.min(60,config.loginAttemptsPerMinute())))return false;q.addLast(now);return true;}}
    private void recordFailedLogin(String ip) { failedLogins.compute(ip,(k,v)-> { long now=System.currentTimeMillis(); if(v==null||now-v.lastFailure>900_000L) v=new FailureState(0,0,now); int n=v.failures+1; long delay=n<5?0:Math.min(900_000L,30_000L << Math.min(4,(n-5)/2)); return new FailureState(n,Math.max(v.blockedUntil,now+delay),now); }); }
    private boolean isProgressivelyBlocked(String ip) { FailureState s=failedLogins.get(ip); if(s==null)return false; if(System.currentTimeMillis()-s.lastFailure>900_000L){failedLogins.remove(ip,s);return false;} return s.blockedUntil>System.currentTimeMillis(); }
    private long retryAfter(String ip) { FailureState s=failedLogins.get(ip);return s==null?0:Math.max(1,(s.blockedUntil-System.currentTimeMillis()+999)/1000); }
    private boolean hasSessionCookie(HttpExchange x) { return cookie(x,"vv_session") != null; }
    private boolean csrfOriginAllowed(HttpExchange x) { String origin=x.getRequestHeaders().getFirst("Origin"), host=x.getRequestHeaders().getFirst("Host"); if(origin==null||host==null)return false; try { URI u=URI.create(origin); return u.getHost()!=null && host.equalsIgnoreCase(u.getRawAuthority()) && ("http".equalsIgnoreCase(u.getScheme())||"https".equalsIgnoreCase(u.getScheme())); } catch(Exception e){return false;} }

    private Path velocityTomlPath() { Path p=dataDirectory.toAbsolutePath();Path plugins=p.getParent();Path root=plugins==null?null:plugins.getParent();return root==null?p.resolve("velocity.toml"):root.resolve("velocity.toml"); }
    private Map<String,Object> readRouting() throws IOException {
        Path file=velocityTomlPath();if(!Files.isRegularFile(file))throw new IOException("velocity.toml was not found at "+file);
        List<String> order = configuredFallbackOrder();
        if(order.isEmpty()&&!proxy.getAllServers().isEmpty())order.add(proxy.getAllServers().iterator().next().getServerInfo().getName());
        Map<String,Object> result=new LinkedHashMap<>();result.put("configPath",file.toString());result.put("fallbackOrder",order);result.put("defaultServer",order.isEmpty()?null:order.get(0));result.put("availableServers",proxy.getAllServers().stream().map(r->r.getServerInfo().getName()).sorted().toList());return result;
    }
    private List<String> configuredFallbackOrder() throws IOException {
        Path file = velocityTomlPath();
        if (!Files.isRegularFile(file)) throw new IOException("velocity.toml was not found at " + file);
        String section = tomlSection(Files.readString(file, StandardCharsets.UTF_8), "servers");
        List<String> order = new ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?m)^\\s*try\\s*=\\s*\\[(.*?)\\]").matcher(section);
        if (matcher.find()) {
            java.util.regex.Matcher names = java.util.regex.Pattern.compile("[\"']([^\"']+)[\"']").matcher(matcher.group(1));
            while (names.find()) order.add(names.group(1));
        }
        return order;
    }

    private void writeRouting(List<String> order) throws IOException {
        Path file = velocityTomlPath();
        if (!Files.isRegularFile(file)) {
            throw new IOException("velocity.toml was not found at " + file + ". No file was modified.");
        }

        String original = Files.readString(file, StandardCharsets.UTF_8);
        java.util.regex.Pattern sectionPattern = java.util.regex.Pattern.compile(
                "(?ms)^(\\[servers\\][ \t]*(?:#[^\\r\\n]*)?\\r?\\n)(.*?)(?=^\\[[^\\r\\n]+\\][ \t]*(?:#[^\\r\\n]*)?$|\\z)");
        java.util.regex.Matcher sectionMatcher = sectionPattern.matcher(original);
        if (!sectionMatcher.find()) {
            throw new IOException("[servers] section was not found in velocity.toml. No file was modified.");
        }

        String body = sectionMatcher.group(2);
        String joined = String.join(", ", order.stream()
                .map(name -> "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .toList());
        String replacement = "try = [" + joined + "]";
        java.util.regex.Pattern tryPattern = java.util.regex.Pattern.compile("(?m)^([ \t]*)try[ \t]*=[^\r\n]*?([ \t]+#.*)?$");
        java.util.regex.Matcher tryMatcher = tryPattern.matcher(body);
        String updatedBody;
        if (tryMatcher.find()) {
            updatedBody = tryMatcher.replaceFirst(java.util.regex.Matcher.quoteReplacement(tryMatcher.group(1) + replacement + (tryMatcher.group(2) == null ? "" : tryMatcher.group(2))));
        } else {
            String newline = body.contains("\r\n") ? "\r\n" : "\n";
            updatedBody = body + (body.isEmpty() || body.endsWith("\n") ? "" : newline) + replacement + newline;
        }
        String newText = original.substring(0, sectionMatcher.start(2)) + updatedBody + original.substring(sectionMatcher.end(2));

        backupVelocityConfig(file);
        writeAtomically(file, newText);
    }
    private Map<String, String> configuredServerAddresses() throws IOException {
        Path file = velocityTomlPath();
        if (!Files.isRegularFile(file)) throw new IOException("velocity.toml was not found at " + file);
        String section = tomlSection(Files.readString(file, StandardCharsets.UTF_8), "servers");
        Map<String, String> result = new LinkedHashMap<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "(?m)^\\s*(?:\"([^\"]+)\"|([A-Za-z0-9_-]+))\\s*=\\s*\"((?:\\\\.|[^\"])*)\"\\s*(?:#.*)?$").matcher(section);
        while (matcher.find()) {
            String name = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            String address = matcher.group(3).replace("\\\"", "\"").replace("\\\\", "\\");
            result.put(name, address);
        }
        return result;
    }

    private Map<String, Object> serverConfigurationView() throws IOException {
        Path file = velocityTomlPath();
        Map<String, String> configured = configuredServerAddresses();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, String> entry : configured.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", entry.getKey());
            row.put("configuredAddress", entry.getValue());
            String host = entry.getValue(); int port = 25565;
            try {
                String[] parsed = splitHostPort(entry.getValue()); host = parsed[0]; port = Integer.parseInt(parsed[1]);
            } catch (Exception ignored) { }
            row.put("host", host); row.put("port", port);
            RegisteredServer live = proxy.getServer(entry.getKey()).orElse(null);
            row.put("registered", live != null);
            row.put("playerCount", live == null ? 0 : live.getPlayersConnected().size());
            BackendHealth health = backendHealth.get(entry.getKey());
            row.put("health", health == null ? (live == null ? "NOT_REGISTERED" : "CHECKING") : health.status);
            rows.add(row);
        }
        rows.sort(Comparator.comparing(row -> String.valueOf(row.get("name")), String.CASE_INSENSITIVE_ORDER));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("configPath", file.toString());
        result.put("backupPath", file.resolveSibling(file.getFileName() + ".vertex-backup").toString());
        result.put("servers", rows);
        result.put("registeredCount", proxy.getAllServers().size());
        result.put("configuredCount", configured.size());
        result.put("fallbackOrder", readRouting().get("fallbackOrder"));
        return result;
    }

    private List<Map<String, Object>> velocityConfigBackups() throws IOException {
        Path history = dataDirectory.resolve("config-backups");
        if (!Files.isDirectory(history)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        try (var stream = Files.list(history)) {
            for (Path file : stream.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().matches("velocity-\\d{8}-\\d{6}-\\d{3}\\.toml"))
                    .sorted(Comparator.comparingLong((Path f) -> f.toFile().lastModified()).reversed()).limit(20).toList()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", file.getFileName().toString());
                row.put("modifiedAt", Files.getLastModifiedTime(file).toMillis() / 1000L);
                row.put("sizeBytes", Files.size(file));
                result.add(row);
            }
        }
        return result;
    }

    private void restoreVelocityConfigBackup(HttpExchange x, Session session) throws Exception {
        Map<?, ?> b = body(x);
        String filename = string(b, "filename", "");
        if (!filename.matches("velocity-\\d{8}-\\d{6}-\\d{3}\\.toml")) {
            json(x, 400, Map.of("error", "Invalid backup filename.")); return;
        }
        Path history = dataDirectory.resolve("config-backups").toAbsolutePath().normalize();
        Path backup = history.resolve(filename).normalize();
        if (!backup.startsWith(history) || !Files.isRegularFile(backup)) {
            json(x, 404, Map.of("error", "Configuration backup not found.")); return;
        }
        String content = Files.readString(backup, StandardCharsets.UTF_8);
        if (!content.contains("[servers]") || tomlSection(content, "servers").isBlank()) {
            json(x, 400, Map.of("error", "Backup does not contain a usable [servers] section.")); return;
        }
        Path current = velocityTomlPath();
        if (!Files.isRegularFile(current)) { json(x, 500, Map.of("error", "Current velocity.toml was not found.")); return; }
        backupVelocityConfig(current);
        writeAtomically(current, content);
        db.audit(session.username, "VELOCITY_CONFIG_RESTORED", filename, clientIp(x), "Restored native Velocity configuration backup; proxy restart required");
        json(x, 200, Map.of("ok", true, "restored", filename, "restartRequired", true,
                "message", "Configuration restored. Restart Velocity to apply the restored configuration to the running proxy."));
    }

    private void createBackend(HttpExchange x, Session session) throws Exception {
        Map<?, ?> b = body(x);
        String name = validServerName(string(b, "name", ""));
        String host = validHost(string(b, "host", ""));
        int port = validPort(b.get("port"));
        Map<String, String> configured = configuredServerAddresses();
        if (configured.containsKey(name) || proxy.getServer(name).isPresent()) {
            json(x, 409, Map.of("error", "A backend with this name already exists.")); return;
        }
        String address = formatHostPort(host, port);
        ServerInfo info = new ServerInfo(name, createAddress(host, port));
        proxy.registerServer(info);
        try { mutateServerToml(null, name, address, false); }
        catch (Exception failure) { try { proxy.unregisterServer(info); } catch (Exception ignored) { } throw failure; }
        db.audit(session.username, "BACKEND_CREATED", name, clientIp(x), address);
        json(x, 201, Map.of("ok", true, "name", name, "address", address,
                "message", "Backend added to velocity.toml and registered in the running proxy."));
    }

    private void updateBackend(HttpExchange x, Session session, String oldName) throws Exception {
        Map<?, ?> b = body(x);
        oldName = validServerName(oldName);
        String newName = validServerName(string(b, "name", oldName));
        String host = validHost(string(b, "host", ""));
        int port = validPort(b.get("port"));
        Map<String, String> configured = configuredServerAddresses();
        String previousAddress = configured.get(oldName);
        if (previousAddress == null) { json(x, 404, Map.of("error", "Backend is not defined in velocity.toml.")); return; }
        if (!newName.equals(oldName) && (configured.containsKey(newName) || proxy.getServer(newName).isPresent())) {
            json(x, 409, Map.of("error", "Another backend already uses that name.")); return;
        }
        RegisteredServer oldRegistered = proxy.getServer(oldName).orElse(null);
        if (oldRegistered != null && !oldRegistered.getPlayersConnected().isEmpty()) {
            json(x, 409, Map.of("error", "This backend has connected players. Transfer them before editing its name or address.")); return;
        }
        String address = formatHostPort(host, port);
        ServerInfo oldInfo = oldRegistered == null ? null : oldRegistered.getServerInfo();
        ServerInfo newInfo = new ServerInfo(newName, createAddress(host, port));
        if (oldInfo != null) proxy.unregisterServer(oldInfo);
        try {
            proxy.registerServer(newInfo);
            mutateServerToml(oldName, newName, address, false);
        } catch (Exception failure) {
            try { proxy.unregisterServer(newInfo); } catch (Exception ignored) { }
            if (oldInfo != null) try { proxy.registerServer(oldInfo); } catch (Exception ignored) { }
            throw failure;
        }
        db.audit(session.username, "BACKEND_UPDATED", newName, clientIp(x), oldName + " -> " + address);
        json(x, 200, Map.of("ok", true, "name", newName, "address", address,
                "message", "Backend configuration saved and live registry updated."));
    }

    private void deleteBackend(HttpExchange x, Session session, String name) throws Exception {
        name = validServerName(name);
        Map<String, String> configured = configuredServerAddresses();
        if (!configured.containsKey(name)) { json(x, 404, Map.of("error", "Backend is not defined in velocity.toml.")); return; }
        RegisteredServer registered = proxy.getServer(name).orElse(null);
        if (registered != null && !registered.getPlayersConnected().isEmpty()) {
            json(x, 409, Map.of("error", "This backend has connected players. Transfer them before deleting it.")); return;
        }
        List<String> fallback = configuredFallbackOrder();
        if (!fallback.isEmpty()) {
            List<String> remainingFallback = new ArrayList<>();
            for (String value : fallback) if (!name.equals(value)) remainingFallback.add(value);
            if (remainingFallback.isEmpty()) { json(x, 409, Map.of("error", "Cannot delete the only fallback server. Configure another fallback first.")); return; }
        } else if (configured.size() <= 1) {
            json(x, 409, Map.of("error", "Cannot delete the last configured backend.")); return;
        }
        if (registered != null) proxy.unregisterServer(registered.getServerInfo());
        try { mutateServerToml(name, null, null, true); }
        catch (Exception failure) { if (registered != null) try { proxy.registerServer(registered.getServerInfo()); } catch (Exception ignored) { } throw failure; }
        db.audit(session.username, "BACKEND_DELETED", name, clientIp(x), configured.get(name));
        json(x, 200, Map.of("ok", true, "name", name, "message", "Backend removed from velocity.toml and the live registry."));
    }

    private String validServerName(String value) {
        String name = value == null ? "" : value.trim();
        if (!name.matches("[A-Za-z0-9_-]{1,32}") || name.equalsIgnoreCase("try"))
            throw new IllegalArgumentException("Backend name must be 1–32 characters using letters, numbers, underscore or hyphen.");
        return name;
    }
    private String validHost(String value) {
        String host = value == null ? "" : value.trim();
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        if (host.isEmpty() || host.length() > 253 || host.chars().anyMatch(Character::isWhitespace)
                || host.contains("/") || host.contains("\\") || host.contains("\"") || host.contains("#"))
            throw new IllegalArgumentException("Enter a valid hostname or IP address. Do not include a port in the host field.");
        if (host.contains(":") && !(host.matches("[0-9a-fA-F:.%]+")))
            throw new IllegalArgumentException("IPv6 hosts may contain only IPv6 characters; enter IPv6 without square brackets.");
        return host;
    }
    private int validPort(Object raw) {
        int port; try { port = Integer.parseInt(String.valueOf(raw)); } catch (Exception e) { throw new IllegalArgumentException("Port must be a number between 1 and 65535."); }
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Port must be between 1 and 65535.");
        return port;
    }
    private InetSocketAddress createAddress(String host, int port) { return InetSocketAddress.createUnresolved(host, port); }
    private String formatHostPort(String host, int port) { return (host.contains(":") ? "[" + host + "]" : host) + ":" + port; }
    private String[] splitHostPort(String value) {
        String host, port;
        if (value.startsWith("[")) { int close = value.indexOf(']'); if (close < 0 || close + 1 >= value.length() || value.charAt(close + 1) != ':') throw new IllegalArgumentException("Invalid IPv6 backend address"); host = value.substring(1, close); port = value.substring(close + 2); }
        else { int colon = value.lastIndexOf(':'); if (colon <= 0) throw new IllegalArgumentException("Backend address must include a port"); host = value.substring(0, colon); port = value.substring(colon + 1); }
        int parsedPort = Integer.parseInt(port); if (parsedPort < 1 || parsedPort > 65535) throw new IllegalArgumentException("Invalid backend port");
        return new String[]{host, String.valueOf(parsedPort)};
    }

    private void mutateServerToml(String oldName, String newName, String address, boolean delete) throws IOException {
        Path file = velocityTomlPath();
        if (!Files.isRegularFile(file)) throw new IOException("velocity.toml was not found at " + file + ". No file was modified.");
        String original = Files.readString(file, StandardCharsets.UTF_8);
        java.util.regex.Pattern sectionPattern = java.util.regex.Pattern.compile(
                "(?ms)^(\\[servers\\][ \\t]*(?:#[^\\r\\n]*)?\\r?\\n)(.*?)(?=^\\[[^\\r\\n]+\\][ \\t]*(?:#[^\\r\\n]*)?$|\\z)");
        java.util.regex.Matcher sectionMatcher = sectionPattern.matcher(original);
        if (!sectionMatcher.find()) throw new IOException("[servers] section was not found in velocity.toml. No file was modified.");
        String body = sectionMatcher.group(2);
        String newline = original.contains("\r\n") ? "\r\n" : "\n";
        if (oldName == null) {
            if (configuredServerAddresses().containsKey(newName)) throw new IOException("A server with this name already exists in velocity.toml.");
            String line = newName + " = \"" + tomlEscape(address) + "\"" + newline;
            body = body + (body.isEmpty() || body.endsWith("\n") ? "" : newline) + line;
        } else {
            java.util.regex.Pattern entryPattern = java.util.regex.Pattern.compile(
                    "(?m)^([ \\t]*)(?:\"" + java.util.regex.Pattern.quote(oldName) + "\"|" + java.util.regex.Pattern.quote(oldName) + ")[ \\t]*=[ \\t]*\"(?:\\\\.|[^\"]*)\"([ \\t]*(?:#.*)?)$");
            java.util.regex.Matcher entryMatcher = entryPattern.matcher(body);
            if (!entryMatcher.find()) throw new IOException("Could not locate the exact server entry for '" + oldName + "'. No file was modified.");
            if (delete) {
                java.util.regex.Pattern linePattern = java.util.regex.Pattern.compile("(?m)^[ \\t]*(?:\"" + java.util.regex.Pattern.quote(oldName) + "\"|" + java.util.regex.Pattern.quote(oldName) + ")[ \\t]*=[^\\r\\n]*(?:\\r?\\n|$)");
                body = linePattern.matcher(body).replaceFirst("");
            } else {
                String replacement = entryMatcher.group(1) + newName + " = \"" + tomlEscape(address) + "\"" + entryMatcher.group(2);
                body = entryMatcher.replaceFirst(java.util.regex.Matcher.quoteReplacement(replacement));
            }
            java.util.regex.Pattern tryPattern = java.util.regex.Pattern.compile("(?m)^([ \\t]*)try[ \\t]*=[ \\t]*\\[(.*?)\\]([ \\t]*(?:#.*)?)$");
            java.util.regex.Matcher tryMatcher = tryPattern.matcher(body);
            if (tryMatcher.find()) {
                List<String> order = new ArrayList<>();
                java.util.regex.Matcher names = java.util.regex.Pattern.compile("[\"']([^\"']+)[\"']").matcher(tryMatcher.group(2));
                while (names.find()) { String value = names.group(1); if (delete) { if (!value.equals(oldName)) order.add(value); } else { order.add(value.equals(oldName) ? newName : value); } }
                if (delete && order.isEmpty()) throw new IOException("Cannot remove the only fallback entry. Add another fallback before deleting this backend.");
                String joined = String.join(", ", order.stream().map(n -> "\"" + tomlEscape(n) + "\"").toList());
                String replacement = tryMatcher.group(1) + "try = [" + joined + "]" + tryMatcher.group(3);
                body = tryMatcher.replaceFirst(java.util.regex.Matcher.quoteReplacement(replacement));
            }
        }
        String updated = original.substring(0, sectionMatcher.start(2)) + body + original.substring(sectionMatcher.end(2));
        backupVelocityConfig(file);
        writeAtomically(file, updated);
    }
    private String tomlEscape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r"); }
    private void backupVelocityConfig(Path file) throws IOException {
        Path backup = file.resolveSibling(file.getFileName() + ".vertex-backup");
        Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
        Path history = dataDirectory.resolve("config-backups"); Files.createDirectories(history);
        String stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(java.time.ZoneOffset.UTC).format(Instant.now());
        Files.copy(file, history.resolve("velocity-" + stamp + ".toml"), StandardCopyOption.COPY_ATTRIBUTES);
        try (var files = Files.list(history)) {
            List<Path> old = files.filter(Files::isRegularFile).sorted(Comparator.comparingLong((Path path) -> path.toFile().lastModified()).reversed()).skip(20).toList();
            for (Path path : old) Files.deleteIfExists(path);
        }
    }
    private void writeAtomically(Path file, String content) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".vertex-tmp");
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            try { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(tmp); }
    }

    private static String tomlSection(String text, String name) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "(?ms)^\\[" + java.util.regex.Pattern.quote(name) + "\\][ \t]*(?:#[^\\r\\n]*)?\\r?\\n?(.*?)(?=^\\[[^\\r\\n]+\\][ \t]*(?:#[^\\r\\n]*)?$|\\z)");
        java.util.regex.Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : "";
    }

    private void cleanupSecurityState() {
        long now=Instant.now().getEpochSecond();
        sessions.entrySet().removeIf(entry -> entry.getValue().expiresAt < now);
        pendingLogins.entrySet().removeIf(entry -> entry.getValue().expiresAt < now);
        long millis=System.currentTimeMillis();
        failedLogins.entrySet().removeIf(entry -> millis-entry.getValue().lastFailure > 900_000L);
        for (Map.Entry<String,ArrayDeque<Long>> entry : loginAttempts.entrySet()) {
            ArrayDeque<Long> attempts=entry.getValue();
            synchronized(attempts){while(!attempts.isEmpty() && millis-attempts.peekFirst()>60_000L)attempts.removeFirst();if(attempts.isEmpty())loginAttempts.remove(entry.getKey(),attempts);}
        }
        alertCooldowns.entrySet().removeIf(entry -> entry.getValue() < now-config.alertCooldownSeconds()*4L);
    }

    private void raiseAlert(String severity,String dedupeKey,String title,String message) {
        long now=Instant.now().getEpochSecond(); long cooldown=Math.max(30,config.alertCooldownSeconds());
        synchronized(alertCooldowns) {
            Long previous=alertCooldowns.get(dedupeKey);
            if(previous!=null && now-previous<cooldown)return;
            alertCooldowns.put(dedupeKey,now);
        }
        try { db.createAlert(severity,dedupeKey,title,message); }
        catch(Exception e){logger.warn("Could not persist monitoring alert {}",dedupeKey,e);}
        sendDiscordAlert(title,message);
    }

    private Map<String,Object> monitoringView() throws Exception {
        List<Map<String,Object>> backends=serversView();
        long online=backends.stream().filter(row->"ONLINE".equals(row.get("health"))).count();
        long offline=backends.stream().filter(row->"OFFLINE".equals(row.get("health"))).count();
        List<Map<String,Object>> currentAlerts=db.alerts(100,"OPEN");
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("checkedAt",Instant.now().getEpochSecond());out.put("backendCount",backends.size());
        out.put("onlineBackends",online);out.put("offlineBackends",offline);out.put("backends",backends);
        out.put("alerts",currentAlerts);out.put("alertCounts",db.alertCounts());out.put("highLatencyMs",config.highLatencyMs());
        out.put("alertCooldownSeconds",config.alertCooldownSeconds());out.put("jvmUsedBytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory());
        out.put("jvmMaxBytes",Runtime.getRuntime().maxMemory());out.put("httpErrors",httpErrors.get());
        return out;
    }

    private Map<String,Object> diagnosticsView() throws Exception {
        Map<String,Object> d=new LinkedHashMap<>();
        d.put("pluginVersion","4.3.0");d.put("javaVersion",System.getProperty("java.version"));
        d.put("javaVendor",System.getProperty("java.vendor"));d.put("os",System.getProperty("os.name")+" "+System.getProperty("os.version"));
        d.put("processors",Runtime.getRuntime().availableProcessors());d.put("jvmUsedBytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory());
        d.put("jvmMaxBytes",Runtime.getRuntime().maxMemory());d.put("uptimeSeconds",Math.max(0,Instant.now().getEpochSecond()-startedAt.getEpochSecond()));
        d.put("databaseHealthy",db.healthCheck());d.put("databaseType","SQLite");d.put("httpListeners",webServers.keySet().stream().map(v->v?"HTTPS":"HTTP").sorted().toList());
        d.put("backendHealth",serversView().stream().map(row->{Map<String,Object> safe=new LinkedHashMap<>();safe.put("name",row.get("name"));safe.put("health",row.get("health"));safe.put("playerCount",row.get("playerCount"));safe.put("latencyMs",row.get("latencyMs"));return safe;}).toList());d.put("requestErrors",httpErrors.get());
        d.put("warning","Diagnostics omit credentials, webhook URLs, private keys, filesystem paths and API secrets.");
        return d;
    }

    private Map<String,Object> diagnosticsBundle() throws Exception {
        Map<String,Object> bundle = new LinkedHashMap<>();
        bundle.put("format", "vertex-diagnostics-v1");
        bundle.put("generatedAt", Instant.now().getEpochSecond());
        bundle.put("diagnostics", diagnosticsView());
        bundle.put("monitoringPolicy", Map.of("highLatencyMs", config.highLatencyMs(),
                "alertCooldownSeconds", config.alertCooldownSeconds(), "alertHistoryLimit", config.alertHistoryLimit()));
        bundle.put("openAlerts", db.alerts(100, "OPEN").stream().map(a -> {
            Map<String,Object> safe = new LinkedHashMap<>();
            safe.put("severity", a.get("severity")); safe.put("source", a.get("source"));
            safe.put("title", a.get("title")); safe.put("status", a.get("status")); safe.put("createdAt", a.get("createdAt"));
            return safe;
        }).toList());
        bundle.put("recentActivityTypes", db.events(100).stream().map(e -> Map.of(
                "type", String.valueOf(e.get("type")), "timestamp", e.get("timestamp") == null ? 0L : e.get("timestamp"))).toList());
        bundle.put("recentAdminActionTypes", db.auditLogs(100).stream().map(a -> Map.of(
                "action", String.valueOf(a.get("action")), "timestamp", a.get("timestamp") == null ? 0L : a.get("timestamp"))).toList());
        bundle.put("note", "Sanitized support bundle. It excludes player names/UUIDs, client IPs, audit targets/details, passwords, API keys, cookies, webhook URLs, TLS private keys, backend addresses and filesystem paths.");
        return bundle;
    }

    private Map<String,Object> globalSearch(String raw) throws Exception {
        String query=raw==null?"":raw.trim();
        if(query.length()<2 || query.length()>64)return Map.of("query",query,"players",List.of(),"servers",List.of(),"events",List.of());
        String normalized=query.toLowerCase(Locale.ROOT);
        List<Map<String,Object>> live=new ArrayList<>();
        for(Player player:proxy.getAllPlayers()){
            String backend=player.getCurrentServer().map(v->v.getServerInfo().getName()).orElse("Connecting");
            if(player.getUsername().toLowerCase(Locale.ROOT).contains(normalized)||player.getUniqueId().toString().toLowerCase(Locale.ROOT).contains(normalized)){
                Map<String,Object> row=new LinkedHashMap<>();row.put("username",player.getUsername());row.put("uuid",player.getUniqueId().toString());row.put("server",backend);row.put("ping",player.getPing());live.add(row);
            }
        }
        List<Map<String,Object>> matchedServers=serversView().stream().filter(v->String.valueOf(v.get("name")).toLowerCase(Locale.ROOT).contains(normalized)||String.valueOf(v.get("address")).toLowerCase(Locale.ROOT).contains(normalized)).limit(8).toList();
        List<Map<String,Object>> knownPlayers=db.playerDirectory(query,10);
        List<Map<String,Object>> matchedEvents=db.events(300).stream().filter(v->String.valueOf(v.get("username")).toLowerCase(Locale.ROOT).contains(normalized)||String.valueOf(v.get("uuid")).toLowerCase(Locale.ROOT).contains(normalized)||String.valueOf(v.get("server")).toLowerCase(Locale.ROOT).contains(normalized)).limit(10).toList();
        return Map.of("query",query,"players",live,"knownPlayers",knownPlayers,"servers",matchedServers,"events",matchedEvents);
    }

    private void executeSafeCommand(HttpExchange x,Session session) throws Exception {
        requireWriteAdmin(x,session);Map<?,?> request=body(x);String action=string(request,"action","").trim();
        Map<String,Object> result=new LinkedHashMap<>();result.put("ok",true);result.put("action",action);
        switch(action){
            case "probe_backends" -> {probeBackends();result.put("message","Backend probes submitted.");}
            case "reload_aliases" -> {int count=aliases.reload();result.put("registeredAliases",count);result.put("message","Command aliases reloaded.");}
            case "database_check" -> {boolean healthy=db.healthCheck();result.put("healthy",healthy);result.put("message",healthy?"SQLite integrity check passed.":"SQLite integrity check failed.");if(!healthy)result.put("ok",false);}
            default -> {json(x,400,Map.of("error","Unsupported action. Only probe_backends, reload_aliases and database_check are allowed."));return;}
        }
        db.audit(session.username,"COMMAND_CENTER_"+action.toUpperCase(Locale.ROOT),null,clientIp(x),String.valueOf(result.get("message")));
        json(x,200,result);
    }

    private String csvCell(Object raw) {
        String value=raw==null?"":String.valueOf(raw);
        int first=0;while(first<value.length()&&Character.isWhitespace(value.charAt(first)))first++;
        if(first<value.length() && "=+-@".indexOf(value.charAt(first))>=0)value="'"+value;
        return "\""+value.replace("\"","\"\"")+"\"";
    }
    private String csv(List<String> header,List<? extends List<?>> rows) {
        StringBuilder out=new StringBuilder("\uFEFF");
        out.append(header.stream().map(this::csvCell).collect(java.util.stream.Collectors.joining(","))).append("\r\n");
        for(List<?> row:rows)out.append(row.stream().map(this::csvCell).collect(java.util.stream.Collectors.joining(","))).append("\r\n");
        return out.toString();
    }
    private void jsonDownload(HttpExchange x, String filename, Object data) throws IOException {
        byte[] bytes = gson.toJson(data).getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        x.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        x.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = x.getResponseBody()) { out.write(bytes); }
    }

    private void csv(HttpExchange x,String filename,String content) throws IOException {
        byte[] data=content.getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type","text/csv; charset=utf-8");
        x.getResponseHeaders().set("Content-Disposition","attachment; filename=\""+filename+"\"");x.getResponseHeaders().set("Cache-Control","no-store");
        x.sendResponseHeaders(200,data.length);try(OutputStream out=x.getResponseBody()){out.write(data);}
    }
    private String metricsCsv() throws Exception {
        List<List<?>> rows=new ArrayList<>();for(Map<String,Object> m:db.metrics(querySafeExportLimit(5000)))rows.add(List.of(m.get("timestamp"),m.get("players"),m.get("backends"),m.get("uptimeSeconds")));
        return csv(List.of("timestamp","online_players","backend_count","uptime_seconds"),rows);
    }
    private String eventsCsv() throws Exception {
        List<List<?>> rows=new ArrayList<>();for(Map<String,Object> e:db.events(querySafeExportLimit(5000)))rows.add(Arrays.asList(e.get("timestamp"),e.get("type"),e.get("username"),e.get("uuid"),e.get("server"),e.get("details")));
        return csv(List.of("timestamp","type","username","uuid","server","details"),rows);
    }
    private String playersCsv() throws Exception {
        List<List<?>> rows=new ArrayList<>();for(Map<String,Object> p:db.playerDirectoryExport(5000))rows.add(Arrays.asList(p.get("username"),p.get("uuid"),p.get("loginCount"),p.get("lastSeen"),p.get("firstSeen")));
        return csv(List.of("username","uuid","login_count","last_seen","first_seen"),rows);
    }
    private String backendsCsv() throws Exception {
        List<List<?>> rows=new ArrayList<>();for(Map<String,Object> b:db.backendMetrics(5000))rows.add(Arrays.asList(b.get("server"),b.get("status"),b.get("latencyMs"),b.get("timestamp")));
        return csv(List.of("server","status","latency_ms","timestamp"),rows);
    }
    private String auditCsv() throws Exception {
        List<List<?>> rows=new ArrayList<>();for(Map<String,Object> e:db.auditLogs(5000))rows.add(Arrays.asList(e.get("timestamp"),e.get("actor"),e.get("action"),e.get("target"),e.get("ip"),e.get("details")));
        return csv(List.of("timestamp","actor","action","target","ip","details"),rows);
    }
    private int querySafeExportLimit(int fallback){return fallback;}

    private List<Map<String,Object>> databaseBackups() throws IOException {
        Path directory=dataDirectory.resolve("database-backups");if(!Files.isDirectory(directory))return List.of();
        try(var files=Files.list(directory)){
            return files.filter(Files::isRegularFile).filter(f->f.getFileName().toString().matches("vertex-panel-\\d{8}-\\d{6}-\\d{3}\\.sqlite"))
                    .sorted(Comparator.comparingLong((Path f)->f.toFile().lastModified()).reversed()).limit(30)
                    .map(f->{Map<String,Object> row=new LinkedHashMap<>();try{row.put("name",f.getFileName().toString());row.put("sizeBytes",Files.size(f));row.put("createdAt",f.toFile().lastModified()/1000);}catch(IOException ignored){}return row;}).toList();
        }
    }
    private void createDatabaseBackup(HttpExchange x,Session session) throws Exception {
        String stamp=java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(java.time.ZoneOffset.UTC).format(Instant.now());
        Path target=dataDirectory.resolve("database-backups").resolve("vertex-panel-"+stamp+".sqlite");
        Path backupDirectory=target.getParent();Files.createDirectories(backupDirectory);
        try { Files.setPosixFilePermissions(backupDirectory,Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE)); } catch(IOException | UnsupportedOperationException permissionFailure){logger.warn("Could not restrict database backup directory permissions; verify the host ACLs",permissionFailure);}
        Path saved=db.backupTo(target);
        try { Files.setPosixFilePermissions(saved,Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,java.nio.file.attribute.PosixFilePermission.OWNER_WRITE)); } catch(IOException | UnsupportedOperationException permissionFailure){logger.warn("Could not restrict database backup file permissions; verify the host ACLs",permissionFailure);}
        pruneDatabaseBackups();db.audit(session.username,"DATABASE_BACKUP_CREATED",saved.getFileName().toString(),clientIp(x),"sizeBytes="+Files.size(saved));
        json(x,201,Map.of("ok",true,"name",saved.getFileName().toString(),"sizeBytes",Files.size(saved),"message","Consistent SQLite snapshot created. Download it and store it outside the proxy host."));
    }
    private void pruneDatabaseBackups() throws IOException {
        Path directory=dataDirectory.resolve("database-backups");if(!Files.isDirectory(directory))return;
        try(var files=Files.list(directory)){
            List<Path> backups=files.filter(Files::isRegularFile).filter(f->f.getFileName().toString().matches("vertex-panel-\\d{8}-\\d{6}-\\d{3}\\.sqlite"))
                    .sorted(Comparator.comparingLong((Path f)->f.toFile().lastModified()).reversed()).toList();
            for(Path obsolete:backups.stream().skip(30).toList())Files.deleteIfExists(obsolete);
        }
    }

    private void downloadDatabaseBackup(HttpExchange x,String path) throws IOException {
        String name=path.substring("/api/v1/backups/database/".length());
        if(!name.matches("vertex-panel-\\d{8}-\\d{6}-\\d{3}\\.sqlite")){json(x,400,Map.of("error","Invalid backup filename"));return;}
        Path directory=dataDirectory.resolve("database-backups").toAbsolutePath().normalize();Path file=directory.resolve(name).normalize();
        if(!file.getParent().equals(directory)||!Files.isRegularFile(file)){json(x,404,Map.of("error","Backup not found"));return;}
        long size=Files.size(file);if(size>512L*1024*1024){json(x,413,Map.of("error","Backup is too large for a direct download"));return;}
        x.getResponseHeaders().set("Content-Type","application/vnd.sqlite3");x.getResponseHeaders().set("Content-Disposition","attachment; filename=\""+name+"\"");
        x.sendResponseHeaders(200,size);try(InputStream in=Files.newInputStream(file);OutputStream out=x.getResponseBody()){in.transferTo(out);}
    }

    private Map<String,Object> stats() throws Exception {
        Map<String,Object> m=new LinkedHashMap<>();m.put("onlinePlayers",proxy.getPlayerCount());m.put("backendCount",proxy.getAllServers().size());m.put("uptimeSeconds",Math.max(0,Instant.now().getEpochSecond()-startedAt.getEpochSecond()));m.put("startedAt",startedAt.getEpochSecond());m.put("jvmUsedBytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory());m.put("jvmTotalBytes",Runtime.getRuntime().totalMemory());m.put("jvmMaxBytes",Runtime.getRuntime().maxMemory());m.put("processors",Runtime.getRuntime().availableProcessors());m.put("httpErrors",httpErrors.get());m.put("maintenance",maintenance.enabled());m.put("latestEvents",db.events(8));return m;
    }
    private List<Map<String,Object>> players(Map<String,String> filters){List<Map<String,Object>> out=new ArrayList<>();String serverFilter=filters.getOrDefault("server","");String search=filters.getOrDefault("search","").toLowerCase(Locale.ROOT);int limit=Math.max(1,Math.min(500,queryIntFromMap(filters,"limit",100)));for(Player p:proxy.getAllPlayers()){String backend=p.getCurrentServer().map(s->s.getServerInfo().getName()).orElse("Connecting");if(!serverFilter.isBlank()&&!backend.equalsIgnoreCase(serverFilter))continue;if(!search.isBlank()&&!p.getUsername().toLowerCase(Locale.ROOT).contains(search))continue;Map<String,Object> m=new LinkedHashMap<>();m.put("username",p.getUsername());m.put("uuid",p.getUniqueId().toString());m.put("ping",p.getPing());m.put("server",backend);out.add(m);}out.sort(Comparator.comparing(m->String.valueOf(m.get("username")),String.CASE_INSENSITIVE_ORDER));return out.stream().limit(limit).toList();}
    private int queryIntFromMap(Map<String,String> values,String key,int fallback){try{return Integer.parseInt(values.getOrDefault(key,String.valueOf(fallback)));}catch(NumberFormatException e){return fallback;}}
    private void serverDetails(HttpExchange x,String name)throws Exception{RegisteredServer server=proxy.getServer(name).orElse(null);if(server==null){json(x,404,Map.of("error","Unknown backend","server",name));return;}Map<String,Object> result=new LinkedHashMap<>();result.put("name",server.getServerInfo().getName());result.put("address",server.getServerInfo().getAddress().getHostString());result.put("port",server.getServerInfo().getAddress().getPort());List<Map<String,Object>> connected=new ArrayList<>();for(Player p:server.getPlayersConnected()){Map<String,Object> player=new LinkedHashMap<>();player.put("username",p.getUsername());player.put("uuid",p.getUniqueId().toString());player.put("ping",p.getPing());connected.add(player);}result.put("playerCount",connected.size());result.put("players",connected);BackendHealth health=backendHealth.get(server.getServerInfo().getName());result.put("health",health==null?"CHECKING":health.status);if(health!=null){result.put("latencyMs",health.latencyMs);result.put("lastCheck",health.checkedAt);}json(x,200,result);}
    private List<Map<String,Object>> serversView(){List<Map<String,Object>> out=new ArrayList<>();for(RegisteredServer s:proxy.getAllServers()){Map<String,Object> m=new LinkedHashMap<>();m.put("name",s.getServerInfo().getName());m.put("address",s.getServerInfo().getAddress().getHostString());m.put("port",s.getServerInfo().getAddress().getPort());m.put("connectedPlayers",s.getPlayersConnected().stream().map(Player::getUsername).toList());m.put("playerCount",s.getPlayersConnected().size());BackendHealth health=backendHealth.get(s.getServerInfo().getName());m.put("health",health==null?"CHECKING":health.status);m.put("latencyMs",health==null?null:health.latencyMs);m.put("lastCheck",health==null?null:health.checkedAt);out.add(m);}out.sort(Comparator.comparing(m->String.valueOf(m.get("name"))));return out;}
    private void playerAction(HttpExchange x,Session session,String path) throws Exception {
        if(session.apiKey){json(x,403,Map.of("error","Player moderation requires an interactive panel account, not an API key."));return;}
        String rest=path.substring("/api/v1/players/".length());String[] parts=rest.split("/");if(parts.length!=2){json(x,404,Map.of("error","Unknown player action"));return;}UUID uuid;try{uuid=UUID.fromString(parts[0]);}catch(IllegalArgumentException e){json(x,400,Map.of("error","Invalid UUID"));return;}Player player=proxy.getPlayer(uuid).orElse(null);if(player==null){json(x,404,Map.of("error","Player is not connected"));return;}
        Map<?,?> body=body(x);if(parts[1].equals("disconnect")){requireRole(x,session,"OWNER","ADMINISTRATOR","MODERATOR");String reason=string(body,"reason","Disconnected by a network administrator");if(reason.length()>200)reason=reason.substring(0,200);player.disconnect(Component.text(reason));db.recordPlayerEvent("ADMIN_DISCONNECT",player.getUniqueId().toString(),player.getUsername(),player.getCurrentServer().map(s->s.getServerInfo().getName()).orElse(null),"Disconnected by "+session.username+": "+reason,Instant.now());db.audit(session.username,"PLAYER_DISCONNECT",player.getUsername(),clientIp(x),reason);json(x,200,Map.of("ok",true));return;}
        if(parts[1].equals("transfer")){requireRole(x,session,"OWNER","ADMINISTRATOR","MODERATOR");String target=string(body,"server","");RegisteredServer destination=proxy.getServer(target).orElse(null);if(destination==null){json(x,400,Map.of("error","Unknown destination server"));return;}String from=player.getCurrentServer().map(s->s.getServerInfo().getName()).orElse("unknown");player.createConnectionRequest(destination).connect().whenComplete((result,error)->{if(error!=null){logger.warn("Transfer failed for {}",player.getUsername(),error);db.recordPlayerEvent("TRANSFER_ERROR",player.getUniqueId().toString(),player.getUsername(),target,error.getClass().getSimpleName(),Instant.now());}});db.recordPlayerEvent("TRANSFER_REQUEST",player.getUniqueId().toString(),player.getUsername(),target,from+" -> "+target+" (requested by "+session.username+")",Instant.now());db.audit(session.username,"PLAYER_TRANSFER",player.getUsername(),clientIp(x),from+" -> "+target);json(x,202,Map.of("ok",true,"message","Transfer request submitted","server",target));return;}
        json(x,404,Map.of("error","Unknown player action"));
    }
    private Map<String,Object> networkView() throws Exception {
        List<Map<String,Object>> nodes=serversView();
        List<Map<String,Object>> recent=db.events(250);
        Map<String,Integer> transfers=new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for(Map<String,Object> e:recent){
            if(!"SERVER_SWITCH".equals(String.valueOf(e.get("type")))) continue;
            String details=String.valueOf(e.getOrDefault("details",""));
            String target=String.valueOf(e.getOrDefault("server",""));
            String key=details.contains(" -> ")?details:target;
            if(!key.isBlank()) transfers.merge(key,1,Integer::sum);
        }
        List<Map<String,Object>> links=new ArrayList<>();
        for(Map.Entry<String,Integer> e:transfers.entrySet()) { Map<String,Object> link=new LinkedHashMap<>();link.put("label",e.getKey());link.put("recentEvents",e.getValue());links.add(link); }
        Map<String,Object> out=new LinkedHashMap<>();out.put("proxy",Map.of("name","Velocity Proxy","players",proxy.getPlayerCount(),"uptimeSeconds",Math.max(0,Instant.now().getEpochSecond()-startedAt.getEpochSecond())));out.put("nodes",nodes);out.put("recentTransfers",links);out.put("updatedAt",Instant.now().getEpochSecond());return out;
    }

    private void probeBackends() {
        for (RegisteredServer backend : proxy.getAllServers()) {
            String name=backend.getServerInfo().getName(); long start=System.nanoTime();
            try {
                backend.ping().orTimeout(Math.max(500,config.backendTimeoutMs()),TimeUnit.MILLISECONDS).whenComplete((response,error)->{
                    long latency=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
                    String status=error==null?"ONLINE":"OFFLINE"; db.sampleBackendMetric(name,status,error==null?latency:-1,Instant.now().getEpochSecond());
                    recordHealthTransition(name, status, latency);
                });
            } catch (Throwable error) { db.sampleBackendMetric(name,"OFFLINE",-1,Instant.now().getEpochSecond()); recordHealthTransition(name,"OFFLINE",-1); }
        }
    }
    private void recordHealthTransition(String name,String status,long latency) {
        long now=Instant.now().getEpochSecond(); BackendHealth previous=backendHealth.put(name,new BackendHealth(status,latency,now));
        if(previous==null || !previous.status.equals(status)) {
            String details=(previous==null?"Initial connectivity state: "+status:"Connectivity changed from "+previous.status+" to "+status)+(latency>=0?"; latency="+latency+"ms":"");
            db.recordPlayerEvent("BACKEND_"+status,null,null,name,details,Instant.ofEpochSecond(now));
            logger.info("Backend {} connectivity: {}{}",name,status,latency>=0?" ("+latency+" ms)":"");
            if ("OFFLINE".equals(status)) raiseAlert("CRITICAL","backend:"+name+":offline","Backend offline: "+name,details);
            else if (previous != null && "OFFLINE".equals(previous.status)) raiseAlert("INFO","backend:"+name+":restored","Backend restored: "+name,details);
        }
        int threshold=config.highLatencyMs();
        if("ONLINE".equals(status) && latency>=threshold && (previous==null || previous.latencyMs<threshold)) {
            raiseAlert("WARNING","backend:"+name+":latency","High backend latency: "+name,"Measured ping is "+latency+" ms (threshold: "+threshold+" ms).");
        }
    }

    public void notifyProxyStarted() { sendDiscordAlert("Velocity proxy started", "Vertex Velocity Web Panel is online. Backend monitoring is active."); }
    private void sendDiscordAlert(String title,String description) {
        if(!config.discordEnabled() || config.discordWebhook().isBlank()) return;
        String url=config.discordWebhook().trim(); if(!url.startsWith("https://discord.com/api/webhooks/") && !url.startsWith("https://discordapp.com/api/webhooks/")) { logger.warn("Discord webhook ignored: only official HTTPS Discord webhook URLs are accepted"); return; }
        try { alertExecutor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                Map<String,Object> embed=new LinkedHashMap<>();
                embed.put("title",title.length()>256?title.substring(0,256):title);
                embed.put("description",description.length()>1500?description.substring(0,1500):description);
                embed.put("color",0x2474E8);embed.put("timestamp",Instant.now().toString());
                String payload=gson.toJson(Map.of("username","Vertex Velocity","embeds",List.of(embed)));
                connection=(HttpURLConnection)URI.create(url).toURL().openConnection();
                connection.setInstanceFollowRedirects(false); connection.setRequestMethod("POST");
                connection.setConnectTimeout(3000);connection.setReadTimeout(3000);connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type","application/json; charset=utf-8");
                byte[] bytes=payload.getBytes(StandardCharsets.UTF_8);connection.setFixedLengthStreamingMode(bytes.length);
                try(OutputStream out=connection.getOutputStream()){out.write(bytes);}
                int code=connection.getResponseCode();if(code<200||code>=300)logger.warn("Discord webhook returned HTTP {}",code);
            } catch(Exception e) { logger.warn("Could not deliver Discord alert: {}",e.getMessage()); }
            finally { if(connection!=null) connection.disconnect(); }
        }); } catch (RejectedExecutionException stopped) { logger.debug("Skipping Discord delivery because the panel is stopping."); }
    }

    private void updateConfig(HttpExchange x,Session session) throws Exception {
        Map<?,?> body=body(x);
        config.updateEditable(body);
        Map<String,Object> saved=new LinkedHashMap<>(); saved.put("mode",config.webMode()); saved.put("bindAddress",config.bindAddress()); saved.put("httpPort",config.httpPort()); saved.put("httpsPort",config.httpsPort()); saved.put("httpsEnabled",config.httpsEnabled()); saved.put("sampleSeconds",config.sampleSeconds()); saved.put("retentionDays",config.retentionDays());
        db.audit(session.username,"CONFIG_UPDATE",null,clientIp(x),gson.toJson(saved));
        json(x,202,Map.of("ok",true,"message","Settings saved to config.yml. Listener, TLS, and bind changes require a proxy restart; data collection settings also take effect after restart.","restartRequired",true,"accepted",saved));
    }
    private void createApiKey(HttpExchange x,Session session) throws Exception {
        Map<?,?> b=body(x);String name=string(b,"name","").trim();String scope=string(b,"scope","READ").trim().toUpperCase(Locale.ROOT);
        if(name.length()<3||name.length()>48){json(x,400,Map.of("error","Key name must be 3 to 48 characters"));return;}
        if(!Set.of("READ","READ_WRITE").contains(scope)){json(x,400,Map.of("error","scope must be READ or READ_WRITE"));return;}
        String raw="vv_live_"+randomToken();String hash=PanelDatabase.hashApiKey(raw);
        if(!db.createApiKey(name,hash,session.username,scope)){json(x,400,Map.of("error","Invalid name or duplicate key"));return;}
        db.audit(session.username,"API_KEY_CREATED",name,clientIp(x),"Scope="+scope);json(x,201,Map.of("ok",true,"name",name,"scope",scope,"apiKey",raw,"warning","Copy this key now. It cannot be displayed again."));
    }
    private void revokeApiKey(HttpExchange x,Session session,String path) throws Exception {
        String raw=path.substring("/api/v1/integrations/keys/".length());long id;try{id=Long.parseLong(raw);}catch(NumberFormatException e){json(x,400,Map.of("error","Invalid key id"));return;}
        if(!db.revokeApiKey(id)){json(x,404,Map.of("error","Key not found or already revoked"));return;}db.audit(session.username,"API_KEY_REVOKED",raw,clientIp(x),"API key revoked");json(x,200,Map.of("ok",true));
    }
    private void createUser(HttpExchange x,Session session) throws Exception {Map<?,?> b=body(x);String username=string(b,"username","");String password=string(b,"password","");String role=string(b,"role","VIEWER").toUpperCase(Locale.ROOT);if(password.length()<12||password.length()>256){json(x,400,Map.of("error","Password must be 12 to 256 characters"));return;}String salt=PasswordUtil.newSalt();boolean ok=db.createUser(username,salt,PasswordUtil.hash(password,salt),role);if(!ok){json(x,400,Map.of("error","Invalid role, username or duplicate username"));return;}db.audit(session.username,"USER_CREATED",username,clientIp(x),role);json(x,201,Map.of("ok",true));}
    private void disableUser(HttpExchange x,Session session,String path) throws Exception {String username=URLDecoder.decode(path.substring("/api/v1/users/".length()),StandardCharsets.UTF_8);Map<?,?> b=body(x);boolean disabled=Boolean.parseBoolean(String.valueOf(b.containsKey("disabled")?b.get("disabled"):Boolean.TRUE));boolean ok=db.disableUser(username,disabled);if(disabled)sessions.entrySet().removeIf(e->e.getValue().username.equals(username));if(!ok){json(x,404,Map.of("error","User not found or cannot be disabled"));return;}db.audit(session.username,disabled?"USER_DISABLED":"USER_ENABLED",username,clientIp(x),"");json(x,200,Map.of("ok",true));}
    private void setMaintenance(HttpExchange x,Session session) throws Exception {
        Map<?,?> b=body(x);
        maintenance.update(b);
        Map<String,Object> state = maintenance.view();
        if (maintenance.enabled() && maintenance.kickExistingPlayers()) {
            // Apply the current policy whenever maintenance settings change, not
            // only on the OFF -> ON transition (e.g. whitelist removal).
            kickPlayersNotAllowedByMaintenance();
        }
        db.audit(session.username,"MAINTENANCE_CHANGED",null,clientIp(x),"enabled="+state.get("enabled"));
        Map<String,Object> response = new LinkedHashMap<>(state); response.put("ok",true);
        json(x,200,response);
    }

    private void kickPlayersNotAllowedByMaintenance() {
        if (!maintenance.enabled() || !maintenance.kickExistingPlayers()) return;
        for (Player player : proxy.getAllPlayers()) {
            if (!maintenance.isAllowed(player.getUniqueId().toString())) {
                player.disconnect(Component.text(maintenance.kickMessage()));
            }
        }
    }

    private boolean apiKeyWriteAllowed(String path, String method) {
        if (!"POST".equals(method)) return false;
        return path.equals("/api/v1/monitoring/probe")
                || path.equals("/api/v1/command-center")
                || path.equals("/api/v1/alerts/ack-all")
                || path.matches("/api/v1/alerts/[0-9]+/ack");
    }

    private void requireOwner(HttpExchange x,Session s) throws ForbiddenException {if(!s.role.equals("OWNER"))throw new ForbiddenException();}
    private void requireWriteAdmin(HttpExchange x,Session s) throws ForbiddenException {
        if (s.apiKey) { if (!s.apiWrite) throw new ForbiddenException(); return; }
        if (!s.role.equals("OWNER") && !s.role.equals("ADMINISTRATOR")) throw new ForbiddenException();
    }
    private String certificateFingerprint(TlsCertificateDetector.Material material) {
        try {
            java.security.cert.X509Certificate certificate;
            if (material.kind() == TlsCertificateDetector.Material.Kind.PEM) {
                try (InputStream in = Files.newInputStream(material.certificate())) { certificate = (java.security.cert.X509Certificate) java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(in); }
            } else {
                java.security.KeyStore store = java.security.KeyStore.getInstance(material.keyStoreType());
                try (InputStream in = Files.newInputStream(material.keyStore())) { store.load(in, material.keyStorePassword()==null?new char[0]:material.keyStorePassword().toCharArray()); }
                certificate = null; var aliases = store.aliases(); while (aliases.hasMoreElements()) { var cert=store.getCertificate(aliases.nextElement()); if(cert instanceof java.security.cert.X509Certificate x) { certificate=x; break; } }
                if(certificate==null)return "";
            }
            byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
            return java.util.HexFormat.of().formatHex(digest);
        } catch(Exception e) { return ""; }
    }

    private Map<String,Object> tlsStatus() {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("requestedMode", config.webMode());
        result.put("activeMode", activeMode);
        result.put("httpsAvailable", activeMode.equals("HTTPS") || activeMode.equals("BOTH"));
        result.put("certificateDetected", tlsMaterial != null);
        result.put("httpsPort", config.httpsPort());
        result.put("httpPort", config.httpPort());
        result.put("acmeEnabled", config.acmeEnabled());
        result.put("acmeLastCheck", lastAcmeCheck); result.put("acmeLastError", lastAcmeError); result.put("certificateFingerprint", lastCertificateFingerprint.isBlank()?null:lastCertificateFingerprint);
        result.put("reverseProxyMode", config.reverseProxyMode());
        result.put("tlsTermination", config.reverseProxyMode()?"TRUSTED_REVERSE_PROXY":"PANEL_LISTENER");
        if (config.acmeEnabled()) result.put("acmeDomain", config.acmeDomain());
        if (tlsMaterial != null) result.put("certificate", tlsMaterial.view());
        return result;
    }
    private void requireRole(HttpExchange x,Session s,String... roles) throws ForbiddenException {if(Arrays.stream(roles).noneMatch(r->r.equals(s.role)))throw new ForbiddenException();}
    private Map<?,?> body(HttpExchange x) throws IOException {if(x.getRequestBody()==null)return Map.of();byte[] data=x.getRequestBody().readNBytes(1_048_577);if(data.length>1_048_576)throw new IOException("Request body too large");if(data.length==0)return Map.of();Object o=gson.fromJson(new String(data,StandardCharsets.UTF_8),Object.class);if(!(o instanceof Map<?,?> m))throw new IOException("Expected JSON object");return m;}
    private int queryInt(HttpExchange x,String key,int fallback){try{return Integer.parseInt(query(x).getOrDefault(key,String.valueOf(fallback)));}catch(Exception e){return fallback;}}
    private Map<String,String> query(HttpExchange x){Map<String,String> m=new HashMap<>();String q=x.getRequestURI().getRawQuery();if(q!=null)for(String pair:q.split("&")){String[] p=pair.split("=",2);try{m.put(URLDecoder.decode(p[0],StandardCharsets.UTF_8),p.length>1?URLDecoder.decode(p[1],StandardCharsets.UTF_8):"");}catch(Exception ignored){}}return m;}
    private String string(Map<?,?> m,String k,String d){Object v=m.get(k);return v==null?d:String.valueOf(v);}
    private String cookie(HttpExchange x,String name){String c=x.getRequestHeaders().getFirst("Cookie");if(c==null)return null;for(String pair:c.split(";")){String[] p=pair.trim().split("=",2);if(p.length==2&&p[0].equals(name))return p[1];}return null;}
    private String clientIp(HttpExchange x){InetSocketAddress a=x.getRemoteAddress();return a==null?"unknown":a.getAddress().getHostAddress();}
    private String randomToken(){byte[] b=new byte[32];new java.security.SecureRandom().nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
    private void addSecurityHeaders(HttpExchange x){
        Headers h=x.getResponseHeaders();
        h.set("X-Content-Type-Options","nosniff"); h.set("X-Frame-Options","DENY");
        h.set("Referrer-Policy","no-referrer"); h.set("Permissions-Policy","camera=(), microphone=(), geolocation=()");
        h.set("Cross-Origin-Opener-Policy","same-origin"); h.set("Cross-Origin-Resource-Policy","same-origin");
        h.set("Content-Security-Policy","default-src 'self'; base-uri 'self'; form-action 'self'; object-src 'none'; frame-ancestors 'none'; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'");
        h.set("Cache-Control","no-store");
        if (x instanceof HttpsExchange) h.set("Strict-Transport-Security","max-age=31536000; includeSubDomains");
    }
    private void serveResource(HttpExchange x,String resource,String type)throws IOException{
        try(InputStream in=getClass().getResourceAsStream(resource)){
            if(in==null){json(x,404,Map.of("error","Resource not found"));return;}
            byte[] b=in.readAllBytes();x.getResponseHeaders().set("Content-Type",type);
            x.sendResponseHeaders(200,b.length);try(OutputStream out=x.getResponseBody()){out.write(b);}
        }
    }
    private void json(HttpExchange x,int status,Object data)throws IOException{
        byte[] b=gson.toJson(data).getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
        x.sendResponseHeaders(status,b.length);try(OutputStream out=x.getResponseBody()){out.write(b);}
    }
    private void safeJson(HttpExchange x,int status,Object data){try{json(x,status,data);}catch(Exception ignored){}}
    private void stopListeners() {
        for (HttpServer server : webServers.values()) {
            try { server.stop(0); } catch (Exception e) { logger.warn("Failed to stop web panel listener cleanly", e); }
        }
        webServers.clear();
    }

    public void stop() {
        stopListeners();
        webExecutor.shutdownNow();
        alertExecutor.shutdownNow();
        sessions.clear();
        pendingLogins.clear();
        loginAttempts.clear();
        failedLogins.clear();
    }
    private record Session(String username,String role,long expiresAt,boolean apiKey,boolean apiWrite){}
    private record FailureState(int failures,long blockedUntil,long lastFailure){}
    private record PendingLogin(String username,String role,long expiresAt,String ip){}
    private record BackendHealth(String status,long latencyMs,long checkedAt){}
    private static final class ForbiddenException extends Exception{}
}
