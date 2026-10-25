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

import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
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
    private final List<HttpServer> servers = new CopyOnWriteArrayList<>();
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> { Thread t=new Thread(r,"Vertex-WebPanel-HTTP");t.setDaemon(true);return t; });
    private final AtomicInteger httpErrors = new AtomicInteger();
    private volatile boolean maintenanceEnabled;
    private volatile String maintenanceMessage = "The network is under maintenance.";
    private final Map<String, BackendHealth> backendHealth = new ConcurrentHashMap<>();

    public WebPanelServer(ProxyServer proxy, Logger logger, PanelConfig config, PanelDatabase db, Instant startedAt, Path dataDirectory) { this.proxy=proxy;this.logger=logger;this.config=config;this.db=db;this.startedAt=startedAt;this.dataDirectory=dataDirectory; }

    public void start() throws Exception {
        if (!config.webEnabled()) { logger.info("Web panel is disabled in config.yml"); return; }
        String mode=config.webMode();
        if (!Set.of("HTTP","HTTPS","BOTH").contains(mode)) throw new IllegalStateException("web-panel.mode must be HTTP, HTTPS or BOTH");
        if ((mode.equals("HTTPS") || mode.equals("BOTH")) && !config.httpsEnabled()) throw new IllegalStateException("HTTPS requested but web-panel.https.enabled is false");
        try {
            if (mode.equals("HTTPS") || mode.equals("BOTH")) startHttps();
            if (mode.equals("HTTP") || mode.equals("BOTH")) startHttp();
        } catch (Exception e) { stop(); throw e; }
        if (servers.isEmpty()) throw new IllegalStateException("No web listener started. Check web-panel.mode and configuration.");
        long interval=Math.max(5,config.sampleSeconds());
        proxy.getScheduler().buildTask(getPluginOwner(), () -> {
            db.sampleMetric(proxy.getPlayerCount(), proxy.getAllServers().size(), Math.max(0,Instant.now().getEpochSecond()-startedAt.getEpochSecond()));
            db.prune(config.retentionDays());
            probeBackends();
        }).repeat(interval,TimeUnit.SECONDS).schedule();
    }

    private Object pluginOwner;
    public void setPluginOwner(Object owner) { this.pluginOwner=owner; }
    private Object getPluginOwner() { if(pluginOwner==null) throw new IllegalStateException("Plugin scheduler owner was not provided"); return pluginOwner; }

    private void startHttp() throws IOException { HttpServer s=HttpServer.create(new InetSocketAddress(config.bindAddress(),config.httpPort()),64); configure(s); servers.add(s);s.start();logger.info("Vertex Web Panel HTTP listening on {}:{}",config.bindAddress(),config.httpPort()); }
    private void startHttps() throws Exception {
        String path=config.keystore(); if(path.isBlank())throw new IllegalStateException("HTTPS keystore path is empty");
        char[] password=config.keystorePassword().toCharArray(); KeyStore ks=KeyStore.getInstance(config.keystoreType());
        try(InputStream in=Files.newInputStream(Path.of(path))){ks.load(in,password);}
        KeyManagerFactory kmf=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());kmf.init(ks,password);
        SSLContext ssl=SSLContext.getInstance("TLS");ssl.init(kmf.getKeyManagers(),null,null);
        HttpsServer s=HttpsServer.create(new InetSocketAddress(config.bindAddress(),config.httpsPort()),64);
        s.setHttpsConfigurator(new HttpsConfigurator(ssl){@Override public void configure(HttpsParameters params){SSLContext c=getSSLContext();SSLEngine e=c.createSSLEngine();params.setNeedClientAuth(false);params.setCipherSuites(e.getEnabledCipherSuites());params.setProtocols(Arrays.stream(e.getEnabledProtocols()).filter(p->p.equals("TLSv1.3")||p.equals("TLSv1.2")).toArray(String[]::new));params.setSSLParameters(c.getDefaultSSLParameters());}});
        configure(s);servers.add(s);s.start();logger.info("Vertex Web Panel HTTPS listening on {}:{}",config.bindAddress(),config.httpsPort());
    }
    private void configure(HttpServer s) { s.setExecutor(executor);s.createContext("/",this::handle); }

    private void handle(HttpExchange x) throws IOException {
        try {
            addSecurityHeaders(x); String path=x.getRequestURI().getPath(); String method=x.getRequestMethod().toUpperCase(Locale.ROOT);
            if(path.equals("/")||path.equals("/index.html")){serveResource(x,"/web/index.html","text/html; charset=utf-8");return;}
            if(path.startsWith("/assets/")){serveResource(x,"/web"+path,"text/plain; charset=utf-8");return;}
            if(path.equals("/api/v1/health")&&method.equals("GET")){json(x,200,Map.of("ok",true,"plugin","Vertex Velocity Web Panel","version","1.1.0","timestamp",Instant.now().getEpochSecond()));return;}
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
            if(session.apiKey && !method.equals("GET")){json(x,403,Map.of("error","API keys are read-only"));return;}
            if(path.equals("/api/v1/2fa")&&method.equals("GET")){json(x,200,Map.of("enabled",db.totpEnabled(session.username)));return;}
            if(path.equals("/api/v1/2fa/setup")&&method.equals("POST")){String secret=Totp.newBase32Secret();db.createPendingTotp(session.username,secret);db.audit(session.username,"TOTP_SETUP_STARTED",session.username,clientIp(x),"Pending TOTP secret created");json(x,200,Map.of("secret",secret,"otpauthUri",Totp.uri(session.username,secret),"message","Add this account to your authenticator app, then confirm with a current six-digit code."));return;}
            if(path.equals("/api/v1/2fa/enable")&&method.equals("POST")){Map<?,?> b=body(x);String secret=db.pendingTotpSecret(session.username);if(secret==null||!Totp.verify(secret,string(b,"code",""))){json(x,400,Map.of("error","Invalid code or no pending setup. Start setup again."));return;}db.enableTotp(session.username);db.audit(session.username,"TOTP_ENABLED",session.username,clientIp(x),"TOTP enabled");json(x,200,Map.of("ok",true,"enabled",true));return;}
            if(path.equals("/api/v1/2fa/disable")&&method.equals("POST")){Map<?,?> b=body(x);Optional<UserRecord> u=db.findUser(session.username);String password=string(b,"password","");String code=string(b,"code","");String secret=db.totpSecret(session.username);if(u.isEmpty()||!PasswordUtil.verify(password,u.get().salt(),u.get().passwordHash())||secret==null||!Totp.verify(secret,code)){json(x,400,Map.of("error","Password or authenticator code is invalid"));return;}db.disableTotp(session.username);db.audit(session.username,"TOTP_DISABLED",session.username,clientIp(x),"TOTP disabled");json(x,200,Map.of("ok",true,"enabled",false));return;}
            if(path.equals("/api/v1/integrations/keys")&&method.equals("GET")){requireOwner(x,session);json(x,200,db.apiKeys());return;}
            if(path.equals("/api/v1/integrations/keys")&&method.equals("POST")){requireOwner(x,session);createApiKey(x,session);return;}
            if(path.startsWith("/api/v1/integrations/keys/")&&method.equals("DELETE")){requireOwner(x,session);revokeApiKey(x,session,path);return;}
            if(path.equals("/api/v1/me")&&method.equals("GET")){json(x,200,Map.of("username",session.username,"role",session.role,"expiresAt",session.expiresAt));return;}
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
            if(path.equals("/api/v1/events")&&method.equals("GET")){json(x,200,db.events(queryInt(x,"limit",100)));return;}
            if(path.equals("/api/v1/audit")&&method.equals("GET")){requireRole(x,session,"OWNER","ADMINISTRATOR");json(x,200,db.auditLogs(queryInt(x,"limit",100)));return;}
            if(path.equals("/api/v1/config")&&method.equals("GET")){requireRole(x,session,"OWNER","ADMINISTRATOR");json(x,200,config.publicView());return;}
            if(path.equals("/api/v1/config")&&method.equals("PUT")){requireOwner(x,session);updateConfig(x,session);return;}
            if(path.equals("/api/v1/users")&&method.equals("GET")){requireRole(x,session,"OWNER");json(x,200,db.users());return;}
            if(path.equals("/api/v1/users")&&method.equals("POST")){requireOwner(x,session);createUser(x,session);return;}
            if(path.startsWith("/api/v1/users/")&&method.equals("POST")){requireOwner(x,session);disableUser(x,session,path);return;}
            if(path.equals("/api/v1/maintenance")&&method.equals("GET")){json(x,200,Map.of("enabled",maintenanceEnabled,"message",maintenanceMessage));return;}
            if(path.equals("/api/v1/maintenance")&&method.equals("PUT")){requireRole(x,session,"OWNER","ADMINISTRATOR");setMaintenance(x,session);return;}
            json(x,404,Map.of("error","Not found"));
        } catch (ForbiddenException e) { safeJson(x,403,Map.of("error","Insufficient permissions")); }
        catch (IllegalArgumentException e) { safeJson(x,400,Map.of("error",e.getMessage()==null?"Invalid request":e.getMessage())); }
        catch (Exception e) { httpErrors.incrementAndGet(); logger.warn("Web panel request failed: {} {}",x.getRequestMethod(),x.getRequestURI(),e); safeJson(x,500,Map.of("error","Internal server error")); }
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
        String ip=clientIp(x); if(!allowAttempt(ip)){json(x,429,Map.of("error","Too many login attempts. Try again shortly."));return;}
        Map<?,?> body=body(x);String username=string(body,"username","");String password=string(body,"password","");
        Optional<UserRecord> found=db.findUser(username);
        if(found.isEmpty()||found.get().disabled()||!PasswordUtil.verify(password,found.get().salt(),found.get().passwordHash())){db.audit(username,"LOGIN_FAILED",null,ip,"Invalid credentials");json(x,401,Map.of("error","Invalid username or password"));return;}
        UserRecord user=found.get();
        if(db.totpEnabled(user.username())){long now=Instant.now().getEpochSecond();pendingLogins.entrySet().removeIf(e->e.getValue().expiresAt<now);String challenge=randomToken();pendingLogins.put(challenge,new PendingLogin(user.username(),user.role(),now+300));json(x,202,Map.of("requiresTotp",true,"challengeId",challenge,"message","Enter the six-digit code from your authenticator app."));return;}
        createSession(x,user.username(),user.role(),ip);
    }
    private void completeTotpLogin(HttpExchange x) throws Exception {
        String ip=clientIp(x);if(!allowAttempt(ip)){json(x,429,Map.of("error","Too many login attempts. Try again shortly."));return;}
        Map<?,?> b=body(x);String id=string(b,"challengeId","");String code=string(b,"code","");PendingLogin pending=pendingLogins.get(id);
        if(pending==null||pending.expiresAt<Instant.now().getEpochSecond()){pendingLogins.remove(id);json(x,401,Map.of("error","Authentication challenge expired. Sign in again."));return;}
        String secret=db.totpSecret(pending.username);if(secret==null||!Totp.verify(secret,code)){db.audit(pending.username,"LOGIN_2FA_FAILED",null,ip,"Invalid TOTP code");json(x,400,Map.of("error","Invalid authenticator code"));return;}
        pendingLogins.remove(id);createSession(x,pending.username,pending.role,ip);
    }
    private void createSession(HttpExchange x,String username,String role,String ip) throws Exception {
        String token=randomToken();long expiry=Instant.now().getEpochSecond()+config.sessionMinutes()*60L;sessions.put(token,new Session(username,role,expiry,false));
        db.audit(username,"LOGIN_SUCCESS",null,ip,"Web panel login");x.getResponseHeaders().add("Set-Cookie","vv_session="+token+"; Path=/; HttpOnly; SameSite=Strict"+(x instanceof HttpsExchange?"; Secure":"")+"; Max-Age="+(config.sessionMinutes()*60));json(x,200,Map.of("ok",true,"username",username,"role",role,"expiresAt",expiry));
    }
    private void logout(HttpExchange x) throws IOException {String token=cookie(x,"vv_session");if(token!=null){Session s=sessions.remove(token);if(s!=null)db.audit(s.username,"LOGOUT",null,clientIp(x),"Web panel logout");}x.getResponseHeaders().add("Set-Cookie","vv_session=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");json(x,200,Map.of("ok",true));}
    private Session authenticate(HttpExchange x){
        String authorization=x.getRequestHeaders().getFirst("Authorization");
        if(authorization!=null && authorization.startsWith("Bearer ")){
            String key=authorization.substring(7).trim();
            if(key.startsWith("vv_live_") && key.length()>=40) try { if(db.authenticateApiKey(PanelDatabase.hashApiKey(key))) return new Session("api-key","VIEWER",Long.MAX_VALUE,true); } catch(Exception ignored) {}
            return null;
        }
        String token=cookie(x,"vv_session");if(token==null)return null;Session s=sessions.get(token);if(s==null)return null;if(s.expiresAt<Instant.now().getEpochSecond()){sessions.remove(token);return null;}return s;
    }
    private boolean allowAttempt(String ip){long now=System.currentTimeMillis();ArrayDeque<Long> q=loginAttempts.computeIfAbsent(ip,k->new ArrayDeque<>());synchronized(q){while(!q.isEmpty()&&now-q.peekFirst()>60_000)q.removeFirst();if(q.size()>=config.loginAttemptsPerMinute())return false;q.addLast(now);return true;}}

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
            List<Path> old = files.filter(Files::isRegularFile)
                    .sorted(Comparator.comparingLong((Path path) -> path.toFile().lastModified()).reversed())
                    .skip(20)
                    .collect(java.util.stream.Collectors.toList());
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

    private Map<String,Object> stats() throws Exception {
        Map<String,Object> m=new LinkedHashMap<>();m.put("onlinePlayers",proxy.getPlayerCount());m.put("backendCount",proxy.getAllServers().size());m.put("uptimeSeconds",Math.max(0,Instant.now().getEpochSecond()-startedAt.getEpochSecond()));m.put("startedAt",startedAt.getEpochSecond());m.put("jvmUsedBytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory());m.put("jvmTotalBytes",Runtime.getRuntime().totalMemory());m.put("jvmMaxBytes",Runtime.getRuntime().maxMemory());m.put("processors",Runtime.getRuntime().availableProcessors());m.put("httpErrors",httpErrors.get());m.put("maintenance",maintenanceEnabled);m.put("latestEvents",db.events(8));return m;
    }
    private List<Map<String,Object>> players(Map<String,String> filters){List<Map<String,Object>> out=new ArrayList<>();String serverFilter=filters.getOrDefault("server","");String search=filters.getOrDefault("search","").toLowerCase(Locale.ROOT);int limit=Math.max(1,Math.min(500,queryIntFromMap(filters,"limit",100)));for(Player p:proxy.getAllPlayers()){String backend=p.getCurrentServer().map(s->s.getServerInfo().getName()).orElse("Connecting");if(!serverFilter.isBlank()&&!backend.equalsIgnoreCase(serverFilter))continue;if(!search.isBlank()&&!p.getUsername().toLowerCase(Locale.ROOT).contains(search))continue;Map<String,Object> m=new LinkedHashMap<>();m.put("username",p.getUsername());m.put("uuid",p.getUniqueId().toString());m.put("ping",p.getPing());m.put("server",backend);out.add(m);}out.sort(Comparator.comparing(m->String.valueOf(m.get("username")),String.CASE_INSENSITIVE_ORDER));return out.stream().limit(limit).toList();}
    private int queryIntFromMap(Map<String,String> values,String key,int fallback){try{return Integer.parseInt(values.getOrDefault(key,String.valueOf(fallback)));}catch(NumberFormatException e){return fallback;}}
    private void serverDetails(HttpExchange x,String name)throws Exception{RegisteredServer server=proxy.getServer(name).orElse(null);if(server==null){json(x,404,Map.of("error","Unknown backend","server",name));return;}Map<String,Object> result=new LinkedHashMap<>();result.put("name",server.getServerInfo().getName());result.put("address",server.getServerInfo().getAddress().getHostString());result.put("port",server.getServerInfo().getAddress().getPort());List<Map<String,Object>> connected=new ArrayList<>();for(Player p:server.getPlayersConnected()){Map<String,Object> player=new LinkedHashMap<>();player.put("username",p.getUsername());player.put("uuid",p.getUniqueId().toString());player.put("ping",p.getPing());connected.add(player);}result.put("playerCount",connected.size());result.put("players",connected);BackendHealth health=backendHealth.get(server.getServerInfo().getName());result.put("health",health==null?"CHECKING":health.status);if(health!=null){result.put("latencyMs",health.latencyMs);result.put("lastCheck",health.checkedAt);}json(x,200,result);}
    private List<Map<String,Object>> serversView(){List<Map<String,Object>> out=new ArrayList<>();for(RegisteredServer s:proxy.getAllServers()){Map<String,Object> m=new LinkedHashMap<>();m.put("name",s.getServerInfo().getName());m.put("address",s.getServerInfo().getAddress().getHostString());m.put("port",s.getServerInfo().getAddress().getPort());m.put("connectedPlayers",s.getPlayersConnected().stream().map(Player::getUsername).toList());m.put("playerCount",s.getPlayersConnected().size());BackendHealth health=backendHealth.get(s.getServerInfo().getName());m.put("health",health==null?"CHECKING":health.status);m.put("latencyMs",health==null?null:health.latencyMs);m.put("lastCheck",health==null?null:health.checkedAt);out.add(m);}out.sort(Comparator.comparing(m->String.valueOf(m.get("name"))));return out;}
    private void playerAction(HttpExchange x,Session session,String path) throws Exception {
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
            sendDiscordAlert("Backend "+name+" "+status,details);
        } else if(latency>=1000 && (previous.latencyMs<1000 || previous.latencyMs<0)) { sendDiscordAlert("High backend latency: "+name, "Measured ping is "+latency+" ms."); }
    }

    public void notifyProxyStarted() { sendDiscordAlert("Velocity proxy started", "Vertex Velocity Web Panel is online. Backend monitoring is active."); }
    private void sendDiscordAlert(String title,String description) {
        if(!config.discordEnabled() || config.discordWebhook().isBlank()) return;
        String url=config.discordWebhook().trim(); if(!url.startsWith("https://discord.com/api/webhooks/") && !url.startsWith("https://discordapp.com/api/webhooks/")) { logger.warn("Discord webhook ignored: only official HTTPS Discord webhook URLs are accepted"); return; }
        CompletableFuture.runAsync(() -> {
            try {
                Map<String,Object> embed=new LinkedHashMap<>();embed.put("title",title);embed.put("description",description);embed.put("color",0x8B5CF6);embed.put("timestamp",Instant.now().toString());
                String payload=gson.toJson(Map.of("username","Vertex Velocity","embeds",List.of(embed)));
                HttpURLConnection c=(HttpURLConnection)URI.create(url).toURL().openConnection();c.setRequestMethod("POST");c.setConnectTimeout(3000);c.setReadTimeout(3000);c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json; charset=utf-8");byte[] bytes=payload.getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(bytes.length);try(OutputStream out=c.getOutputStream()){out.write(bytes);}int code=c.getResponseCode();if(code<200||code>=300)logger.warn("Discord webhook returned HTTP {}",code);c.disconnect();
            } catch(Exception e) { logger.warn("Could not deliver Discord alert: {}",e.getMessage()); }
        },executor);
    }

    private void updateConfig(HttpExchange x,Session session) throws Exception {
        Map<?,?> body=body(x);
        config.updateEditable(body);
        Map<String,Object> saved=new LinkedHashMap<>(); saved.put("mode",config.webMode()); saved.put("bindAddress",config.bindAddress()); saved.put("httpPort",config.httpPort()); saved.put("httpsPort",config.httpsPort()); saved.put("httpsEnabled",config.httpsEnabled()); saved.put("sampleSeconds",config.sampleSeconds()); saved.put("retentionDays",config.retentionDays());
        db.audit(session.username,"CONFIG_UPDATE",null,clientIp(x),gson.toJson(saved));
        json(x,202,Map.of("ok",true,"message","Settings saved to config.yml. Listener, TLS, and bind changes require a proxy restart; data collection settings also take effect after restart.","restartRequired",true,"accepted",saved));
    }
    private void createApiKey(HttpExchange x,Session session) throws Exception {
        Map<?,?> b=body(x);String name=string(b,"name","").trim();if(name.length()<3||name.length()>48){json(x,400,Map.of("error","Key name must be 3 to 48 characters"));return;}
        String raw="vv_live_"+randomToken();String hash=PanelDatabase.hashApiKey(raw);
        if(!db.createApiKey(name,hash,session.username)){json(x,400,Map.of("error","Invalid name or duplicate key"));return;}
        db.audit(session.username,"API_KEY_CREATED",name,clientIp(x),"Read-only API key created");json(x,201,Map.of("ok",true,"name",name,"apiKey",raw,"warning","Copy this key now. It cannot be displayed again."));
    }
    private void revokeApiKey(HttpExchange x,Session session,String path) throws Exception {
        String raw=path.substring("/api/v1/integrations/keys/".length());long id;try{id=Long.parseLong(raw);}catch(NumberFormatException e){json(x,400,Map.of("error","Invalid key id"));return;}
        if(!db.revokeApiKey(id)){json(x,404,Map.of("error","Key not found or already revoked"));return;}db.audit(session.username,"API_KEY_REVOKED",raw,clientIp(x),"Read-only API key revoked");json(x,200,Map.of("ok",true));
    }
    private void createUser(HttpExchange x,Session session) throws Exception {Map<?,?> b=body(x);String username=string(b,"username","");String password=string(b,"password","");String role=string(b,"role","VIEWER").toUpperCase(Locale.ROOT);if(password.length()<12||password.length()>256){json(x,400,Map.of("error","Password must be 12 to 256 characters"));return;}String salt=PasswordUtil.newSalt();boolean ok=db.createUser(username,salt,PasswordUtil.hash(password,salt),role);if(!ok){json(x,400,Map.of("error","Invalid role, username or duplicate username"));return;}db.audit(session.username,"USER_CREATED",username,clientIp(x),role);json(x,201,Map.of("ok",true));}
    private void disableUser(HttpExchange x,Session session,String path) throws Exception {String username=URLDecoder.decode(path.substring("/api/v1/users/".length()),StandardCharsets.UTF_8);Map<?,?> b=body(x);boolean disabled=Boolean.parseBoolean(String.valueOf(b.containsKey("disabled")?b.get("disabled"):Boolean.TRUE));boolean ok=db.disableUser(username,disabled);if(disabled)sessions.entrySet().removeIf(e->e.getValue().username.equals(username));if(!ok){json(x,404,Map.of("error","User not found or cannot be disabled"));return;}db.audit(session.username,disabled?"USER_DISABLED":"USER_ENABLED",username,clientIp(x),"");json(x,200,Map.of("ok",true));}
    private void setMaintenance(HttpExchange x,Session session) throws Exception {Map<?,?> b=body(x);maintenanceEnabled=Boolean.parseBoolean(String.valueOf(b.containsKey("enabled")?b.get("enabled"):Boolean.FALSE));maintenanceMessage=string(b,"message","The network is under maintenance.");if(maintenanceMessage.length()>200)maintenanceMessage=maintenanceMessage.substring(0,200);db.audit(session.username,"MAINTENANCE_CHANGED",null,clientIp(x),"enabled="+maintenanceEnabled);json(x,200,Map.of("ok",true,"enabled",maintenanceEnabled,"message",maintenanceMessage));}

    private void requireOwner(HttpExchange x,Session s) throws ForbiddenException {if(!s.role.equals("OWNER"))throw new ForbiddenException();}
    private void requireRole(HttpExchange x,Session s,String... roles) throws ForbiddenException {if(Arrays.stream(roles).noneMatch(r->r.equals(s.role)))throw new ForbiddenException();}
    private Map<?,?> body(HttpExchange x) throws IOException {if(x.getRequestBody()==null)return Map.of();byte[] data=x.getRequestBody().readNBytes(1_048_577);if(data.length>1_048_576)throw new IOException("Request body too large");if(data.length==0)return Map.of();Object o=gson.fromJson(new String(data,StandardCharsets.UTF_8),Object.class);if(!(o instanceof Map<?,?> m))throw new IOException("Expected JSON object");return m;}
    private int queryInt(HttpExchange x,String key,int fallback){try{return Integer.parseInt(query(x).getOrDefault(key,String.valueOf(fallback)));}catch(Exception e){return fallback;}}
    private Map<String,String> query(HttpExchange x){Map<String,String> m=new HashMap<>();String q=x.getRequestURI().getRawQuery();if(q!=null)for(String pair:q.split("&")){String[] p=pair.split("=",2);try{m.put(URLDecoder.decode(p[0],StandardCharsets.UTF_8),p.length>1?URLDecoder.decode(p[1],StandardCharsets.UTF_8):"");}catch(Exception ignored){}}return m;}
    private String string(Map<?,?> m,String k,String d){Object v=m.get(k);return v==null?d:String.valueOf(v);}
    private String cookie(HttpExchange x,String name){String c=x.getRequestHeaders().getFirst("Cookie");if(c==null)return null;for(String pair:c.split(";")){String[] p=pair.trim().split("=",2);if(p.length==2&&p[0].equals(name))return p[1];}return null;}
    private String clientIp(HttpExchange x){InetSocketAddress a=x.getRemoteAddress();return a==null?"unknown":a.getAddress().getHostAddress();}
    private String randomToken(){byte[] b=new byte[32];new java.security.SecureRandom().nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
    private void addSecurityHeaders(HttpExchange x){Headers h=x.getResponseHeaders();h.set("X-Content-Type-Options","nosniff");h.set("X-Frame-Options","DENY");h.set("Referrer-Policy","no-referrer");h.set("Content-Security-Policy","default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'");h.set("Cache-Control","no-store");}
    private void serveResource(HttpExchange x,String resource,String type)throws IOException{try(InputStream in=getClass().getResourceAsStream(resource)){if(in==null){json(x,404,Map.of("error","Resource not found"));return;}byte[] b=in.readAllBytes();x.getResponseHeaders().set("Content-Type",type);x.sendResponseHeaders(200,b.length);x.getResponseBody().write(b);}}
    private void json(HttpExchange x,int status,Object data)throws IOException{byte[] b=gson.toJson(data).getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");x.sendResponseHeaders(status,b.length);x.getResponseBody().write(b);}
    private void safeJson(HttpExchange x,int status,Object data){try{json(x,status,data);}catch(Exception ignored){}}
    public void stop(){for(HttpServer s:servers)s.stop(1);servers.clear();executor.shutdownNow();sessions.clear();}
    private record Session(String username,String role,long expiresAt,boolean apiKey){}
    private record PendingLogin(String username,String role,long expiresAt){}
    private record BackendHealth(String status,long latencyMs,long checkedAt){}
    private static final class ForbiddenException extends Exception{}
}
