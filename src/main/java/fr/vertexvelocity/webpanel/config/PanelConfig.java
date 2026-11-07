package fr.vertexvelocity.webpanel.config;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Map;

public final class PanelConfig {
    private final Path file;
    private Map<String, Object> root;
    private PanelConfig(Path file, Map<String, Object> root) { this.file = file; this.root = root; }

    public static PanelConfig load(Path file) throws IOException {
        if (Files.notExists(file)) {
            Files.createDirectories(file.getParent());
            try (InputStream defaults = PanelConfig.class.getResourceAsStream("/defaults/config.yml")) {
                if (defaults == null) throw new IOException("Missing bundled /defaults/config.yml resource");
                Files.copy(defaults, file);
                restrictFilePermissions(file);
            }
        }
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        Map<String, Object> map;
        try (Reader reader = Files.newBufferedReader(file)) {
            Object loaded = yaml.load(reader);
            map = loaded instanceof Map<?, ?> m ? stringMap(m) : new LinkedHashMap<>();
        }
        PanelConfig config = new PanelConfig(file, map);
        config.mergeDefaults();
        config.removeLegacyTransportConfig();
        config.save();
        restrictFilePermissions(file);
        return config;
    }

    private void mergeDefaults() throws IOException {
        Map<String,Object> defaults;
        try (InputStream in = PanelConfig.class.getResourceAsStream("/defaults/config.yml")) {
            if (in == null) throw new IOException("Missing bundled /defaults/config.yml resource");
            Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
            if (!(loaded instanceof Map<?,?> map)) throw new IOException("Bundled config.yml defaults must be a YAML mapping");
            defaults = stringMap(map);
        }
        mergeMissing(root, defaults);
    }

