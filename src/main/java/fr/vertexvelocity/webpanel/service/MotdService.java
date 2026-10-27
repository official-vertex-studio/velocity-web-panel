package fr.vertexvelocity.webpanel.service;

import com.velocitypowered.api.proxy.server.ServerPing;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Loads the configurable network MOTD from the plugin data directory. */
public final class MotdService {
    private final Path file;
    private Map<String, Object> root = new LinkedHashMap<>();

    public MotdService(Path dataDirectory) throws IOException {
        Files.createDirectories(dataDirectory);
        file = dataDirectory.resolve("motd.yml");
        if (Files.notExists(file)) {
            try (InputStream in = MotdService.class.getResourceAsStream("/defaults/motd.yml")) {
                if (in != null) Files.copy(in, file);
                else Files.writeString(file, "enabled: true\nnormal:\n  line-1: '&b&lVertex Network'\n  line-2: '&7A premium Minecraft network'\nmaintenance:\n  line-1: '&c&lMAINTENANCE'\n  line-2: '&7The network is temporarily unavailable'\nplayer-sample:\n  enabled: true\n");
            }
        }
        reload();
    }

    public synchronized void reload() throws IOException {
        try (Reader reader = Files.newBufferedReader(file)) {
            Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
            if (!(loaded instanceof Map<?, ?> map)) throw new IOException("motd.yml must contain a YAML mapping");
            Map<String,Object> candidate = stringMap(map);
            for (String section : List.of("normal", "maintenance")) {
                Object profile = candidate.get(section);
                if (profile != null && !(profile instanceof Map<?,?>)) throw new IOException("motd.yml section '" + section + "' must be a mapping");
            }
            root = candidate;
        } catch (RuntimeException e) { throw new IOException("Invalid motd.yml", e); }
    }

    public synchronized ServerPing apply(ServerPing current, boolean maintenance) {
        if (!bool(root.get("enabled"), true)) return current;
        Map<String, Object> section = section(maintenance ? "maintenance" : "normal");
        String first = value(section.get("line-1"), maintenance ? "&c&lMAINTENANCE" : "&b&lVertex Network");
        String second = value(section.get("line-2"), maintenance ? "&7The network is temporarily unavailable" : "&7A premium Minecraft network");
        Component description = LegacyComponentSerializer.legacyAmpersand().deserialize(first + "\n" + second);
        ServerPing.Players players = current.getPlayers().orElse(null);
        if (bool(section("player-sample").get("enabled"), true) && players != null) {
            List<ServerPing.SamplePlayer> sample = current.getPlayers().map(ServerPing.Players::getSample).orElse(List.of());
            players = new ServerPing.Players(players.getMax(), players.getOnline(), sample);
        } else if (players != null) {
            players = new ServerPing.Players(players.getMax(), players.getOnline(), List.of());
        }
        ServerPing.Builder builder = ServerPing.builder()
                .version(current.getVersion())
                .description(description);
        if (players != null) {
            builder.onlinePlayers(players.getOnline())
                    .maximumPlayers(players.getMax());
            if (bool(section("player-sample").get("enabled"), true)) {
                builder.samplePlayers(players.getSample());
            }
        }
        current.getFavicon().ifPresent(builder::favicon);
        return builder.build();
    }

    public synchronized Map<String,Object> view() { return Collections.unmodifiableMap(new LinkedHashMap<>(root)); }

    public synchronized void update(Map<?, ?> input) throws IOException {
        // Validate and persist a candidate snapshot before swapping live state.
        // A bad request or disk error must never leave the in-memory MOTD half-updated.
        Map<String,Object> candidate = deepCopy(root);
        if (input.containsKey("enabled")) candidate.put("enabled", strictBool(input.get("enabled"), "enabled"));
        for (String sectionName : List.of("normal", "maintenance")) {
            Map<String,Object> target = candidateSection(candidate, sectionName);
            Object raw = input.get(sectionName);
            if (raw != null && !(raw instanceof Map<?,?>)) {
                throw new IllegalArgumentException(sectionName + " must be an object");
            }
            if (raw instanceof Map<?,?> values) {
                for (String key : List.of("line-1", "line-2")) {
                    if (!values.containsKey(key)) continue;
                    Object rawValue = values.get(key);
                    if (!(rawValue instanceof String)) throw new IllegalArgumentException(sectionName + "." + key + " must be a string");
                    String value = ((String) rawValue).trim();
                    if (value.isEmpty() || value.length() > 256 || value.contains("\n") || value.contains("\r")) {
                        throw new IllegalArgumentException(sectionName + "." + key + " must contain 1-256 single-line characters");
                    }
                    target.put(key, value);
                }
                candidate.put(sectionName, target);
            }
        }
        Object sample = input.get("playerSampleEnabled");
        if (sample == null && input.get("player-sample") instanceof Map<?, ?> sampleMap) sample = sampleMap.get("enabled");
        if (sample != null) candidate.put("player-sample", new LinkedHashMap<>(Map.of("enabled", strictBool(sample, "playerSampleEnabled"))));

        org.yaml.snakeyaml.DumperOptions options = new org.yaml.snakeyaml.DumperOptions();
        options.setDefaultFlowStyle(org.yaml.snakeyaml.DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (java.io.Writer writer = Files.newBufferedWriter(temp)) { new Yaml(options).dump(candidate, writer); }
        try {
            Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException error) {
            Files.deleteIfExists(temp);
            throw error;
        }
        root = candidate;
    }

    private static Map<String,Object> candidateSection(Map<String,Object> source, String key) {
        Object value = source.get(key);
        return value instanceof Map<?,?> map ? stringMap(map) : new LinkedHashMap<>();
    }

    private static Map<String,Object> deepCopy(Map<String,Object> source) {
        Map<String,Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String,Object> entry : source.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map<?,?> map) copy.put(entry.getKey(), deepCopy(stringMap(map)));
            else if (value instanceof List<?> list) copy.put(entry.getKey(), new ArrayList<>(list));
            else copy.put(entry.getKey(), value);
        }
        return copy;
    }

    private static boolean strictBool(Object value, String field) {
        if (value instanceof Boolean b) return b;
        if (value != null && "true".equalsIgnoreCase(String.valueOf(value))) return true;
        if (value != null && "false".equalsIgnoreCase(String.valueOf(value))) return false;
        throw new IllegalArgumentException(field + " must be true or false");
    }

    public synchronized void reloadFromDisk() throws IOException { reload(); }

    private Map<String, Object> section(String key) { Object v = root.get(key); return v instanceof Map<?, ?> m ? stringMap(m) : Map.of(); }
    private static Map<String,Object> stringMap(Map<?,?> input) { Map<String,Object> out = new LinkedHashMap<>(); input.forEach((k,v)->out.put(String.valueOf(k),v)); return out; }
    private static boolean bool(Object v, boolean fallback) { return v == null ? fallback : Boolean.parseBoolean(String.valueOf(v)); }
    private static String value(Object v, String fallback) { String s = v == null ? fallback : String.valueOf(v); return s.length() > 256 ? s.substring(0,256) : s; }
}
