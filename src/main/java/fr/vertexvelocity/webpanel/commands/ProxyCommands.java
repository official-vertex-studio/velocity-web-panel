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
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.time.Instant;
import fr.vertexvelocity.webpanel.db.PanelDatabase;
import java.sql.SQLException;

public final class ProxyCommands {
    private final ProxyServer proxy;
    private final Instant startedAt;
    private final PanelDatabase database;
    private volatile BooleanSupplier panelActive = () -> false;

    public ProxyCommands(ProxyServer proxy, Instant startedAt, PanelDatabase database) {
        this.proxy = proxy;
        this.startedAt = startedAt;
        this.database = database;
    }

    /** Lets /vpanel report the real state of the web listener instead of a hard-coded value. */
    public void setPanelActive(BooleanSupplier supplier) { this.panelActive = supplier == null ? () -> false : supplier; }

    public void register(Object plugin) {
        register(plugin, "vstats", new String[]{"vertexstats"}, null, this::stats);
        register(plugin, "vpanel", new String[]{"vertexpanel"}, null, this::panel);
        register(plugin, "vservers", new String[]{"vertexservers"}, null, this::servers);
        register(plugin, "vplayers", new String[]{"vertexplayers"}, PERM_PLAYERS, this::players);
        register(plugin, "vserver", new String[]{"vertexserver"}, null, this::server);
        register(plugin, "vping", new String[]{"vertexping"}, null, this::ping);
        register(plugin, "vhelp", new String[]{"vertexhelp"}, null, this::help);
    }

    private static final String PERM_PLAYERS = "vertexvelocity.command.players";
    private static final String PERM_SERVER = "vertexvelocity.command.server";
    private static final String PERM_PLAYERSTATS = "vertexvelocity.command.playerstats";

    /** @param permission when non-null the command is hidden from (and not executable by) sources lacking it. */
    private void register(Object plugin, String name, String[] aliases, String permission, Handler handler) {
        CommandMeta meta = proxy.getCommandManager().metaBuilder(name).aliases(aliases).plugin(plugin).build();
        proxy.getCommandManager().register(meta, new SimpleCommand() {
            @Override public void execute(Invocation invocation) { handler.run(invocation); }
            @Override public boolean hasPermission(Invocation invocation) { return permission == null || invocation.source().hasPermission(permission); }
            @Override public List<String> suggest(Invocation invocation) { return suggestFor(name, invocation); }
        });
    }

    private List<String> suggestFor(String name, SimpleCommand.Invocation invocation) {
        String[] args = invocation.arguments();
        int index = Math.max(0, args.length - 1);
        String prefix = args.length == 0 ? "" : args[index].toLowerCase(Locale.ROOT);
        CommandSource source = invocation.source();
        return switch (name) {
            case "vplayers" -> index == 0 ? backendNames(prefix) : List.of();
            case "vserver" -> index == 0 && source.hasPermission(PERM_SERVER) ? playerNames(prefix) : List.of();
            case "vstats" -> {
                if (index == 0) yield filter(List.of("serveur", "joueur"), prefix);
                if (index == 1) {
                    String sub = args[0].toLowerCase(Locale.ROOT);
                    if (sub.equals("serveur") || sub.equals("server")) yield backendNames(prefix);
                    if (sub.equals("joueur") || sub.equals("player")) yield playerNames(prefix);
                }
                yield List.of();
            }
            default -> List.of();
        };
    }
    private List<String> backendNames(String prefix) { return filter(proxy.getAllServers().stream().map(sv -> sv.getServerInfo().getName()).toList(), prefix); }
    private List<String> playerNames(String prefix) { return filter(proxy.getAllPlayers().stream().map(Player::getUsername).toList(), prefix); }
    private static List<String> filter(List<String> values, String prefix) {
        return values.stream().filter(v -> v.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted(String.CASE_INSENSITIVE_ORDER).limit(50).toList();
    }

    private void stats(SimpleCommand.Invocation inv) {
        CommandSource s = inv.source();
        String[] args = inv.arguments();
        if (args.length > 0) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            boolean backend = sub.equals("serveur") || sub.equals("server");
            boolean player = sub.equals("joueur") || sub.equals("player");
            if (!backend && !player) { line(s, "Usage: /vstats [serveur <backend> | joueur <player>]", NamedTextColor.YELLOW); return; }
            if (args.length < 2) { line(s, "Usage: /vstats " + sub + (backend ? " <backend>" : " <player>"), NamedTextColor.YELLOW); return; }
            if (backend) serverStats(s, args[1]); else playerStats(s, args[1]);
            return;
        }
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
        try { Map<String,Object> history=database.analyticsSummary(1);line(s,"Last 24h · Peak: "+history.getOrDefault("peakPlayers",0)+" · Average: "+history.getOrDefault("averagePlayers",0)+" · Connections: "+history.getOrDefault("connections",0),NamedTextColor.AQUA); } catch(Exception ignored) {}
        line(s, "Use /vservers, /vplayers, /vstats serveur <name>, /vstats joueur <name> or /vhelp", NamedTextColor.DARK_GRAY);
    }

