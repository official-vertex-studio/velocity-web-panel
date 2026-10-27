package fr.vertexvelocity.webpanel.service;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.*;

/** Automatically discovers existing TLS assets without requiring paths to be entered in the panel. */
public final class TlsCertificateDetector {
    public record Material(Kind kind, Path certificate, Path privateKey, Path keyStore, String keyStoreType,
                           String keyStorePassword, String subject, Instant expiresAt) {
        public enum Kind { PEM, KEYSTORE }
        public Map<String,Object> view() {
            Map<String,Object> result = new LinkedHashMap<>();
            result.put("detected", true); result.put("kind", kind.name()); result.put("subject", subject);
            result.put("expiresAt", expiresAt.toString());
            result.put("certificate", certificate == null ? null : certificate.getFileName().toString());
            result.put("privateKeyDetected", kind == Kind.PEM);
            result.put("keyStore", keyStore == null ? null : keyStore.getFileName().toString());
            return result;
        }
    }

    private final Path dataDirectory;
    private final Path settingsFile;
    public TlsCertificateDetector(Path dataDirectory) {
        this.dataDirectory = dataDirectory.toAbsolutePath().normalize();
        this.settingsFile = this.dataDirectory.resolve("tls.yml");
        try {
            Files.createDirectories(this.dataDirectory);
            if (Files.notExists(settingsFile)) {
                try (InputStream defaults = TlsCertificateDetector.class.getResourceAsStream("/defaults/tls.yml")) {
                    if (defaults != null) Files.copy(defaults, settingsFile);
                }
            }
        } catch (Exception ignored) { /* Built-in safe search roots remain available if config cannot be copied. */ }
    }

    public Optional<Material> detectPemPair(Path certificate, Path privateKey) {
        if (certificate == null || privateKey == null) return Optional.empty();
        return pem(certificate, privateKey);
    }

