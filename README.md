# Vertex Velocity Web Panel

**An all-in-one administration plugin for Minecraft Velocity, developed by Vertex Studio.**

Vertex Velocity Web Panel brings proxy operations, backend management, player intelligence, network monitoring, analytics and security controls into one responsive web interface. The web runtime is embedded in the Velocity plugin and served by the JDK HTTP/HTTPS server; no separate web application process is required.

## Highlights

- **Live proxy overview** — online players, backend status, proxy uptime and network activity.
- **Native backend management** — add, edit and remove backend entries in Velocity's `velocity.toml`, with validation and safeguards.
- **Routing management** — review and update the configured fallback order.
- **Player intelligence** — inspect player profiles, UUIDs, server visits, sessions and recorded events.
- **Analytics** — player counts, backend availability, latency observations and historical activity.
- **Network map and activity feed** — understand how players move between configured backends.
- **Security controls** — owner and administrator roles, viewer access, password hashing, rate-limited login attempts, expiring sessions, audit logs and optional TOTP.
- **API access** — authenticated REST endpoints and revocable API keys for external tools.
- **Configuration history** — timestamped backups for supported native Velocity configuration changes.
- **Proxy commands** — global player, server and proxy-stat commands available through Velocity.
- **SQLite storage** — persistent users, player events, metrics and audit records.
- **Embedded JDK HTTP/HTTPS runtime** — no Spring Boot classpath or factory discovery is needed inside Velocity.

## Requirements

- Java 25 runtime
- A compatible Minecraft Velocity proxy
- Permission to install plugins and bind the configured web-panel port

## Installation

1. Download `vertex-velocity-web-panel-4.3.0.jar` from the GitHub Release **Assets**.
2. Place the JAR in the `plugins/` directory of the Velocity proxy.
3. Start or restart the proxy.
4. Open the plugin's data directory and review `config.yml`.
5. Open the configured panel URL and complete the initial owner setup using the one-time token printed in the proxy console.
6. Create the owner account, sign in and configure access before making the panel reachable from other machines.

The panel's embedded JDK HTTP/HTTPS listeners start and stop with Velocity. Do not launch the plugin JAR using `java -jar` as a standalone web application.

## Web panel configuration

The plugin creates `plugins/vertexvelocitywebpanel/config.yml`. The web panel uses a loopback bind address by default so it is not automatically exposed to the public internet.

```yaml
web-panel:
  enabled: true
  mode: HTTP # Set to HTTPS to enable automatic certificate detection
  bind-address: 127.0.0.1
  http-port: 25569
  reverse-proxy: false
```

For remote administration, use a trusted HTTPS certificate or a properly secured reverse proxy, restrict network access, and never expose an unencrypted HTTP listener directly to the public internet. Keep the initial setup token private.

## Native Velocity server management

The backend manager edits the `[servers]` section of the proxy's native `velocity.toml` file. Before modifying production configuration, make a separate backup and verify the intended hostnames and ports.

- Backend add/edit/delete operations validate input and create timestamped backups.
- Changes to backend registry entries are applied to the running Velocity registry where supported by the operation.
- Fallback-order changes are written to `velocity.toml` and require a Velocity restart to take effect.
- Restoring a configuration backup changes the file on disk; restart Velocity to apply the restored configuration consistently.

## Automatic HTTPS (5.6.0)

Set `web-panel.mode: HTTPS`. The plugin automatically detects a valid Certbot certificate under `/etc/letsencrypt/live`, checks the certificate/key pair and domain lineage, and hot-reloads renewed files about every 30 seconds without restarting Velocity. Certbot's system timer remains responsible for issuing renewals. If no usable certificate is found, the plugin reuses or generates a local self-signed fallback; browsers will warn because that fallback is not publicly trusted. No certificate paths or passwords need to be configured. See `docs/SECURITY-AND-TLS-HARDENING.md` for permissions and operational details.

## Security guidance

- Use a long, unique owner password.
- Keep the panel bound to loopback unless remote access is required.
- Prefer HTTPS for remote access and firewall the panel ports.
- Create API keys only for trusted clients, store them as secrets and revoke unused keys.
- Give read-only access to users who do not need administrative permissions.
- Keep backups of `velocity.toml` and the plugin's SQLite database.
- Test configuration changes on a staging proxy before applying them to a production network.

## API

The authenticated REST API is documented in [`docs/API.md`](docs/API.md). API keys are intended for authorized, trusted clients and are restricted according to the permissions implemented by the plugin.

## Build from source

Build with JDK 25 and Maven:

```bash
mvn --batch-mode --no-transfer-progress clean verify
```

The distributable plugin JAR is produced under `target/`. Install the shaded `vertex-velocity-web-panel-4.3.0.jar` in the Velocity `plugins/` directory.

## Project

- **Website:** https://vertexdev-studio-website.vercel.app/
- **Repository:** https://github.com/official-vertex-studio/velocity-web-panel
- **License:** GNU General Public License v3.0 — see [`LICENSE`](LICENSE)
- **Brand:** Vertex Studio — see [`TRADEMARKS.md`](TRADEMARKS.md)

