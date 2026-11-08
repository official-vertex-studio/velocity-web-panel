package fr.vertexvelocity.webpanel.service;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMEncryptedKeyPair;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509TrustManager;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Provider;
import java.security.Signature;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Automatically selects a domain-matched Certbot certificate, otherwise reuses or creates
 * the plugin's private self-signed fallback. A stable SSLContext delegates to a reloadable
 * key manager so renewed Certbot certificates are used without restarting the HTTPS listener.
 */
public final class TlsCertificateDetector {
    public record Selection(SSLContext context, String source, String subject, Instant expiresAt, String fingerprint) {}

    private static final char[] EMPTY_PASSWORD = new char[0];
    private static final Provider BC_PROVIDER = new BouncyCastleProvider();
    private final Path dataDirectory;
    private volatile String lastDiagnostic = "";
    private volatile ReloadableKeyManager reloadableKeyManager;
    private volatile SSLContext sharedContext;
    private volatile Selection currentSelection;

    public TlsCertificateDetector(Path dataDirectory) {
        this.dataDirectory = dataDirectory.toAbsolutePath().normalize();
    }

    public String lastDiagnostic() { return lastDiagnostic; }

    /**
     * Returns the same SSLContext for the lifetime of this detector. If the selected certificate
     * changes, only the key-manager delegate is replaced; HttpsServer can keep its listener bound.
     */
    public synchronized Selection createSslContext(String configuredKeystore, String configuredType,
                                                    String configuredPassword) throws Exception {
        Candidate candidate = null;
        Optional<PemMaterial> certbot = findCertbotCertificate();
        if (certbot.isPresent()) {
            PemMaterial material = certbot.get();
            try {
                X509Certificate leaf = material.chain().get(0);
                candidate = new Candidate(keyManagerFromKeyAndChain(material.privateKey(), material.chain()),
                        "Certbot / Let's Encrypt", leaf.getSubjectX500Principal().getName(),
                        leaf.getNotAfter().toInstant(), material.fingerprint());
            } catch (Exception error) {
                lastDiagnostic = "A matching Certbot certificate was found, but Java could not load its key material: " + error.getClass().getSimpleName();
            }
        }

        if (candidate == null) {
            Path keystore = Path.of(configuredKeystore == null || configuredKeystore.isBlank()
                    ? "tls/keystore.p12" : configuredKeystore);
            if (!keystore.isAbsolute()) keystore = dataDirectory.resolve(keystore).normalize();
            String type = configuredType == null || configuredType.isBlank() ? "PKCS12" : configuredType;
            String password = configuredPassword == null ? "" : configuredPassword;

            if (Files.isRegularFile(keystore)) {
                try {
                    candidate = candidateFromKeystore(keystore, type, password);
                    restrictPermissions(keystore);
                    candidate = new Candidate(candidate.keyManager(), candidate.source(), candidate.subject(),
                            candidate.expiresAt(), fingerprint(keystore));
                } catch (Exception invalid) {
                    lastDiagnostic = appendDiagnostic(lastDiagnostic,
                            "The plugin TLS keystore is invalid or unreadable; a new fallback certificate will be generated.");
                }
            }

            if (candidate == null) {
                Files.createDirectories(keystore.getParent());
                GeneratedCertificate generated = createSelfSignedCertificate();
                char[] secret = password.toCharArray();
                writePkcs12(keystore, generated.keyPair().getPrivate(), new Certificate[]{generated.certificate()}, secret);
                restrictPermissions(keystore);
                candidate = new Candidate(keyManagerFromKeyAndChain(generated.keyPair().getPrivate(),
                        List.of(generated.certificate())), "Auto-generated self-signed certificate",
                        generated.certificate().getSubjectX500Principal().getName(),
                        generated.certificate().getNotAfter().toInstant(), fingerprint(keystore));
            }
        }

        Selection previous = currentSelection;
        if (previous != null && previous.source().contains("Certbot")
                && !candidate.source().contains("Certbot") && previous.expiresAt().isAfter(Instant.now())) {
            // Do not downgrade a working Certbot certificate because a transient permission/read error
            // made the renewed files unavailable. Keep the last known-good key-manager delegate.
            return previous;
        }

        if (reloadableKeyManager == null) {
            reloadableKeyManager = new ReloadableKeyManager(candidate.keyManager());
            sharedContext = SSLContext.getInstance("TLS");
            sharedContext.init(new KeyManager[]{reloadableKeyManager}, null, new SecureRandom());
        } else if (previous == null || !previous.fingerprint().equals(candidate.fingerprint())) {
            reloadableKeyManager.setDelegate(candidate.keyManager());
        }

        Selection selection = new Selection(sharedContext, candidate.source(), candidate.subject(),
                candidate.expiresAt(), candidate.fingerprint());
        currentSelection = selection;
        return selection;
    }

