package fr.vertexvelocity.webpanel.commands;

import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.time.Instant;

public final class ProxyCommands {
    private final ProxyServer proxy;
    private final Instant startedAt;

    public ProxyCommands(ProxyServer proxy, Instant startedAt) {
        this.proxy = proxy;
        this.startedAt = startedAt;
    }

    public void register(Object plugin) {
        register(plugin, "vstats", new String[]{"vertexstats"}, this::stats);
        register(plugin, "vservers", new String[]{"vertexservers"}, this::servers);
        register(plugin, "vplayers", new String[]{"vertexplayers"}, this::players);
        register(plugin, "vserver", new String[]{"vertexserver"}, this::server);
        register(plugin, "vping", new String[]{"vertexping"}, this::ping);
        register(plugin, "vhelp", new String[]{"vertexhelp"}, this::help);
    }

    private void register(Object plugin, String name, String[] aliases, Handler handler) {
        CommandMeta meta = proxy.getCommandManager().metaBuilder(name).aliases(aliases).plugin(plugin).build();
        proxy.getCommandManager().register(meta, new SimpleCommand() {
            @Override public void execute(Invocation invocation) { handler.run(invocation); }
            @Override public List<String> suggest(Invocation invocation) {
                if (name.equals("vplayers")) {
                    String prefix = invocation.arguments().length == 0 ? "" : invocation.arguments()[invocation.arguments().length - 1].toLowerCase(Locale.ROOT);
                    return proxy.getAllServers().stream().map(server -> server.getServerInfo().getName()).filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().limit(50).toList();
                }
                if (name.equals("vserver")) {
                    String prefix = invocation.arguments().length == 0 ? "" : invocation.arguments()[invocation.arguments().length - 1].toLowerCase(Locale.ROOT);
                    return proxy.getAllPlayers().stream().map(Player::getUsername).filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().limit(50).toList();
                }
                return List.of();
            }
        });
    }