    private void serverStats(CommandSource s,String name){
        Optional<RegisteredServer> found=proxy.getAllServers().stream().filter(b->b.getServerInfo().getName().equalsIgnoreCase(name)).findFirst();
        if(found.isEmpty()){line(s,"Unknown backend: "+name,NamedTextColor.RED);return;}
        RegisteredServer b=found.get();int online=b.getPlayersConnected().size();
        line(s,"BACKEND · "+b.getServerInfo().getName(),NamedTextColor.LIGHT_PURPLE);
        line(s,"Connected players: "+online,NamedTextColor.GREEN);
        line(s,"Registered address: "+b.getServerInfo().getAddress().getHostString()+":"+b.getServerInfo().getAddress().getPort(),NamedTextColor.GRAY);
        try{Map<String,Object> history=database.analyticsSummary(30);Object rows=history.get("backends");if(rows instanceof List<?> list){for(Object item:list)if(item instanceof Map<?,?> row&&name.equalsIgnoreCase(String.valueOf(row.get("server")))){line(s,"30d availability: "+row.get("availabilityPercent")+"% · Avg latency: "+row.get("averageLatencyMs")+" ms",NamedTextColor.AQUA);break;}}}catch(Exception ignored){}
        line(s,"Historical samples: web panel → Analytics / Monitoring",NamedTextColor.GRAY);
    }
    private void playerStats(CommandSource s,String name){
        Optional<Player> online=proxy.getPlayer(name);
        if(online.isPresent()){
            Player p=online.get();String backend=p.getCurrentServer().map(v->v.getServerInfo().getName()).orElse("Connecting");
            line(s,"PLAYER · "+p.getUsername(),NamedTextColor.LIGHT_PURPLE);
            line(s,"Status: ONLINE · Backend: "+backend+" · Ping: "+p.getPing()+" ms",NamedTextColor.GREEN);
            try{Map<String,Object> profile=database.playerProfile(p.getUniqueId().toString(),20);line(s,"Recorded playtime: "+duration(((Number)profile.getOrDefault("totalPlaySeconds",0L)).longValue()),NamedTextColor.GRAY);line(s,"Recorded sessions: "+profile.getOrDefault("sessionCount",0),NamedTextColor.GRAY);}catch(Exception e){line(s,"Historical profile unavailable.",NamedTextColor.YELLOW);}
            return;
        }
        if(!s.hasPermission(PERM_PLAYERSTATS)){line(s,"You do not have permission: "+PERM_PLAYERSTATS,NamedTextColor.RED);return;}
        try {
            Optional<Map<String,Object>> found=database.playerProfileByName(name,30);
            if(found.isPresent()){Map<String,Object> profile=found.get();line(s,"PLAYER HISTORY · "+profile.get("username"),NamedTextColor.LIGHT_PURPLE);line(s,"Total playtime: "+duration(((Number)profile.getOrDefault("totalPlaySeconds",0L)).longValue()),NamedTextColor.GREEN);line(s,"Recorded sessions: "+profile.getOrDefault("sessionCount",0)+" · Transfers: "+profile.getOrDefault("transferCount",0),NamedTextColor.GRAY);line(s,"Last seen: "+Instant.ofEpochSecond(((Number)profile.getOrDefault("lastSeen",0L)).longValue()),NamedTextColor.GRAY);return;}
        }catch(Exception ignored){}
        line(s,"Player is offline or has not been recorded: "+name,NamedTextColor.YELLOW);
    }
    private void panel(SimpleCommand.Invocation inv){
        CommandSource s=inv.source();long uptime=Math.max(0,Instant.now().getEpochSecond()-startedAt.getEpochSecond());
        line(s,"VERTEX PANEL STATUS",NamedTextColor.LIGHT_PURPLE);
        line(s,"Proxy: ONLINE · Players: "+proxy.getPlayerCount(),NamedTextColor.GREEN);
        boolean active=panelActive.getAsBoolean();line(s,"Web panel: "+(active?"ACTIVE":"NOT RUNNING"),active?NamedTextColor.GREEN:NamedTextColor.RED);
        line(s,"Uptime: "+duration(uptime)+" · Backends: "+proxy.getAllServers().size(),NamedTextColor.GRAY);
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
        if (!s.hasPermission(PERM_PLAYERS)) { line(s, "You do not have permission: "+PERM_PLAYERS, NamedTextColor.RED); return; }
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
        if (!s.hasPermission(PERM_SERVER)) { line(s, "You do not have permission: "+PERM_SERVER, NamedTextColor.RED); return; }
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
        line(s, "/vstats - Global proxy statistics (24h history)", NamedTextColor.GRAY);
        line(s, "/vstats serveur <name> - Backend statistics", NamedTextColor.GRAY);
        line(s, "/vstats joueur <name> - Player activity and playtime", NamedTextColor.GRAY);
        line(s, "/vpanel - Web panel and proxy status", NamedTextColor.GRAY);
        line(s, "/vservers - Backends and connected player counts", NamedTextColor.GRAY);
        if (s.hasPermission(PERM_PLAYERS)) line(s, "/vplayers [backend] - Online player list", NamedTextColor.GRAY);
        line(s, s.hasPermission(PERM_SERVER) ? "/vserver [player] - Your backend or another player's backend" : "/vserver - Your current backend", NamedTextColor.GRAY);
        line(s, "/vping - Your current proxy ping", NamedTextColor.GRAY);
        line(s, "Every command also works as /vertex<name>, e.g. /vertexstats", NamedTextColor.DARK_GRAY);
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