    private record Candidate(X509ExtendedKeyManager keyManager, String source, String subject,
                             Instant expiresAt, String fingerprint) {}
    private record PemMaterial(PrivateKey privateKey, List<X509Certificate> chain, String fingerprint) {}
    private record GeneratedCertificate(KeyPair keyPair, X509Certificate certificate) {}

    private Optional<PemMaterial> findCertbotCertificate() {
        lastDiagnostic = "";
        List<Map.Entry<Path, PemMaterial>> candidates = new ArrayList<>();
        Path letsEncrypt = Path.of("/etc/letsencrypt/live");
        if (Files.isDirectory(letsEncrypt)) {
            try (var entries = Files.list(letsEncrypt)) {
                for (Path directory : entries.filter(Files::isDirectory).limit(512).toList()) {
                    Optional<PemMaterial> material = readPemPair(directory.resolve("fullchain.pem"), directory.resolve("privkey.pem"));
                    material.ifPresent(value -> candidates.add(Map.entry(directory, value)));
                }
            } catch (Exception error) {
                lastDiagnostic = "Cannot inspect /etc/letsencrypt/live: " + error.getClass().getSimpleName()
                        + (error.getMessage() == null ? "" : " (" + error.getMessage() + ")");
            }
        }

        String hostname = "";
        try { hostname = InetAddress.getLocalHost().getCanonicalHostName(); } catch (Exception ignored) { }
        final String host = hostname;
        List<Map.Entry<Path, PemMaterial>> hostMatches = candidates.stream()
                .filter(entry -> !host.isBlank() && matchesHostname(entry.getValue().chain().get(0), host)).toList();
        if (!hostMatches.isEmpty()) {
            return hostMatches.stream()
                    .max(java.util.Comparator.comparing((Map.Entry<Path, PemMaterial> entry) -> entry.getValue().chain().get(0).getNotAfter()))
                    .map(Map.Entry::getValue);
        }
        // With one Certbot lineage, its name is a safe implicit panel-domain hint. With several
        // lineages, choosing by each directory's own SAN would always match and could pick another site's certificate.
        if (candidates.size() == 1) {
            Map.Entry<Path, PemMaterial> only = candidates.get(0);
            String lineage = only.getKey().getFileName() == null ? "" : only.getKey().getFileName().toString();
            if (lineage.matches(".*-\\d{4}$")) lineage = lineage.replaceFirst("-\\d{4}$", "");
            if (!lineage.isBlank() && matchesHostname(only.getValue().chain().get(0), lineage))
                return Optional.of(only.getValue());
        }
        if (!candidates.isEmpty()) {
            lastDiagnostic = "Several Certbot lineages exist but none matches the machine hostname, or the only lineage does not match its SAN. The plugin fallback will be used rather than selecting another domain's certificate.";
        }
        return Optional.empty();
    }

