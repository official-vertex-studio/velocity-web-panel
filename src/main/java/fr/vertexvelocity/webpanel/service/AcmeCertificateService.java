package fr.vertexvelocity.webpanel.service;

import fr.vertexvelocity.webpanel.config.PanelConfig;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Certbot-backed ACME HTTP-01 issuance and renewal. Certbot must be installed in this container. */
public final class AcmeCertificateService {
    private static final Pattern DOMAIN = Pattern.compile("(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{1,200}");
    private final PanelConfig config;
    private final Logger logger;
    private final Path challengeRoot;
    private final Path stateRoot;
    private final Path configRoot;
    private final Path workRoot;
    private final Path logsRoot;

    public AcmeCertificateService(PanelConfig config, Logger logger, Path dataDirectory) throws IOException {
        this.config = config;
        this.logger = logger;
        this.stateRoot = dataDirectory.resolve("acme").toAbsolutePath().normalize();
        this.configRoot = stateRoot.resolve("config");
        this.workRoot = stateRoot.resolve("work");
        this.logsRoot = stateRoot.resolve("logs");
        this.challengeRoot = stateRoot.resolve("webroot").normalize();
        Files.createDirectories(challengeRoot.resolve(".well-known/acme-challenge"));
        Files.createDirectories(configRoot);
        Files.createDirectories(workRoot);
        Files.createDirectories(logsRoot);
        restrictPermissions(stateRoot); restrictPermissions(configRoot); restrictPermissions(workRoot); restrictPermissions(logsRoot); restrictPermissions(challengeRoot);
    }

    private void restrictPermissions(Path path) {
        try { Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")); }
        catch (UnsupportedOperationException | IOException ignored) { /* Windows/non-POSIX filesystem: rely on container filesystem ACLs. */ }
    }

    public Path challengeRoot() { return challengeRoot; }

    public void validateConfiguration() {
        if (!DOMAIN.matcher(config.acmeDomain()).matches()) throw new IllegalStateException("ACME enabled but acme.domain is not a valid public DNS hostname");
        if (config.acmeEmail().isBlank() || config.acmeEmail().length() > 254 || !config.acmeEmail().matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) throw new IllegalStateException("ACME enabled but acme.email is not a valid email address");
        if (config.certbotCommand().isBlank()) throw new IllegalStateException("acme.certbot-command must not be blank");
    }

    public Optional<byte[]> challenge(String requestPath) {
        String prefix = "/.well-known/acme-challenge/";
        if (requestPath == null || !requestPath.startsWith(prefix)) return Optional.empty();
        String token = requestPath.substring(prefix.length());
        if (!TOKEN.matcher(token).matches()) return Optional.of(new byte[0]);
        Path file = challengeRoot.resolve(".well-known/acme-challenge").resolve(token).normalize();
        if (!file.startsWith(challengeRoot) || !Files.isRegularFile(file)) return Optional.of(new byte[0]);
        try { return Optional.of(Files.readAllBytes(file)); }
        catch (IOException ignored) { return Optional.of(new byte[0]); }
    }

    public boolean obtainInitialCertificate() {
        validateConfiguration();
        List<String> command = new ArrayList<>(List.of(config.certbotCommand(), "certonly", "--webroot", "--webroot-path", challengeRoot.toString(),
                "--config-dir", configRoot.toString(), "--work-dir", workRoot.toString(), "--logs-dir", logsRoot.toString(),
                "--non-interactive", "--agree-tos", "--email", config.acmeEmail(), "--cert-name", config.acmeDomain(),
                "-d", config.acmeDomain(), "--keep-until-expiring"));
        if (config.acmeStaging()) { command.add("--server"); command.add("https://acme-staging-v02.api.letsencrypt.org/directory"); }
        return run(command, Duration.ofMinutes(10), "initial certificate issuance");
    }

    public boolean renew() {
        validateConfiguration();
        List<String> command = new ArrayList<>(List.of(config.certbotCommand(), "renew", "--cert-name", config.acmeDomain(),
                "--config-dir", configRoot.toString(), "--work-dir", workRoot.toString(), "--logs-dir", logsRoot.toString(),
                "--webroot", "--webroot-path", challengeRoot.toString(), "--quiet"));
        if (config.acmeStaging()) { command.add("--server"); command.add("https://acme-staging-v02.api.letsencrypt.org/directory"); }
        return run(command, Duration.ofMinutes(10), "certificate renewal check");
    }

    public Path certificatePath() { return configRoot.resolve("live").resolve(config.acmeDomain()).resolve("fullchain.pem"); }
    public Path privateKeyPath() { return configRoot.resolve("live").resolve(config.acmeDomain()).resolve("privkey.pem"); }

    private boolean run(List<String> command, Duration timeout, String operation) {
        Process process = null;
        try {
            logger.info("Starting ACME {} for {} using Certbot", operation, config.acmeDomain());
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            Process p = process;
            StringBuilder output = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (var in = p.getInputStream()) {
                    byte[] buffer = new byte[2048]; int count;
                    while ((count = in.read(buffer)) >= 0) {
                        synchronized (output) {
                            if (output.length() < 12000) output.append(new String(buffer, 0, count, StandardCharsets.UTF_8));
                        }
                    }
                } catch (IOException ignored) { }
            }, "vertex-acme-output");
            reader.setDaemon(true); reader.start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                logger.error("Certbot {} timed out after {} seconds", operation, timeout.toSeconds());
                return false;
            }
            reader.join(2000);
            int exit = process.exitValue();
            if (exit == 0) {
                logger.info("Certbot {} completed successfully for {}", operation, config.acmeDomain());
                return true;
            }
            String detail;
            synchronized (output) { detail = output.toString().trim(); }
            logger.error("Certbot {} failed (exit {}). Detailed output is suppressed to avoid leaking runtime paths or secrets. Check the Certbot log files in the plugin acme/logs directory.", operation, exit);
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (process != null) process.destroyForcibly();
            logger.warn("Certbot {} was interrupted", operation);
            return false;
        } catch (Exception error) {
            logger.error("Could not execute Certbot for ACME {}. Ensure the executable exists and the container has permission to write certificates.", operation, error);
            return false;
        }
    }
}