---

**Vertex Velocity Web Panel** — one control center for your Velocity network.

## Custom Velocity command aliases

On first startup, the plugin creates `plugins/vertexvelocitywebpanel/aliases.yml` from the bundled defaults. Define aliases under `aliases`, then restart the proxy for them to register. Existing aliases and built-in commands are not overwritten intentionally; aliases that collide with registered commands are logged and skipped.

```yaml
aliases:
  lobby:
    command: "server lobby"
    permission: "vertexvelocity.alias.lobby"
    player-only: true
  whoami:
    command: "send {player} UUID={uuid}, server={server}"
    permission: "vertexvelocity.alias.whoami"
    player-only: true
```

Supported placeholders: `{player}`, `{uuid}`, `{server}`, `{args}`, `{arg1}`, `{arg2}` and zero-based `{0}`, `{1}`, etc. `permission: ""` disables the alias-specific permission check; use a permission node for public servers. `player-only: true` prevents console execution. Alias targets are executed through Velocity's command manager as the original command source, so permissions of the invoking user still apply.

### Maintenance, MOTD and TLS files

Runtime YAML files are stored in `plugins/vertexvelocitywebpanel/`: `config.yml`, `maintenance.yml`, `motd.yml`, and `aliases.yml`. Default templates are bundled under `src/main/resources/defaults/`; runtime changes are persisted to the plugin data directory.

Maintenance enforcement runs at Velocity pre-login. With maintenance enabled and whitelist enforcement enabled, UUIDs not in the whitelist are denied. `allow-existing-players` controls reconnect/pre-login behavior for players already online, and `kick-existing-players` can disconnect non-whitelisted players when maintenance is enabled. The whitelist uses UUIDs as its canonical identity; the API can resolve a `playerName` only while that player is online.

Authenticated API routes include `GET /api/v1/status`, `GET /api/v1/maintenance`, `GET /api/v1/maintenance/status`, `PUT /api/v1/maintenance`, `GET /api/v1/motd`, `GET /api/v1/motd/preview`, and `PUT /api/v1/motd`. API keys can be created with `scope: READ` or `scope: READ_WRITE`. READ_WRITE keys can only call an explicit safe allowlist: backend probes, alert acknowledgement and predefined Command Center actions. They cannot change proxy configuration, moderate players or manage accounts, backends or API keys.

With `web-panel.mode: HTTPS`, TLS is fully automatic. At startup the plugin first searches the standard Certbot directory `/etc/letsencrypt/live/*/` for a valid `fullchain.pem` + `privkey.pem` pair. It verifies certificate dates, the trusted issuer chain, the SAN/domain lineage and the private-key match before using it. No certificate paths or passwords need to be entered. If no usable Certbot certificate is found, the plugin reuses its own `tls/keystore.p12`; if that does not exist or is invalid, it generates and stores a self-signed certificate automatically. The generated certificate is reused on later starts. The plugin checks Certbot files every 30 seconds and hot-reloads renewed certificates for new HTTPS connections without restarting Velocity. Certbot's system timer or cron job performs the actual renewal.

A self-signed certificate encrypts traffic but is not trusted by browsers, which will show a security warning. For a public panel without that warning, install Certbot on the host and make its certificate directory readable by the Velocity process. The plugin does not install Certbot or request a public certificate itself: public certificate issuance still requires domain validation and host-level access. When multiple Certbot lineages are present, the plugin selects only a certificate whose SAN matches the machine canonical hostname; if it cannot disambiguate them, it uses the fallback rather than selecting another domain's certificate. The panel's HTTPS listener uses the configured `http-port` as its listening port.

## Release validation and limitations

See [`docs/REMAINING-VALIDATION.md`](docs/REMAINING-VALIDATION.md) for TOTP recovery-code behavior, automatic TLS certificate detection, settings that require a Velocity restart, and the mandatory Java 25 / real-proxy release checklist. The checklist is not evidence that those tests have already passed.


## 🚨 Operations & Reliability 4.2

- Persistent backend alerts with configurable latency thresholds, cooldowns and retention.
- Monitoring policy controls and alert acknowledgement.
- Safe diagnostic actions; no arbitrary console or operating-system command runner.
- Global search, player/backend CSV exports and OpenAPI API documentation.
- Consistent SQLite snapshots with authenticated owner-only download.
- Browser sessions are revalidated against account disablement and role changes.


## Operations & Support 4.3

- Added a downloadable sanitized JSON support bundle for owner/administrator troubleshooting.
- Restricted `READ_WRITE` API keys to an explicit allowlist of safe operations; they no longer inherit administrator identity or configuration permissions.
- Expanded diagnostic metadata while omitting backend addresses, credential material, webhook URLs and filesystem paths.
- Improved audit and player-history export limits and hardened backup permission fallback behavior.