    private boolean matchesHostname(X509Certificate certificate, String hostname) {
        if (hostname == null || hostname.isBlank()) return false;
        String normalized = hostname.toLowerCase(Locale.ROOT).replaceAll("\\.$", "");
        try {
            Collection<List<?>> names = certificate.getSubjectAlternativeNames();
            if (names != null) for (List<?> entry : names) {
                if (entry.size() < 2 || !(entry.get(0) instanceof Integer type) || type != 2) continue;
                String dns = String.valueOf(entry.get(1)).toLowerCase(Locale.ROOT);
                if (dns.equals(normalized)) return true;
                if (dns.startsWith("*.") && normalized.endsWith(dns.substring(1))) {
                    String prefix = normalized.substring(0, normalized.length() - dns.substring(1).length());
                    if (!prefix.isEmpty() && !prefix.contains(".")) return true;
                }
            }
        } catch (Exception ignored) { }
        return false;
    }

    private Optional<PemMaterial> readPemPair(Path certificateFile, Path privateKeyFile) {
        try {
            if (!Files.isRegularFile(certificateFile) || !Files.isRegularFile(privateKeyFile)) {
                if (Files.exists(certificateFile) || Files.exists(privateKeyFile))
                    lastDiagnostic = "Certbot certificate files exist but cannot be accessed as regular files by the Velocity process.";
                return Optional.empty();
            }
            if (!Files.isReadable(certificateFile) || !Files.isReadable(privateKeyFile)) {
                lastDiagnostic = "Certbot certificate or private key exists but is not readable by the Velocity process. Check service-user group/ACL permissions.";
                return Optional.empty();
            }

            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            Collection<? extends Certificate> parsed;
            try (InputStream in = Files.newInputStream(certificateFile)) {
                parsed = factory.generateCertificates(in);
            }
            List<X509Certificate> chain = parsed.stream().filter(X509Certificate.class::isInstance)
                    .map(X509Certificate.class::cast).toList();
            if (chain.isEmpty()) throw new java.security.cert.CertificateException("Certificate chain is empty");
            for (X509Certificate certificate : chain) certificate.checkValidity();
            for (int i = 0; i + 1 < chain.size(); i++) {
                X509Certificate child = chain.get(i), issuer = chain.get(i + 1);
                if (!child.getIssuerX500Principal().equals(issuer.getSubjectX500Principal()))
                    throw new java.security.cert.CertificateException("Certificate chain issuer mismatch");
                child.verify(issuer.getPublicKey());
            }
            verifyTrustedChain(chain);

            PrivateKey key = readPrivateKey(privateKeyFile);
            if (key == null || !keyMatchesCertificate(key, chain.get(0)))
                throw new java.security.cert.CertificateException("Private key does not match the leaf certificate or is unsupported");
            return Optional.of(new PemMaterial(key, chain, fingerprint(certificateFile, privateKeyFile)));
        } catch (java.nio.file.AccessDeniedException denied) {
            lastDiagnostic = "Certbot certificate files are not readable by the Velocity process. Check the service-user group/ACL permissions.";
            return Optional.empty();
        } catch (Exception error) {
            if (Files.exists(certificateFile) || Files.exists(privateKeyFile))
                lastDiagnostic = "A Certbot PEM pair is invalid, expired, untrusted or unreadable: " + error.getClass().getSimpleName();
            return Optional.empty();
        }
    }

    private void verifyTrustedChain(List<X509Certificate> chain) throws Exception {
        TrustManagerFactory trustFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustFactory.init((KeyStore) null);
        Set<TrustAnchor> anchors = new HashSet<>();
        for (TrustManager manager : trustFactory.getTrustManagers()) {
            if (manager instanceof X509TrustManager x509) {
                for (X509Certificate issuer : x509.getAcceptedIssuers()) anchors.add(new TrustAnchor(issuer, null));
            }
        }
        if (anchors.isEmpty()) throw new java.security.cert.CertificateException("No trusted root certificates in the Java trust store");
        List<X509Certificate> pathCertificates = new ArrayList<>(chain);
        if (pathCertificates.size() > 1) {
            X509Certificate last = pathCertificates.get(pathCertificates.size() - 1);
            if (last.getSubjectX500Principal().equals(last.getIssuerX500Principal()))
                pathCertificates.remove(pathCertificates.size() - 1);
        }
        CertPath path = CertificateFactory.getInstance("X.509").generateCertPath(pathCertificates);
        PKIXParameters parameters = new PKIXParameters(anchors);
        parameters.setRevocationEnabled(false);
        CertPathValidator.getInstance("PKIX").validate(path, parameters);
    }

