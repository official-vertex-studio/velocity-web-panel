package fr.vertexvelocity.webpanel;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import fr.vertexvelocity.webpanel.config.PanelConfig;
import fr.vertexvelocity.webpanel.commands.ProxyCommands;
import fr.vertexvelocity.webpanel.commands.CommandAliasService;
import fr.vertexvelocity.webpanel.db.PanelDatabase;
import fr.vertexvelocity.webpanel.service.MaintenanceService;
import fr.vertexvelocity.webpanel.service.MotdService;
import fr.vertexvelocity.webpanel.web.WebPanelServer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

@Plugin(id = "vertexvelocitywebpanel", name = "Vertex Velocity Web Panel", version = "3.0.0", description = "Premium live administration panel for Velocity", authors = {"Vertex Studio"})
public final class VertexVelocityWebPanel {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private PanelConfig config;
    private PanelDatabase database;
    private WebPanelServer webPanel;
    private Instant startedAt;
    private MaintenanceService maintenance;
    private MotdService motd;
    private CommandAliasService aliases;

    @Inject
    public VertexVelocityWebPanel(ProxyServer proxy, Logger logger, @com.velocitypowered.api.plugin.annotation.DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        try {
            Files.createDirectories(dataDirectory);
            this.config = PanelConfig.load(dataDirectory.resolve("config.yml"));
            this.database = new PanelDatabase(dataDirectory.resolve("vertex-panel.sqlite"));
            this.database.initialize();
            this.startedAt = Instant.now();
            this.maintenance = new MaintenanceService(dataDirectory);
            this.motd = new MotdService(dataDirectory);
            new ProxyCommands(proxy, startedAt).register(this);
            logger.info("Registered proxy-wide commands: /vstats, /vservers, /vplayers, /vserver, /vping, /vhelp");
            this.aliases = new CommandAliasService(proxy, logger, this, dataDirectory);
            int aliasCount = aliases.loadAndRegister();
            logger.info("Registered {} custom command alias(es) from aliases.yml", aliasCount);
            this.webPanel = new WebPanelServer(proxy, logger, config, database, startedAt, dataDirectory, maintenance, motd, aliases);
            webPanel.setPluginOwner(this);
            webPanel.start();
            webPanel.notifyProxyStarted();
            logger.info("Vertex Velocity Web Panel initialized. Panel mode: {}", config.webMode());
            if (database.userCount() == 0) {
                String setupToken = database.createSetupToken();
                logger.warn("No web owner exists yet. Open the panel and use the one-time setup token: {}", setupToken);
                logger.warn("This token is shown only in the proxy console. Create the owner account immediately.");
            }
        } catch (Exception e) {
            logger.error("Vertex Velocity Web Panel could not start. The proxy will continue without the web panel.", e);
        }
    }


    @Subscribe
    public void onPreLogin(PreLoginEvent event) {
        MaintenanceService state = maintenance;
        if (state == null || !state.enabled()) return;
        String uuid = String.valueOf(event.getUniqueId());
        if (!state.isAllowed(uuid) && !(state.allowExistingPlayers() && proxy.getPlayer(event.getUniqueId()).isPresent())) {
            event.setResult(PreLoginEvent.PreLoginComponentResult.denied(Component.text(state.kickMessage())));
        }
    }

    @Subscribe
    public void onProxyPing(ProxyPingEvent event) {
        MotdService service = motd;
        if (service == null) return;
        try { event.setPing(service.apply(event.getPing(), maintenance != null && maintenance.enabled())); }
        catch (RuntimeException error) { logger.warn("Could not apply configured MOTD; keeping Velocity's current ping response.", error); }
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        if (database != null) database.recordPlayerEvent("LOGIN", player.getUniqueId().toString(), player.getUsername(), null, null, Instant.now());
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        String serverName = player.getCurrentServer().map(s -> s.getServerInfo().getName()).orElse(null);
        if (database != null) database.recordPlayerEvent("DISCONNECT", player.getUniqueId().toString(), player.getUsername(), serverName, null, Instant.now());
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        Player player = event.getPlayer();
        String serverName = event.getServer().getServerInfo().getName();
        if (database != null) database.recordPlayerEvent("SERVER_SWITCH", player.getUniqueId().toString(), player.getUsername(), serverName, "Connected to backend " + serverName, Instant.now());
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (webPanel != null) webPanel.stop();
        if (database != null) database.close();
        logger.info("Vertex Velocity Web Panel stopped.");
    }
}
