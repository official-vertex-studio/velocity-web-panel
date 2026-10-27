package fr.vertexvelocity.webpanel.service;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Owns persistent maintenance state. UUIDs are the authoritative whitelist identifiers.
 * The class deliberately does not expose secrets or arbitrary filesystem access.
 */
public final class MaintenanceService {
    private final Path file;
    private boolean enabled;
    private boolean whitelistEnabled = true;
    private boolean allowExistingPlayers = true;
    private boolean kickExistingPlayers;
    private String message = "The network is under maintenance.";
    private String kickMessage = "The network is currently under maintenance.";
    private final Set<String> whitelist = new LinkedHashSet<>();

    public MaintenanceService(Path dataDirectory) throws IOException {
        this.file = dataDirectory.resolve("maintenance.yml");
        load();
    }

    public synchronized void load() throws IOException {
        if (Files.notExists(file)) {
            Files.createDirectories(file.getParent());
            try (InputStream defaults = MaintenanceService.class.getResourceAsStream("/defaults/maintenance.yml")) {
                if (defaults == null) throw new IOException("Missing bundled /defaults/maintenance.yml resource");
                Files.copy(defaults, file);
            }
        }
        Object raw;
        try (Reader reader = Files.newBufferedReader(file)) { raw = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader); }
        if (!(raw instanceof Map<?, ?> map)) throw new IOException("maintenance.yml must contain a YAML object");
        enabled = bool(map.get("enabled"), false);
        whitelistEnabled = bool(map.get("whitelist-enabled"), true);
        allowExistingPlayers = bool(map.get("allow-existing-players"), true);
        kickExistingPlayers = bool(map.get("kick-existing-players"), false);
        message = bounded(map.get("message"), "The network is under maintenance.", 200);
        kickMessage = bounded(map.get("kick-message"), "The network is currently under maintenance.", 200);
        whitelist.clear();
        Object rawList = map.get("whitelist");
        if (rawList instanceof Iterable<?> values) {
            for (Object value : values) {
                String id = String.valueOf(value).trim();
                try { whitelist.add(UUID.fromString(id).toString()); } catch (IllegalArgumentException ignored) { }
            }
        }
    }

    public synchronized void update(Map<?, ?> input) throws IOException {
        boolean nextEnabled = input.containsKey("enabled") ? bool(input.get("enabled"), enabled) : enabled;
        boolean nextWhitelistEnabled = input.containsKey("whitelistEnabled") ? bool(input.get("whitelistEnabled"), whitelistEnabled) : whitelistEnabled;
        boolean nextAllowExisting = input.containsKey("allowExistingPlayers") ? bool(input.get("allowExistingPlayers"), allowExistingPlayers) : allowExistingPlayers;
        boolean nextKickExisting = input.containsKey("kickExistingPlayers") ? bool(input.get("kickExistingPlayers"), kickExistingPlayers) : kickExistingPlayers;
        String nextMessage = input.containsKey("message") ? bounded(input.get("message"), message, 200) : message;
        String nextKickMessage = input.containsKey("kickMessage") ? bounded(input.get("kickMessage"), kickMessage, 200) : kickMessage;
        Set<String> nextWhitelist = new LinkedHashSet<>(whitelist);
        if (input.containsKey("whitelist")) {
            Object raw = input.get("whitelist");
            if (!(raw instanceof Iterable<?> values)) throw new IllegalArgumentException("whitelist must be an array of UUID strings");
            nextWhitelist.clear();
            for (Object value : values) {
                String id = String.valueOf(value).trim();
                if (id.isBlank()) continue;
                nextWhitelist.add(canonicalUuid(id));
                if (nextWhitelist.size() > 10000) throw new IllegalArgumentException("Maintenance whitelist cannot exceed 10000 UUIDs");
            }
        }
        boolean oldEnabled=enabled, oldWhitelistEnabled=whitelistEnabled, oldAllowExisting=allowExistingPlayers, oldKickExisting=kickExistingPlayers;
        String oldMessage=message, oldKickMessage=kickMessage;
        Set<String> oldWhitelist=new LinkedHashSet<>(whitelist);
        enabled=nextEnabled; whitelistEnabled=nextWhitelistEnabled; allowExistingPlayers=nextAllowExisting;
        kickExistingPlayers=nextKickExisting; message=nextMessage; kickMessage=nextKickMessage;
        whitelist.clear(); whitelist.addAll(nextWhitelist);
        try { save(); }
        catch (IOException error) {
            enabled=oldEnabled; whitelistEnabled=oldWhitelistEnabled; allowExistingPlayers=oldAllowExisting;
            kickExistingPlayers=oldKickExisting; message=oldMessage; kickMessage=oldKickMessage;
            whitelist.clear(); whitelist.addAll(oldWhitelist);
            throw error;
        }
    }

    public synchronized boolean add(String uuid) throws IOException {
        String canonical = canonicalUuid(uuid);
        if (whitelist.contains(canonical)) return false;
        whitelist.add(canonical);
        try { save(); }
        catch (IOException error) { whitelist.remove(canonical); throw error; }
        return true;
    }
    public synchronized boolean remove(String uuid) throws IOException {
        String canonical = canonicalUuid(uuid);
        if (!whitelist.contains(canonical)) return false;
        whitelist.remove(canonical);
        try { save(); }
        catch (IOException error) { whitelist.add(canonical); throw error; }
        return true;
    }
    /**
     * During maintenance, only UUIDs explicitly on the whitelist are allowed.
     * Disabling the whitelist does not disable maintenance; it means nobody is
     * admitted as a new connection. Existing sessions are handled separately.
     */
    public synchronized boolean isAllowed(String uuid) {
        if (!enabled) return true;
        return whitelistEnabled && isWhitelisted(uuid);
    }
    public synchronized boolean isWhitelisted(String uuid) {
        try { return whitelist.contains(canonicalUuid(uuid)); } catch (IllegalArgumentException e) { return false; }
    }
    public synchronized Map<String, Object> view() {
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled);
        out.put("message", message);
        out.put("kickMessage", kickMessage);
        out.put("whitelistEnabled", whitelistEnabled);
        out.put("allowExistingPlayers", allowExistingPlayers);
        out.put("kickExistingPlayers", kickExistingPlayers);
        out.put("whitelist", List.copyOf(whitelist));
        out.put("whitelistSize", whitelist.size());
        return out;
    }
    public synchronized boolean enabled() { return enabled; }
    public synchronized String message() { return message; }
    public synchronized String kickMessage() { return kickMessage; }
    public synchronized boolean whitelistEnabled() { return whitelistEnabled; }
    public synchronized boolean allowExistingPlayers() { return allowExistingPlayers; }
    public synchronized boolean kickExistingPlayers() { return kickExistingPlayers; }

    private void save() throws IOException {
        Map<String,Object> data = new LinkedHashMap<>();
        data.put("enabled", enabled); data.put("message", message); data.put("kick-message", kickMessage);
        data.put("whitelist-enabled", whitelistEnabled); data.put("allow-existing-players", allowExistingPlayers); data.put("kick-existing-players", kickExistingPlayers);
        data.put("whitelist", new ArrayList<>(whitelist));
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK); options.setPrettyFlow(true); options.setIndent(2);
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temp)) { new Yaml(options).dump(data, writer); }
        try {
            Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException error) {
            Files.deleteIfExists(temp);
            throw error;
        }
    }
    private static String canonicalUuid(String value) {
        if (value == null) throw new IllegalArgumentException("A valid player UUID is required");
        try { return UUID.fromString(value.trim()).toString(); }
        catch (RuntimeException e) { throw new IllegalArgumentException("Whitelist entries must be valid UUIDs"); }
    }
    private static boolean bool(Object value, boolean fallback) {
        if (value == null) return fallback;
        if (value instanceof Boolean b) return b;
        if ("true".equalsIgnoreCase(String.valueOf(value))) return true;
        if ("false".equalsIgnoreCase(String.valueOf(value))) return false;
        throw new IllegalArgumentException("Boolean configuration values must be true or false");
    }
    private static String bounded(Object value, String fallback, int max) {
        String result = value == null ? fallback : String.valueOf(value).trim();
        if (result.isBlank() || result.length() > max) throw new IllegalArgumentException("Maintenance messages must contain 1-" + max + " characters");
        return result;
    }
}