    private PrivateKey readPrivateKey(Path path) throws Exception {
        try (Reader reader = Files.newBufferedReader(path); PEMParser parser = new PEMParser(reader)) {
            Object object = parser.readObject();
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter().setProvider(BC_PROVIDER);
            if (object instanceof PEMKeyPair pair) return converter.getKeyPair(pair).getPrivate();
            if (object instanceof org.bouncycastle.asn1.pkcs.PrivateKeyInfo info) return converter.getPrivateKey(info);
            if (object instanceof PEMEncryptedKeyPair)
                throw new java.security.GeneralSecurityException("Encrypted Certbot private keys are not supported");
            return null;
        }
    }

    private boolean keyMatchesCertificate(PrivateKey key, X509Certificate certificate) {
        try {
            String algorithm = switch (key.getAlgorithm().toUpperCase(Locale.ROOT)) {
                case "RSA" -> "SHA256withRSA";
                case "EC", "ECDSA" -> "SHA256withECDSA";
                case "ED25519", "EDDSA" -> "Ed25519";
                case "DSA" -> "SHA256withDSA";
                default -> null;
            };
            if (algorithm == null) return false;
            byte[] challenge = "Vertex Velocity Web Panel TLS key-pair validation".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Signature signer = Signature.getInstance(algorithm);
            signer.initSign(key); signer.update(challenge);
            Signature verifier = Signature.getInstance(algorithm);
            verifier.initVerify(certificate.getPublicKey()); verifier.update(challenge);
            return verifier.verify(signer.sign());
        } catch (Exception ignored) { return false; }
    }

