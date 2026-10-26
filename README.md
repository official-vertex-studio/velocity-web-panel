# Vertex Velocity Web Panel

**An all-in-one administration plugin for Minecraft Velocity, developed by Vertex Studio.**

Vertex Velocity Web Panel brings proxy operations, backend management, player intelligence, network monitoring, analytics and security controls into one responsive web interface. The web runtime is embedded in the Velocity plugin and powered by Spring Boot; no separate web application process is required.

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
- **Spring Boot 4.1 web runtime** — embedded Spring MVC/Tomcat lifecycle managed by the Velocity plugin.

## Requirements

- Java 25 runtime
- A compatible Minecraft Velocity proxy
- Permission to install plugins and bind the configured web-panel port

## Installation

1. Download `vertex-velocity-web-panel-2.0.0.jar` from the GitHub Release **Assets**.
2. Place the JAR in the `plugins/` directory of the Velocity proxy.
3. Start or restart the proxy.
4. Open the plugin's data directory and review `config.yml`.
5. Open the configured panel URL and complete the initial owner setup using the one-time token printed in the proxy console.
6. Create the owner account, sign in and configure access before making the panel reachable from other machines.

The panel's embedded Spring Boot server starts and stops with Velocity. Do not launch the plugin JAR using `java -jar` as a standalone web application.

## Web panel configuration

The plugin creates `plugins/vertexvelocitywebpanel/config.yml`. The web panel uses a loopback bind address by default so it is not automatically exposed to the public internet.

```yaml
web-panel:
  enabled: true
  mode: HTTP # HTTP, HTTPS, or BOTH
  bind-address: 127.0.0.1
  http-port: 25569
  https-port: 8443
  https:
    enabled: false
    keystore: ''
    keystore-password: ''
    keystore-type: PKCS12
```

For remote administration, use a trusted HTTPS certificate or a properly secured reverse proxy, restrict network access, and never expose an unencrypted HTTP listener directly to the public internet. Keep the keystore password and initial setup token private.

## Native Velocity server management

The backend manager edits the `[servers]` section of the proxy's native `velocity.toml` file. Before modifying production configuration, make a separate backup and verify the intended hostnames and ports.

- Backend add/edit/delete operations validate input and create timestamped backups.
- Changes to backend registry entries are applied to the running Velocity registry where supported by the operation.
- Fallback-order changes are written to `velocity.toml` and require a Velocity restart to take effect.
- Restoring a configuration backup changes the file on disk; restart Velocity to apply the restored configuration consistently.

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

The distributable plugin JAR is produced under `target/`. Install the shaded `vertex-velocity-web-panel-2.0.0.jar` in the Velocity `plugins/` directory.

## Project

- **Website:** https://vertexdev-studio-website.vercel.app/
- **Repository:** https://github.com/official-vertex-studio/velocity-web-panel
- **License:** GNU General Public License v3.0 — see [`LICENSE`](LICENSE)
- **Brand:** Vertex Studio — see [`TRADEMARKS.md`](TRADEMARKS.md)

---

**Vertex Velocity Web Panel** — one control center for your Velocity network.