    public Optional<Material> detect(String configuredKeystore, String configuredType, String configuredPassword,
                                     String configuredCertificate, String configuredPrivateKey) {
        try {
            List<Path> roots = searchRoots();
            if (configuredCertificate != null && !configuredCertificate.isBlank() && configuredPrivateKey != null && !configuredPrivateKey.isBlank()) {
                Optional<Material> configured = pem(Path.of(configuredCertificate), Path.of(configuredPrivateKey));
                if (configured.isPresent()) return configured;
            }
            if (configuredKeystore != null && !configuredKeystore.isBlank()) {
                Optional<Material> configured = keyStore(Path.of(configuredKeystore), configuredType, configuredPassword);
                if (configured.isPresent()) return configured;
            }
            for (Path root : roots) {
                if (!Files.isDirectory(root)) continue;
                for (String[] pair : List.of(
                        new String[]{"fullchain.pem","privkey.pem"}, new String[]{"cert.pem","privkey.pem"},
                        new String[]{"cert.pem","key.pem"}, new String[]{"fullchain.pem","key.pem"},
                        new String[]{"tls.crt","tls.key"}, new String[]{"server.crt","server.key"},
                        new String[]{"certificate.crt","private.key"}, new String[]{"certificate.pem","private-key.pem"},
                        new String[]{"cert.crt","key.pem"}, new String[]{"chain.pem","privkey.pem"})) {
                    Optional<Material> found = pem(root.resolve(pair[0]), root.resolve(pair[1]));
                    if (found.isPresent()) return found;
                }
                for (String[] pair : List.of(new String[]{"keystore.p12","PKCS12"}, new String[]{"keystore.pfx","PKCS12"}, new String[]{"keystore.jks","JKS"})) {
                    Optional<Material> found = keyStore(root.resolve(pair[0]), pair[1], configuredPassword);
                    if (found.isPresent()) return found;
                }
                // Let's Encrypt uses one subdirectory per domain. Limit recursion to one level.
                try (var children = Files.list(root)) {
                    for (Path child : children.filter(Files::isDirectory).limit(256).toList()) {
                        Optional<Material> found = pem(child.resolve("fullchain.pem"), child.resolve("privkey.pem"));
                        if (found.isPresent()) return found;
                    }
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
        return Optional.empty();
    }

    private Optional<Material> pem(Path cert, Path key) {
        try {
            if (!Files.isRegularFile(cert) || !Files.isReadable(cert) || !Files.isRegularFile(key) || !Files.isReadable(key)) return Optional.empty();
            try (InputStream in = Files.newInputStream(cert)) {
                X509Certificate x509 = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
                x509.checkValidity();
                PrivateKey privateKey = readPkcs8PrivateKey(key);
                if (privateKey == null || !keyMatchesCertificate(privateKey, x509)) return Optional.empty();
                return Optional.of(new Material(Material.Kind.PEM, cert.toAbsolutePath().normalize(), key.toAbsolutePath().normalize(), null, null, null,
                        x509.getSubjectX500Principal().getName(), x509.getNotAfter().toInstant()));
            }
        } catch (Exception ignored) { return Optional.empty(); }
    }


    /** Accepts unencrypted PKCS#8 PEM keys and verifies that the key belongs to the certificate. */
    private PrivateKey readPkcs8PrivateKey(Path path) {
        try {
            String pem = Files.readString(path).trim();
            if (!pem.contains("-----BEGIN PRIVATE KEY-----") || !pem.contains("-----END PRIVATE KEY-----")) return null;
            String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
            byte[] encoded = Base64.getDecoder().decode(base64);
            for (String algorithm : List.of("RSA", "EC", "Ed25519", "DSA")) {
                try { return KeyFactory.getInstance(algorithm).generatePrivate(new PKCS8EncodedKeySpec(encoded)); }
                catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
        return null;
    }

    private boolean keyMatchesCertificate(PrivateKey privateKey, X509Certificate certificate) {
        try {
            String algorithm = switch (privateKey.getAlgorithm().toUpperCase(Locale.ROOT)) {
                case "RSA" -> "SHA256withRSA";
                case "EC", "ECDSA" -> "SHA256withECDSA";
                case "ED25519", "EDDSA" -> "Ed25519";
                case "DSA" -> "SHA256withDSA";
                default -> null;
            };
            if (algorithm == null) return false;
            byte[] challenge = "Vertex Velocity TLS key-pair validation".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Signature signer = Signature.getInstance(algorithm);
            signer.initSign(privateKey); signer.update(challenge); byte[] signature = signer.sign();
            Signature verifier = Signature.getInstance(algorithm);
            verifier.initVerify(certificate.getPublicKey()); verifier.update(challenge);
            return verifier.verify(signature);
        } catch (Exception ignored) { return false; }
    }

    private Optional<Material> keyStore(Path path, String type, String password) {
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) return Optional.empty();
        String storeType = type == null || type.isBlank() ? (path.toString().endsWith(".jks") ? "JKS" : "PKCS12") : type;
        List<char[]> passwords = new ArrayList<>();
        passwords.add((password == null ? "" : password).toCharArray());
        passwords.add(new char[0]);
        passwords.add(null);
        for (char[] secret : passwords) {
            try {
                KeyStore store = KeyStore.getInstance(storeType);
                try (InputStream in = Files.newInputStream(path)) { store.load(in, secret); }
                Enumeration<String> aliases = store.aliases();
                while (aliases.hasMoreElements()) {
                    String alias = aliases.nextElement();
                    if (store.getCertificate(alias) instanceof X509Certificate cert && store.isKeyEntry(alias)) {
                        cert.checkValidity();
                        return Optional.of(new Material(Material.Kind.KEYSTORE, null, null, path.toAbsolutePath().normalize(), storeType,
                                secret == null ? "" : new String(secret), cert.getSubjectX500Principal().getName(), cert.getNotAfter().toInstant()));
                    }
                }
            } catch (Exception ignored) { }
        }
        return Optional.empty();
    }

    private List<Path> searchRoots() {
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        roots.add(dataDirectory.resolve("tls")); roots.add(dataDirectory); roots.add(Path.of("tls").toAbsolutePath().normalize());
        roots.add(Path.of("/etc/letsencrypt/live")); roots.add(Path.of("/etc/ssl/private")); roots.add(Path.of("/etc/ssl/certs"));
        roots.add(Path.of("/var/lib/letsencrypt"));
        if (Files.isRegularFile(settingsFile)) {
            try (InputStream in = Files.newInputStream(settingsFile)) {
                Object raw = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
                if (raw instanceof Map<?,?> map && map.get("search-paths") instanceof Iterable<?> paths) {
                    for (Object item : paths) {
                        String value = String.valueOf(item).replace("${PLUGIN_DATA}", dataDirectory.toString()).trim();
                        if (!value.isBlank()) roots.add(Path.of(value).toAbsolutePath().normalize());
                    }
                }
            } catch (Exception ignored) { }
        }
        return List.copyOf(roots);
    }
}
