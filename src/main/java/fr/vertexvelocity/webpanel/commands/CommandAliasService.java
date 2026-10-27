package fr.vertexvelocity.webpanel.commands;

import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Loads administrator-defined command aliases from aliases.yml and registers them with Velocity. */
public final class CommandAliasService {
    private static final Pattern VALID_NAME = Pattern.compile("[a-zA-Z0-9_-]{1,32}");
    private final ProxyServer proxy;
    private final Logger logger;
    private final Object plugin;
    private final Path configPath;
    private final Map<String, CommandMeta> registeredMetas = new LinkedHashMap<>();
    private CommandMeta reloadMeta;

    public CommandAliasService(ProxyServer proxy, Logger logger, Object plugin, Path dataDirectory) throws IOException {
        this.proxy = proxy;
        this.logger = logger;
        this.plugin = plugin;
        Files.createDirectories(dataDirectory);
        this.configPath = dataDirectory.resolve("aliases.yml");
        ensureDefaultConfig();
        registerReloadCommand();
    }

    private void registerReloadCommand() {
        try {
            reloadMeta = proxy.getCommandManager().metaBuilder("valiasreload").plugin(plugin).build();
            proxy.getCommandManager().register(reloadMeta, new SimpleCommand() {
                @Override public void execute(Invocation invocation) {
                    if (!invocation.source().hasPermission("vertexvelocitywebpanel.aliases.reload")) {
                        invocation.source().sendMessage(net.kyori.adventure.text.Component.text("Missing permission: vertexvelocitywebpanel.aliases.reload"));
                        return;
                    }
                    try {
                        int count = reload();
                        invocation.source().sendMessage(net.kyori.adventure.text.Component.text("Reloaded " + count + " Vertex command alias(es)."));
                    } catch (Exception e) {
                        logger.warn("Could not reload command aliases", e);
                        invocation.source().sendMessage(net.kyori.adventure.text.Component.text("Alias reload failed. Check the Velocity console."));
                    }
                }
            });
        } catch (RuntimeException e) {
            logger.warn("Could not register /valiasreload: {}", e.getMessage());
        }
    }

    public synchronized int reload() throws IOException {
        // Parse and validate the complete file BEFORE unregistering anything. A malformed
        // aliases.yml must never wipe out the currently working command aliases.
        validateConfigFile();
        for (CommandMeta meta : new ArrayList<>(registeredMetas.values())) {
            try { proxy.getCommandManager().unregister(meta); } catch (RuntimeException e) { logger.warn("Could not unregister a previously registered command alias: {}", e.getMessage()); }
        }
        registeredMetas.clear();
        return loadAndRegister();
    }


    private void validateConfigFile() throws IOException {
        final Object loaded;
        try (InputStream input = Files.newInputStream(configPath)) {
            loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(input);
        } catch (RuntimeException e) {
            throw new IOException("aliases.yml is invalid YAML; existing aliases were kept", e);
        }
        if (!(loaded instanceof Map<?, ?> root)) throw new IOException("aliases.yml must contain a YAML mapping; existing aliases were kept");
        Object raw = root.get("aliases");
        if (!(raw instanceof Map<?, ?> aliases)) throw new IOException("aliases.yml must contain an 'aliases' mapping; existing aliases were kept");
        Set<String> names = new java.util.HashSet<>();
        for (Map.Entry<?, ?> entry : aliases.entrySet()) {
            String name = String.valueOf(entry.getKey()).trim().toLowerCase(Locale.ROOT);
            if (!VALID_NAME.matcher(name).matches() || Set.of("valiasreload", "vstats", "vservers", "vplayers", "vserver", "vping", "vhelp").contains(name))
                throw new IOException("Invalid or reserved alias name '" + name + "'; existing aliases were kept");
            if (!names.add(name)) throw new IOException("Duplicate alias '" + name + "'; existing aliases were kept");
            if (!(entry.getValue() instanceof Map<?, ?> definition)) throw new IOException("Alias '" + name + "' must be a mapping; existing aliases were kept");
            String command = stringValue(definition.get("command"));
            String permission = stringValue(definition.get("permission"));
            if (command.isBlank() || command.startsWith("/") || command.contains("\n") || command.contains("\r") || command.length() > 512)
                throw new IOException("Invalid command for alias '" + name + "'; existing aliases were kept");
            if (permission.length() > 128 || permission.contains("\n") || permission.contains("\r"))
                throw new IOException("Invalid permission for alias '" + name + "'; existing aliases were kept");
        }
    }