    private Candidate candidateFromKeystore(Path path, String type, String password) throws Exception {
        char[] secret = password.toCharArray();
        KeyStore store = KeyStore.getInstance(type);
        try (InputStream in = Files.newInputStream(path)) { store.load(in, secret); }
        X509Certificate certificate = null;
        Enumeration<String> aliases = store.aliases();
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            if (store.isKeyEntry(alias) && store.getCertificate(alias) instanceof X509Certificate x509) {
                x509.checkValidity(); certificate = x509; break;
            }
        }
        if (certificate == null) throw new IllegalStateException("No valid private-key entry in keystore");
        KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(store, secret);
        String source = certificate.getSubjectX500Principal().equals(certificate.getIssuerX500Principal())
                ? "Plugin keystore (self-signed fallback reused)" : "Plugin keystore (reused)";
        return new Candidate(extractKeyManager(factory.getKeyManagers()), source,
                certificate.getSubjectX500Principal().getName(), certificate.getNotAfter().toInstant(), fingerprint(path));
    }

    private X509ExtendedKeyManager keyManagerFromKeyAndChain(PrivateKey key, List<? extends Certificate> chain) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, EMPTY_PASSWORD);
        store.setKeyEntry("vertex-panel", key, EMPTY_PASSWORD, chain.toArray(Certificate[]::new));
        KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(store, EMPTY_PASSWORD);
        return extractKeyManager(factory.getKeyManagers());
    }

    private X509ExtendedKeyManager extractKeyManager(KeyManager[] managers) {
        for (KeyManager manager : managers) if (manager instanceof X509ExtendedKeyManager extended) return extended;
        throw new IllegalStateException("The active Java provider did not supply an X509ExtendedKeyManager");
    }

    private GeneratedCertificate createSelfSignedCertificate() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        Instant now = Instant.now();
        Date notBefore = Date.from(now.minusSeconds(300));
        Date notAfter = Date.from(now.plusSeconds(365L * 24 * 60 * 60));
        X500Name name = new X500Name("CN=Vertex Velocity Web Panel");
        BigInteger serial = new BigInteger(160, ThreadLocalRandom.current()).abs().add(BigInteger.ONE);
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(name, serial, notBefore, notAfter, name, pair.getPublic());
        List<GeneralName> names = new ArrayList<>();
        names.add(new GeneralName(GeneralName.dNSName, "localhost"));
        names.add(new GeneralName(GeneralName.iPAddress, "127.0.0.1"));
        try {
            InetAddress local = InetAddress.getLocalHost();
            String hostname = local.getHostName();
            String canonical = local.getCanonicalHostName();
            if (hostname != null && hostname.matches("[A-Za-z0-9.-]{1,253}"))
                names.add(new GeneralName(GeneralName.dNSName, hostname));
            if (canonical != null && canonical.matches("[A-Za-z0-9.-]{1,253}") && !canonical.equalsIgnoreCase(hostname))
                names.add(new GeneralName(GeneralName.dNSName, canonical));
            String address = local.getHostAddress();
            if (address != null && !address.contains("%")) names.add(new GeneralName(GeneralName.iPAddress, address));
        } catch (Exception ignored) { }
        builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(names.toArray(GeneralName[]::new)));
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").setProvider(BC_PROVIDER).build(pair.getPrivate());
        X509CertificateHolder holder = builder.build(signer);
        X509Certificate certificate = new JcaX509CertificateConverter().setProvider(BC_PROVIDER).getCertificate(holder);
        certificate.checkValidity();
        certificate.verify(pair.getPublic());
        return new GeneratedCertificate(pair, certificate);
    }

    private void writePkcs12(Path path, PrivateKey key, Certificate[] chain, char[] password) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, password);
        store.setKeyEntry("vertex-panel", key, password, chain);
        try (var out = Files.newOutputStream(path)) { store.store(out, password); }
    }

    private String fingerprint(Path... paths) {
        StringBuilder value = new StringBuilder();
        for (Path path : paths) {
            try {
                Path real = path.toRealPath();
                value.append(real).append('|').append(Files.size(real)).append('|')
                        .append(Files.getLastModifiedTime(real).toMillis()).append(';');
            } catch (Exception error) {
                value.append(path.toAbsolutePath().normalize()).append("|unreadable;");
            }
        }
        return value.toString();
    }

    private static String appendDiagnostic(String first, String second) {
        return first == null || first.isBlank() ? second : first + " " + second;
    }

    private static void restrictPermissions(Path file) {
        try {
            Files.setPosixFilePermissions(file, java.util.Set.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        } catch (Exception ignored) { }
    }

    private static final class ReloadableKeyManager extends X509ExtendedKeyManager {
        private volatile X509ExtendedKeyManager delegate;
        private ReloadableKeyManager(X509ExtendedKeyManager delegate) { this.delegate = delegate; }
        private void setDelegate(X509ExtendedKeyManager delegate) { this.delegate = delegate; }
        @Override public String[] getClientAliases(String keyType, java.security.Principal[] issuers) { return delegate.getClientAliases(keyType, issuers); }
        @Override public String chooseClientAlias(String[] keyType, java.security.Principal[] issuers, java.net.Socket socket) { return delegate.chooseClientAlias(keyType, issuers, socket); }
        @Override public String[] getServerAliases(String keyType, java.security.Principal[] issuers) { return delegate.getServerAliases(keyType, issuers); }
        @Override public String chooseServerAlias(String keyType, java.security.Principal[] issuers, java.net.Socket socket) { return delegate.chooseServerAlias(keyType, issuers, socket); }
        @Override public X509Certificate[] getCertificateChain(String alias) { return delegate.getCertificateChain(alias); }
        @Override public PrivateKey getPrivateKey(String alias) { return delegate.getPrivateKey(alias); }
        @Override public String chooseEngineClientAlias(String[] keyType, java.security.Principal[] issuers, javax.net.ssl.SSLEngine engine) { return delegate.chooseEngineClientAlias(keyType, issuers, engine); }
        @Override public String chooseEngineServerAlias(String keyType, java.security.Principal[] issuers, javax.net.ssl.SSLEngine engine) { return delegate.chooseEngineServerAlias(keyType, issuers, engine); }
    }
}