    @SuppressWarnings("unchecked")
    private static void mergeMissing(Map<String,Object> target, Map<String,Object> defaults) {
        for (Map.Entry<String,Object> entry : defaults.entrySet()) {
            Object current = target.get(entry.getKey());
            Object fallback = entry.getValue();
            if (current instanceof Map<?,?> currentMap && fallback instanceof Map<?,?> defaultMap) {
                mergeMissing((Map<String,Object>) currentMap, stringMap(defaultMap));
            } else if (current == null) {
                target.put(entry.getKey(), fallback instanceof Map<?,?> m ? new LinkedHashMap<>(stringMap(m)) : fallback);
            }
        }
    }
    private synchronized void removeLegacyTransportConfig() {
        Map<String,Object> web = section("web-panel");
        web.remove("https");
        web.remove("https-port");
        web.remove("reverse-proxy");
        web.put("mode", "HTTP");
        root.remove("acme");
    }
    public synchronized void save() throws IOException {
        DumperOptions options = new DumperOptions(); options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK); options.setPrettyFlow(true); options.setIndent(2);
        try (Writer writer = Files.newBufferedWriter(file)) { new Yaml(options).dump(root, writer); }
        restrictFilePermissions(file);
    }
    private static void restrictFilePermissions(Path target) {
        try { Files.setPosixFilePermissions(target, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)); }
        catch (UnsupportedOperationException | IOException ignored) { /* Non-POSIX filesystem: retain platform ACLs. */ }
    }
    public synchronized String webMode() { return "HTTP"; }
    public synchronized boolean webEnabled() { return bool(section("web-panel"), "enabled", true); }
    public synchronized String bindAddress() { return str(section("web-panel"), "bind-address", "127.0.0.1"); }
    public synchronized int httpPort() { return integer(section("web-panel"), "http-port", 25569); }
    public synchronized int sessionMinutes() { return integer(section("security"), "session-minutes", 60); }
    public synchronized int loginAttemptsPerMinute() { return integer(section("security"), "login-attempts-per-minute", 8); }
    public synchronized int sampleSeconds() { return integer(section("statistics"), "sample-seconds", 15); }
    public synchronized int retentionDays() { return integer(section("statistics"), "retention-days", 30); }
    public synchronized int backendTimeoutMs() { return integer(section("statistics"), "backend-connect-timeout-ms", 1500); }
    public synchronized int highLatencyMs() { return integer(section("monitoring"), "high-latency-ms", 1000); }
    public synchronized int alertCooldownSeconds() { return integer(section("monitoring"), "alert-cooldown-seconds", 300); }
    public synchronized int alertHistoryLimit() { return integer(section("monitoring"), "alert-history-limit", 1000); }
    public synchronized boolean discordEnabled() { return bool(section("discord"), "enabled", false); }
    public synchronized String discordWebhook() { return str(section("discord"), "webhook-url", ""); }
    public synchronized void updateEditable(String mode, Integer httpPort, Integer sampleSeconds) throws IOException {
        Map<String,Object> values = new LinkedHashMap<>();
        if (mode != null) values.put("mode", mode);
        if (httpPort != null) values.put("httpPort", httpPort);
        if (sampleSeconds != null) values.put("sampleSeconds", sampleSeconds);
        updateEditable(values);
    }

    public synchronized void updateEditable(Map<?, ?> values) throws IOException {
        Map<String,Object> web = section("web-panel");
        Map<String,Object> security = section("security");
        Map<String,Object> stats = section("statistics");
        Map<String,Object> monitoring = section("monitoring");
        web.put("mode", "HTTP");
        if (values.containsKey("bindAddress")) {
            String address = String.valueOf(values.get("bindAddress")).trim();
            if (address.isBlank() || address.length() > 255 || address.contains(" ")) throw new IllegalArgumentException("Bind address is invalid");
            web.put("bind-address", address);
        }
        putPort(values, "httpPort", web, "http-port");
        if (values.containsKey("webEnabled")) web.put("enabled", Boolean.parseBoolean(String.valueOf(values.get("webEnabled"))));
        putBounded(values, "sampleSeconds", stats, "sample-seconds", 5, 3600);
        putBounded(values, "retentionDays", stats, "retention-days", 1, 3650);
        putBounded(values, "loginAttemptsPerMinute", security, "login-attempts-per-minute", 3, 60);
        putBounded(values, "sessionMinutes", security, "session-minutes", 5, 1440);
        putBounded(values, "highLatencyMs", monitoring, "high-latency-ms", 100, 60000);
        putBounded(values, "alertCooldownSeconds", monitoring, "alert-cooldown-seconds", 30, 86400);
        putBounded(values, "alertHistoryLimit", monitoring, "alert-history-limit", 100, 10000);
        web.remove("https");
        root.remove("acme");
        web.put("mode", "HTTP");
        save();
    }

    private static void putPort(Map<?, ?> values, String inputKey, Map<String,Object> target, String configKey) {
        if (!values.containsKey(inputKey)) return;
        int port;
        try { port = Integer.parseInt(String.valueOf(values.get(inputKey))); }
        catch (RuntimeException ex) { throw new IllegalArgumentException(inputKey + " must be a number"); }
        if (port < 1024 || port > 65535) throw new IllegalArgumentException(inputKey + " must be 1024-65535");
        target.put(configKey, port);
    }

    private static void putBounded(Map<?, ?> values, String inputKey, Map<String,Object> target, String configKey, int min, int max) {
        if (!values.containsKey(inputKey)) return;
        int value;
        try { value = Integer.parseInt(String.valueOf(values.get(inputKey))); }
        catch (RuntimeException ex) { throw new IllegalArgumentException(inputKey + " must be a number"); }
        if (value < min || value > max) throw new IllegalArgumentException(inputKey + " must be " + min + "-" + max);
        target.put(configKey, value);
    }
    public synchronized Map<String,Object> publicView() {
        Map<String,Object> result = new LinkedHashMap<>();
        Map<String,Object> web = new LinkedHashMap<>(section("web-panel"));
        web.remove("https");
        web.put("mode", "HTTP");
        result.put("web-panel", web);
        result.put("statistics", new LinkedHashMap<>(section("statistics"))); result.put("security", new LinkedHashMap<>(section("security")));
        result.put("monitoring", new LinkedHashMap<>(section("monitoring")));
        result.put("discord", Map.of("enabled", discordEnabled(), "webhook-configured", !discordWebhook().isBlank())); return result;
    }
    private Map<String,Object> section(String key) { Object value = root.get(key); if (!(value instanceof Map<?,?> m)) { Map<String,Object> n = new LinkedHashMap<>(); root.put(key,n); return n; } @SuppressWarnings("unchecked") Map<String,Object> cast=(Map<String,Object>)m; return cast; }
    private static Map<String,Object> child(Map<String,Object> map, String key) { Object value=map.get(key); if (value instanceof Map<?,?> m) { @SuppressWarnings("unchecked") Map<String,Object> cast=(Map<String,Object>)m; return cast; } Map<String,Object> n=new LinkedHashMap<>(); map.put(key,n); return n; }
    private static Map<String,Object> stringMap(Map<?,?> m) { Map<String,Object> out=new LinkedHashMap<>(); m.forEach((k,v)->out.put(String.valueOf(k),v)); return out; }
    private static String str(Map<String,Object> m,String k,String d) { Object v=m.get(k); return v == null ? d : String.valueOf(v); }
    private static int integer(Map<String,Object> m,String k,int d) { Object v=m.get(k); try { return v == null ? d : Integer.parseInt(String.valueOf(v)); } catch (RuntimeException e) { return d; } }
    private static boolean bool(Map<String,Object> m,String k,boolean d) { Object v=m.get(k); return v == null ? d : Boolean.parseBoolean(String.valueOf(v)); }
}