    private void stats(SimpleCommand.Invocation inv) {
        CommandSource s = inv.source();
        long totalPing = proxy.getAllPlayers().stream().mapToLong(Player::getPing).sum();
        long avgPing = proxy.getPlayerCount() == 0 ? 0 : totalPing / proxy.getPlayerCount();
        line(s, "VERTEX VELOCITY  /  NETWORK STATUS", NamedTextColor.LIGHT_PURPLE);
        line(s, "Online players: " + proxy.getPlayerCount(), NamedTextColor.GREEN);
        line(s, "Registered backends: " + proxy.getAllServers().size(), NamedTextColor.AQUA);
        line(s, "Average player ping: " + avgPing + " ms", NamedTextColor.GRAY);
        long uptime = Math.max(0, Instant.now().getEpochSecond() - startedAt.getEpochSecond());
        long memoryUsedMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024);
        long memoryMaxMb = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        line(s, "Proxy uptime: " + duration(uptime), NamedTextColor.GRAY);
        line(s, "JVM memory: " + memoryUsedMb + " / " + memoryMaxMb + " MiB", NamedTextColor.GRAY);
        int onlineBackends = 0;
        for (RegisteredServer server : proxy.getAllServers()) {
            if (!server.getPlayersConnected().isEmpty()) onlineBackends++;
        }
        line(s, "Backends with players: " + onlineBackends, NamedTextColor.GRAY);
        line(s, "Use /vservers, /vplayers or /vhelp", NamedTextColor.DARK_GRAY);
    }

    private void servers(SimpleCommand.Invocation inv) {
        CommandSource s = inv.source();
        line(s, "NETWORK BACKENDS", NamedTextColor.LIGHT_PURPLE);
        List<RegisteredServer> servers = new ArrayList<>(proxy.getAllServers());
        servers.sort(Comparator.comparing(a -> a.getServerInfo().getName(), String.CASE_INSENSITIVE_ORDER));
        if (servers.isEmpty()) { line(s, "No backends are configured.", NamedTextColor.RED); return; }
        for (RegisteredServer backend : servers) {
            int count = backend.getPlayersConnected().size();
            line(s, backend.getServerInfo().getName() + "  •  " + count + " player(s) connected", count > 0 ? NamedTextColor.GREEN : NamedTextColor.GRAY);
        }
    }

    private void players(SimpleCommand.Invocation inv) {
        CommandSource s = inv.source();
        if (!s.hasPermission("vertexvelocity.command.players")) { line(s, "You do not have permission: vertexvelocity.command.players", NamedTextColor.RED); return; }
        String filter = inv.arguments().length > 0 ? inv.arguments()[0].toLowerCase(Locale.ROOT) : "";
        List<Player> matches = proxy.getAllPlayers().stream()
                .filter(p -> filter.isEmpty() || p.getCurrentServer().map(c -> c.getServerInfo().getName().equalsIgnoreCase(filter)).orElse(false))
                .sorted(Comparator.comparing(Player::getUsername, String.CASE_INSENSITIVE_ORDER)).toList();
        line(s, "ONLINE PLAYERS (" + matches.size() + ")", NamedTextColor.LIGHT_PURPLE);
        if (matches.isEmpty()) { line(s, "No players match this filter.", NamedTextColor.GRAY); return; }
        for (Player p : matches) {
            String backend = p.getCurrentServer().map(c -> c.getServerInfo().getName()).orElse("Connecting");
            line(s, p.getUsername() + "  •  " + backend + "  •  " + p.getPing() + " ms", NamedTextColor.GRAY);
        }
    }

    private void server(SimpleCommand.Invocation inv) {
        CommandSource s = inv.source();
        if (inv.arguments().length == 0) {
            if (s instanceof Player player) {
                String backend = player.getCurrentServer().map(c -> c.getServerInfo().getName()).orElse("Connecting");
                line(s, "You are connected to: " + backend, NamedTextColor.GREEN);
            } else line(s, "Usage: /vserver <player>", NamedTextColor.YELLOW);
            return;
        }
        if (!s.hasPermission("vertexvelocity.command.server")) { line(s, "You do not have permission: vertexvelocity.command.server", NamedTextColor.RED); return; }
        Optional<Player> target = proxy.getPlayer(inv.arguments()[0]);
        if (target.isEmpty()) { line(s, "That player is not connected to this proxy.", NamedTextColor.RED); return; }
        Player p = target.get();
        String backend = p.getCurrentServer().map(c -> c.getServerInfo().getName()).orElse("Connecting");
        line(s, p.getUsername() + " is on " + backend + " (" + p.getPing() + " ms)", NamedTextColor.GREEN);
    }

    private void ping(SimpleCommand.Invocation inv) {
        if (inv.source() instanceof Player p) line(inv.source(), "Your proxy ping: " + p.getPing() + " ms", NamedTextColor.AQUA);
        else line(inv.source(), "This command is available to connected players.", NamedTextColor.YELLOW);
    }

    private void help(SimpleCommand.Invocation inv) {
        CommandSource s = inv.source();
        line(s, "VERTEX NETWORK COMMANDS", NamedTextColor.LIGHT_PURPLE);
        line(s, "/vstats - Global proxy statistics", NamedTextColor.GRAY);
        line(s, "/vservers - Backends and connected player counts", NamedTextColor.GRAY);
        line(s, "/vplayers [backend] - Online player list (permission required)", NamedTextColor.GRAY);
        line(s, "/vserver [player] - Your backend or another player's backend", NamedTextColor.GRAY);
        line(s, "/vping - Your current proxy ping", NamedTextColor.GRAY);
    }

    private static String duration(long seconds) {
        long days = seconds / 86400; long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60; long remainder = seconds % 60;
        return (days > 0 ? days + "d " : "") + String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, remainder);
    }

    private static void line(CommandSource source, String text, NamedTextColor color) {
        source.sendMessage(Component.text(text, color));
    }

    @FunctionalInterface private interface Handler { void run(SimpleCommand.Invocation invocation); }
}