    public synchronized Map<String, Object> view() throws IOException {
        Object loaded;
        try (InputStream input = Files.newInputStream(configPath)) { loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(input); }
        if (!(loaded instanceof Map<?, ?> root) || !(root.get("aliases") instanceof Map<?, ?> aliases)) return Map.of("aliases", Map.of());
        Map<String, Object> safe = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : aliases.entrySet()) if (e.getValue() instanceof Map<?, ?> d) {
            Map<String, Object> def = new LinkedHashMap<>();
            def.put("command", stringValue(d.get("command"))); def.put("permission", stringValue(d.get("permission")));
            def.put("player-only", Boolean.parseBoolean(stringValue(d.get("player-only")))); safe.put(String.valueOf(e.getKey()), def);
        }
        return Map.of("aliases", safe);
    }

    public synchronized int update(Map<?, ?> submitted) throws IOException {
        Object raw = submitted.get("aliases");
        if (!(raw instanceof Map<?, ?> aliases)) throw new IllegalArgumentException("aliases must be an object keyed by command name");
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : aliases.entrySet()) {
            String name = String.valueOf(e.getKey()).trim().toLowerCase(Locale.ROOT);
            if (!VALID_NAME.matcher(name).matches() || Set.of("valiasreload", "vstats", "vservers", "vplayers", "vserver", "vping", "vhelp").contains(name)) throw new IllegalArgumentException("Invalid or reserved alias name: " + name);
            if (!(e.getValue() instanceof Map<?, ?> d)) throw new IllegalArgumentException("Alias " + name + " must be an object");
            String command = stringValue(d.get("command")); String permission = stringValue(d.get("permission"));
            boolean playerOnly = Boolean.parseBoolean(stringValue(d.get("player-only")));
            if (command.isBlank() || command.startsWith("/") || command.contains("\n") || command.contains("\r") || command.length() > 512) throw new IllegalArgumentException("Invalid command for alias " + name);
            if (permission.length() > 128 || permission.contains("\n") || permission.contains("\r")) throw new IllegalArgumentException("Invalid permission for alias " + name);
            Map<String, Object> def = new LinkedHashMap<>(); def.put("command", command); def.put("permission", permission); def.put("player-only", playerOnly); normalized.put(name, def);
        }
        Path temp = configPath.resolveSibling("aliases.yml.tmp");
        Map<String, Object> root = new LinkedHashMap<>(); root.put("aliases", normalized);
        Files.writeString(temp, new Yaml().dump(root), java.nio.charset.StandardCharsets.UTF_8);
        try { Files.move(temp, configPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE); }
        catch (java.nio.file.AtomicMoveNotSupportedException ignored) { Files.move(temp, configPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
        return reload();
    }

    public int loadAndRegister() throws IOException {
        Object loaded;
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        try (InputStream input = Files.newInputStream(configPath)) {
            loaded = yaml.load(input);
        }
        if (!(loaded instanceof Map<?, ?> root)) {
            logger.warn("aliases.yml has no valid YAML mapping; no custom aliases were registered.");
            return 0;
        }
        Object rawAliases = root.get("aliases");
        if (!(rawAliases instanceof Map<?, ?> aliases)) {
            logger.info("No custom command aliases configured in {}", configPath.getFileName());
            return 0;
        }

        int registered = 0;
        for (Map.Entry<?, ?> entry : aliases.entrySet()) {
            String name = String.valueOf(entry.getKey()).trim().toLowerCase(Locale.ROOT);
            if (!VALID_NAME.matcher(name).matches()) {
                logger.warn("Skipping invalid command alias name: {}", name);
                continue;
            }
            if (!(entry.getValue() instanceof Map<?, ?> definition)) {
                logger.warn("Skipping alias '{}': its configuration must be a YAML mapping.", name);
                continue;
            }
            String command = stringValue(definition.get("command"));
            String permission = stringValue(definition.get("permission"));
            boolean playerOnly = Boolean.parseBoolean(stringValue(definition.get("player-only")));
            if (command.isBlank() || command.startsWith("/") || command.contains("\n") || command.contains("\r")) {
                logger.warn("Skipping alias '{}': command must be non-empty, single-line and must not start with '/'.", name);
                continue;
            }
            if (name.equals("valiasreload") || name.equals("vstats") || name.equals("vservers") || name.equals("vplayers") || name.equals("vserver") || name.equals("vping") || name.equals("vhelp")) {
                logger.warn("Skipping alias '{}': name is reserved by Vertex Velocity Web Panel.", name);
                continue;
            }

            try {
                CommandMeta meta = proxy.getCommandManager().metaBuilder(name).plugin(plugin).build();
                proxy.getCommandManager().register(meta, new AliasCommand(command, permission, playerOnly));
                registeredMetas.put(name, meta);
                registered++;
                logger.info("Registered command alias /{} -> {}", name, command);
            } catch (RuntimeException exception) {
                logger.warn("Could not register alias '/{}'. The command may already be registered: {}", name, exception.getMessage());
            }
        }
        return registered;
    }

    private void ensureDefaultConfig() throws IOException {
        if (Files.exists(configPath)) return;
        try (InputStream input = CommandAliasService.class.getResourceAsStream("/defaults/aliases.yml")) {
            if (input != null) Files.copy(input, configPath);
            else Files.writeString(configPath, "# Custom Velocity command aliases\naliases: {}\n");
        }
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private final class AliasCommand implements SimpleCommand {
        private final String targetCommand;
        private final String permission;
        private final boolean playerOnly;

        private AliasCommand(String targetCommand, String permission, boolean playerOnly) {
            this.targetCommand = targetCommand;
            this.permission = permission;
            this.playerOnly = playerOnly;
        }

        @Override
        public void execute(Invocation invocation) {
            CommandSource source = invocation.source();
            if (!permission.isBlank() && !source.hasPermission(permission)) {
                source.sendMessage(net.kyori.adventure.text.Component.text("You do not have permission to use this command."));
                return;
            }
            if (playerOnly && !(source instanceof Player)) {
                source.sendMessage(net.kyori.adventure.text.Component.text("This command can only be used by a player."));
                return;
            }
            String expanded = targetCommand;
            if (source instanceof Player player) {
                expanded = expanded.replace("{player}", player.getUsername())
                        .replace("{uuid}", player.getUniqueId().toString())
                        .replace("{server}", player.getCurrentServer().map(c -> c.getServerInfo().getName()).orElse(""));
            }
            String[] args = invocation.arguments();
            for (int i = 0; i < args.length; i++) {
                expanded = expanded.replace("{" + i + "}", args[i]);
                expanded = expanded.replace("{arg" + (i + 1) + "}", args[i]);
            }
            expanded = expanded.replace("{args}", String.join(" ", args));
            if (expanded.isBlank() || expanded.startsWith("/") || expanded.contains("\n") || expanded.contains("\r")) {
                source.sendMessage(net.kyori.adventure.text.Component.text("This command alias is configured incorrectly."));
                logger.warn("Blocked malformed command produced by an alias.");
                return;
            }
            proxy.getCommandManager().executeAsync(source, expanded).exceptionally(error -> {
                logger.warn("Command alias execution failed.", error);
                source.sendMessage(net.kyori.adventure.text.Component.text("The aliased command could not be executed."));
                return false;
            });
        }
    }
}
