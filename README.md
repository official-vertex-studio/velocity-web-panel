# Vertex Velocity Web Panel

**A premium web administration panel and REST API for the official Minecraft Velocity proxy.**

> **Compatibility note:** Vertex Velocity Web Panel is a plugin for the official Minecraft Velocity proxy. It is not a standalone proxy and must not be installed on a Paper backend.

- [Overview](#overview)
- [Features](#features)
- [Requirements](#requirements)
- [Build from source](#build-from-source)
- [Install on Velocity / Pterodactyl](#install-on-velocity--pterodactyl)
- [First launch and owner setup](#first-launch-and-owner-setup)
- [Ports, network binding and HTTPS](#ports-network-binding-and-https)
- [Configuration](#configuration)
- [In-game commands](#in-game-commands)
- [REST API](#rest-api)
- [API authentication and key management](#api-authentication-and-key-management)
- [Language examples / SDKs](#language-examples--sdks)
- [Security model](#security-model)
- [Data, analytics and monitoring limitations](#data-analytics-and-monitoring-limitations)
- [Troubleshooting](#troubleshooting)
- [Upgrade and backup](#upgrade-and-backup)
- [Project documentation](#project-documentation)

## Overview

Vertex Velocity Web Panel runs inside the Velocity proxy process. It registers proxy-level commands, listens for relevant Velocity player/backend events, records observations in SQLite, and exposes a web UI plus a language-neutral REST API.

The architecture is intentionally simple:

```text
Minecraft clients
      |
      v
Velocity proxy + Vertex Velocity Web Panel plugin
      |                         |
      |                         +--> Embedded web UI / REST API
      |                                      |
      +--> lobby / survival / minigames      +--> external dashboards / scripts
      |
      +--> SQLite history, metrics, sessions, audit records
```

The plugin only knows data available to the proxy and events it records. It does not automatically read arbitrary data from Paper plugins on every backend. For example, a Paper-only economy balance, claim count or plugin-specific statistic needs a separate integration on that backend; it is not supplied by this proxy API by default.

## Features

### Web dashboard

- Premium dark navy interface with Vertex Studio blue/orange accents, responsive navigation and restrained motion effects.
- Overview cards for online players, backend count, uptime and JVM memory.
- Player directory and observed player profiles.
- Backend list, ping/health snapshots, network topology and activity feed.
- Historical metrics and analytics stored in SQLite.
- Audit log and account/security pages.
- API key creation/revocation and external integration examples.
- TOTP authenticator setup and login challenge flow.
- Active web-session listing and revocation.
- Native Velocity server manager: add, edit, and delete backend entries in `velocity.toml` from the web panel, with input validation and live registry updates.
- Fallback-order editor for the `try` list in Velocity's native `velocity.toml`.
- Timestamped `velocity.toml` configuration snapshots, a fixed emergency backup, and a guarded restore workflow (restore requires a proxy restart).
- Optional Discord webhook notifications for proxy startup and backend/latency transitions.

### Proxy-wide Minecraft commands

Commands are registered by Velocity itself. They can be used while a player is connected to any backend without installing a separate Paper command plugin on each backend. See [In-game commands](#in-game-commands).

### REST API and language support

The API uses HTTP + JSON and is language-agnostic. External applications can consume the documented REST endpoints using standard HTTP clients.

### Security and access control

- First-owner setup token printed to the Velocity console.
- Salted PBKDF2-HMAC-SHA256 password hashes.
- HttpOnly session cookie and server-side session expiration.
- Per-observed-IP login attempt limiting.
- Owner/admin/moderator/viewer role checks on supported endpoints.
- Read-only API keys; only SHA-256 hashes are persisted, and raw keys are shown once.
- TOTP two-factor authentication with a six-digit code and a login challenge.
- Audit records for important account and administration actions.
- Security response headers and optional embedded HTTPS listener.

TOTP secrets are stored in the plugin database. Recovery codes are not currently provided, so administrators should retain secure access to their authenticator and proxy data.

## Requirements

- Minecraft proxy: **Velocity**, using a version compatible with the dependency declared in `pom.xml`.
- Java Development Kit **25 or newer** for the current build configuration (`maven.compiler.release=25`).
- Maven 3.9 or newer.
- Network access to the Maven repositories when resolving dependencies.
- A Pterodactyl allocation and firewall rule for the web listener if it must be reachable outside the container.
- A compatible Velocity backend configuration, such as lobby and survival servers.

**Important:** the JDK used to build the plugin must support Java 25. A server runtime using a different Java version should be checked against both Velocity's requirements and the compiled artifact's target version.

## Build from source

From the repository root:

```bash
mvn clean package
```

Expected output:

```text
target/vertex-velocity-web-panel-1.1.0.jar
```

The Maven Shade Plugin packages runtime dependencies and relocates Gson and SnakeYAML. The Velocity API is declared `provided` because Velocity supplies it at runtime.

Check that the build ends with `BUILD SUCCESS` and that the expected JAR exists before installing it. A ZIP passing `unzip -t` or a JavaScript syntax check is **not** proof that the Java plugin builds or runs.

### Build environment checks

```bash
java -version
mvn -version
```

Both commands should report a JDK 25-compatible toolchain and Maven. If Maven cannot resolve a dependency, inspect the full repository URL, proxy/network settings and the exact artifact/version in the error before changing dependency versions. Do not replace the Velocity API version at random.

## Install on Velocity / Pterodactyl

1. Stop the **Velocity proxy** server in Pterodactyl. Do not install this JAR on a Paper backend.
2. Download or build the source and run `mvn clean package` with JDK 25.
3. Back up the existing Velocity `plugins/` directory and, if upgrading, the plugin data directory.
4. Upload `target/vertex-velocity-web-panel-1.1.0.jar` to the proxy's `plugins/` directory.
5. Start Velocity and read its console. Check that the plugin initializes without `ERROR`, linkage errors or missing-class exceptions.
6. Find the web listener address and the one-time owner setup token in the console.
7. Open the panel and complete the owner setup immediately.
8. Confirm `/vstats`, `/vservers` and `/vhelp` in Minecraft after joining through the proxy.
9. Test API authentication, backend pings, player history and any administrative operation on a staging server before relying on them.

Do not upload a source ZIP into `plugins/`; Velocity requires the compiled JAR. Do not claim a JAR is working solely because it appears in the file list.

## First launch and owner setup

On first launch, if no owner exists, the plugin prints a one-time setup token to the Velocity console. Open the panel and use the token to create the owner account. Choose a unique password of at least 12 characters and keep it in a password manager.

- The setup token is a secret. Do not post it in Discord, screenshots, tickets or public logs.
- The owner account can create API keys and additional accounts; do not share the owner login with a bot.
- Create separate named accounts with the least privilege needed.
- If the token was exposed before setup, restrict access to the panel and restart only after confirming whether an owner account already exists.
- If no setup token appears, inspect the console for a database initialization failure and check whether an owner already exists in the existing SQLite database.

## Ports, network binding and HTTPS

The Minecraft port and web panel port are separate listeners. Changing the web port should not change the Minecraft listener.

Example deployment values:

- Minecraft / Velocity listener: `25567` (keep your current working value if different).
- Web panel: `25569`.
- Web bind: `127.0.0.1` for local access/reverse-proxy-only access, or `0.0.0.0` when intentionally exposing the panel through the container network.

For Pterodactyl, all of these must be true for remote access:

1. The web port is configured in `config.yml`.
2. A matching allocation is attached to the **Velocity container**.
3. The listener is bound to an address reachable through the container/network configuration.
4. Host firewall/provider rules permit the TCP port.
5. The panel URL uses the correct host, port and scheme.

A plugin cannot create a Pterodactyl allocation or open a host firewall. Never change a working Minecraft allocation merely to expose the panel.

### Recommended public access

Prefer HTTPS. Either configure the embedded HTTPS listener with a valid keystore or keep the plugin listener private and terminate TLS at a reverse proxy that you control. If a reverse proxy is used, restrict direct access to its backend port. Do not enable trusted proxy headers unless the proxy is trusted and clients cannot bypass it.

Plain HTTP transmits passwords and API keys without transport encryption. Use HTTP only on localhost or a trusted private network.

## Configuration

The plugin creates `plugins/vertexvelocitywebpanel/config.yml` in the proxy's plugin data directory. Let the plugin create the file on first startup, then edit only the settings you need. Preserve extra keys generated by the version you installed.

Typical initial configuration:

```yaml
web-panel:
  enabled: true
  mode: HTTP                 # HTTP, HTTPS or BOTH
  bind-address: 127.0.0.1    # private by default
  http-port: 25569
  https-port: 8443
  https:
    enabled: false
    keystore: ''
    keystore-password: ''
    keystore-type: PKCS12     # PKCS12 or JKS
security:
  session-minutes: 60
  login-attempts-per-minute: 8
  trust-proxy-headers: false
statistics:
  sample-seconds: 15
  retention-days: 30
  backend-connect-timeout-ms: 1500
discord:
  enabled: false
  webhook-url: ''
```

Notes:

- The configuration snippet is an example, not a replacement for the generated file.
- `mode: BOTH` starts HTTP and HTTPS listeners on separate ports. HTTPS requires `https.enabled: true`, a valid keystore path and a keystore password.
- Listener, bind and TLS changes require a full Velocity restart; saving settings does not hot-rebind the listener.
- The sample interval is bounded to 5–3600 seconds by the settings update path; retention is bounded to 1–3650 days.
- Keep the keystore password and Discord webhook URL secret. The safe config API intentionally omits the keystore password.
- The `maintenance` flag is informational in this version. It does **not** block Minecraft logins.

### Embedded HTTPS keystore example

Create a PKCS12 keystore on a trusted machine (replace the hostname and protect the password):

```bash
keytool -genkeypair \
  -alias vertex-panel \
  -keyalg RSA -keysize 3072 \
  -validity 365 \
  -storetype PKCS12 \
  -keystore vertex-panel.p12 \
  -dname "CN=panel.example.com"
```

Copy the keystore to a protected path on the proxy host, then set `mode: HTTPS`, `https.enabled: true`, `https.keystore` to its absolute path, `https.keystore-password` to the password and `https.keystore-type: PKCS12`. A self-signed certificate may trigger browser warnings until the certificate is trusted. Restart Velocity after changing these values.

## In-game commands

These commands are registered at the Velocity proxy and are intended to work from any backend on the same network.

| Command | Purpose | Access |
|---|---|---|
| `/vstats` | Online player count, backend count, average player ping, uptime and JVM memory | Public |
| `/vservers` | Backend names and connected-player counts | Public |
| `/vplayers [backend]` | List connected players, optionally filtering by backend | `vertexvelocity.command.players` |
| `/vserver` | Show your current backend | Public for the sender's own backend |
| `/vserver <player>` | Show another player's backend and ping | `vertexvelocity.command.server` |
| `/vping` | Show your current proxy ping | Player only |
| `/vhelp` | List command help | Public |

Aliases are also registered, for example `/vertexstats`, `/vertexservers`, `/vertexplayers`, `/vertexserver`, `/vertexping` and `/vertexhelp`.

Grant the permission nodes through your permissions manager. For example, in LuckPerms, assign `vertexvelocity.command.players` or `vertexvelocity.command.server` to the appropriate group. Check command registration and permission behavior on your exact Velocity version before enabling it on a public network.

These commands only expose information available through Velocity: connected players, registered backends, player ping, JVM memory and proxy uptime. They do not query private plugin data such as Paper economy balances or claim statistics.

## REST API

Base URL:

```text
https://PANEL-HOST:PORT/api/v1
```

Responses are JSON. Use HTTPS for requests crossing an untrusted network. The full route reference and request details are in [`../../Downloads/vertex-velocity-web-panel-1.1.0-commercial-changes (1)/vertex-velocity-web-panel-1.1.0-changes-only/docs/API.md`](docs/API.md).

### Authentication routes

| Method | Route | Access / purpose |
|---|---|---|
| `GET` | `/health` | Public health/version response |
| `GET` | `/setup-status` | Public initial-setup status |
| `POST` | `/setup` | Public only while the first owner setup is pending; consumes the setup token |
| `POST` | `/login` | Username/password login; may return a TOTP challenge |
| `POST` | `/login/2fa` | Complete a pending login with a six-digit authenticator code |
| `POST` | `/logout` | Revoke current browser session |
| `GET` | `/me` | Current session identity, role and expiry |

### Read routes

| Method | Route | Purpose |
|---|---|---|
| `GET` | `/stats` | Current player count, backend count, uptime, JVM memory, HTTP error count, maintenance flag and recent events |
| `GET` | `/players?server=lobby&search=Alex&limit=100` | Currently connected players, optional backend/name filter, max 500 results |
| `GET` | `/players/{uuid}?limit=100` | Player profile and observed history for a UUID |
| `GET` | `/player-intelligence?search=Alex&limit=100` | Searchable historical player directory |
| `GET` | `/servers` | Registered backends, current player counts, latency and ping status |
| `GET` | `/servers/{name}` | Backend details and connected player list |
| `GET` | `/commands` | Proxy-wide command metadata and permission nodes |
| `GET` | `/network` | Topology snapshot and recent network-event summary |
| `GET` | `/activity?limit=250` | Recent activity feed |
| `GET` | `/events?limit=100` | Recent player/network events |
| `GET` | `/analytics?limit=240&events=100&days=7` | Stored samples, events, historical summaries and backend latency/availability aggregates |
| `GET` | `/maintenance` | Informational maintenance flag and message |
| `GET` | `/config` | Safe config view; secrets omitted; owner/admin session |
| `GET` | `/audit?limit=100` | Audit records; owner/admin session |
| `GET` | `/sessions` | Active session metadata; owner session only |
| `GET` | `/integrations/keys` | API key metadata; owner session only |
| `GET` | `/2fa` | Current account TOTP status; browser session |
| `GET` | `/routing` | Current fallback order; owner/admin session |
| `GET` | `/users` | User metadata; owner session only |

### Write/action routes

These routes require a browser session with the relevant role. API keys are read-only and cannot call them.

| Method | Route | Access / purpose |
|---|---|---|
| `PUT` | `/config` | Owner updates supported configuration values |
| `POST` | `/players/{uuid}/disconnect` | Owner/admin/moderator disconnects a currently connected player |
| `POST` | `/players/{uuid}/transfer` | Owner/admin/moderator requests a transfer to a registered backend |
| `PUT` | `/maintenance` | Owner/admin changes the informational maintenance flag |
| `POST` | `/users` | Owner creates a panel user |
| `POST` | `/users/{username}` | Owner enables/disables a panel account |
| `POST` | `/2fa/setup` | Current user creates a pending TOTP secret |
| `POST` | `/2fa/enable` | Confirm pending TOTP secret with a current six-digit code |
| `POST` | `/2fa/disable` | Disable TOTP after password + current code verification |
| `PUT` | `/routing` | Owner updates fallback order in native `velocity.toml` |
| `POST` | `/integrations/keys` | Owner creates a read-only API key; raw key is returned once |
| `DELETE` | `/integrations/keys/{id}` | Owner revokes an API key |
| `DELETE` | `/sessions/{id}` | Owner revokes a web session |

All route names above are relative to `/api/v1`. Check [`../../Downloads/vertex-velocity-web-panel-1.1.0-commercial-changes (1)/vertex-velocity-web-panel-1.1.0-changes-only/docs/API.md`](docs/API.md) for authentication requirements and the exact JSON shape. If a route returns `401`, verify authentication; if it returns `403`, verify role/HTTP method; if it returns `404`, verify the path, UUID, backend name or resource ID.

### Example: query the proxy stats

```bash
curl --fail-with-body \
  "$VERTEX_URL/api/v1/stats" \
  -H "Authorization: Bearer $VERTEX_API_KEY"
```

### Example: filter players by backend

```bash
curl --get --fail-with-body \
  "$VERTEX_URL/api/v1/players" \
  --data-urlencode 'server=lobby' \
  --data-urlencode 'limit=50' \
  -H "Authorization: Bearer $VERTEX_API_KEY"
```

### Example: get a specific backend

```bash
curl --fail-with-body \
  "$VERTEX_URL/api/v1/servers/lobby" \
  -H "Authorization: Bearer $VERTEX_API_KEY"
```

Backend names and player UUIDs must be URL-encoded when building request paths dynamically. Do not concatenate arbitrary user-provided text into a URL without encoding it.

## API authentication and key management

1. Sign in as the owner.
2. Open **API & Integrations**.
3. Create a key with a descriptive name, for example `Discord status bot`.
4. Copy the returned key immediately. The panel cannot display the raw value again.
5. Set it as an environment variable on the external application.
6. Revoke and replace it immediately if it is exposed.

The server stores a SHA-256 hash of each API key, not the raw key. Keys use the `vv_live_` prefix and are **read-only**: authenticated API-key requests are limited to `GET`. They cannot change settings, transfer or disconnect players, modify routing, change maintenance state, create users or manage sessions. Use a browser owner/admin/moderator session for the supported administrative endpoints.

Never:

- commit an API key, setup token, keystore password or webhook URL to Git;
- paste a real secret into a public issue or screenshot;
- expose API keys in frontend JavaScript, embeds or client-side applications;
- log authorization headers or full request objects containing secrets;
- use plain HTTP for secrets over a public network.

For local administration, a loopback address can be appropriate. For remote access, configure HTTPS and use a trusted certificate.


## REST API clients

The REST API is language-neutral and can be consumed by any HTTP client that supports JSON and bearer-token authentication. Keep API keys on trusted server-side applications, store them in environment variables or a secrets manager, use HTTPS for remote access, and apply request timeouts. The API reference is available in [`../../Downloads/vertex-velocity-web-panel-1.1.0-commercial-changes (1)/vertex-velocity-web-panel-1.1.0-changes-only/docs/API.md`](docs/API.md).

## Security model

### Roles

- **OWNER** — account administration, API-key management, configuration and owner-only actions.
- **ADMINISTRATOR** — selected operational views/actions such as audit, configuration view, maintenance and player operations where allowed by the endpoint.
- **MODERATOR** — supported player transfer/disconnect actions.
- **VIEWER** — read-only panel access.
- **API key** — a separate read-only machine credential, not a user role that can mutate data.

Every protected endpoint must enforce permissions server-side; hiding a button in the UI is not an authorization boundary. Review the endpoint permissions in [`../../Downloads/vertex-velocity-web-panel-1.1.0-commercial-changes (1)/vertex-velocity-web-panel-1.1.0-changes-only/docs/API.md`](docs/API.md) and assign each account only the access it needs.

### TOTP two-factor authentication

Use **Security Center** to create a pending TOTP secret, add it to an authenticator app, and confirm it with a current six-digit code. When enabled, password login returns a short-lived challenge that must be completed with the current code. Disabling TOTP requires the current password and current authenticator code.

- There are no recovery codes in this version.
- TOTP secrets are stored in the plugin SQLite database; protect the plugin data directory and its backups.
- Keep access to the authenticator app before enabling TOTP on the owner account.
- Test login, clock drift and disabling on a staging instance before relying on it.

### Sessions, passwords and audit

The session cookie is HttpOnly and SameSite=Strict; the Secure flag is added for a direct HTTPS connection. Sessions are held in memory and expire according to configuration. A proxy restart clears in-memory sessions. The owner can revoke listed sessions from the Security Center. Password hashes use salted PBKDF2-HMAC-SHA256.

### Network security

- Use HTTPS for external access.
- Keep `bind-address: 127.0.0.1` when the panel should only be accessed through a local reverse proxy.
- Restrict the panel port using firewall/provider controls.
- Treat Discord webhooks as secrets; anyone with the webhook URL may be able to post to that channel.
- Back up SQLite and configuration securely; database access can expose user records, API key hashes, audit history and TOTP secrets.
- Keep the plugin and dependency versions under review; scan dependencies and source before public deployment.

## Data, analytics and monitoring limitations

- **No retroactive history:** player history begins when the plugin starts recording events. Earlier sessions cannot be reconstructed.
- **Estimated playtime:** durations are inferred from recorded login/disconnect pairs. A crash, forced process termination or missing event can make totals incomplete.
- **Retention applies:** old metric/backend samples and events may be pruned according to `retention-days`.
- **Backend health is a ping:** a successful Velocity ping does not prove every game feature or plugin on that backend is healthy.
- **Sampled latency:** latency/availability are derived from periodic probes; a brief outage between samples can be missed.
- **Event-based transfers:** the current transfer history observes server-connected events; it may not reliably infer every source-to-destination pair.
- **Polling, not push:** the UI refreshes through HTTP polling. WebSocket and Server-Sent Events are not implemented.
- **Proxy-visible data only:** backend-specific economy, permissions, claims, inventories and plugin internals are not queried by this plugin.
- **Maintenance is informational:** it does not currently block Minecraft logins.
- **Fallback edits require restart:** the routing editor writes the native `try` array in `velocity.toml`; restart Velocity to apply the new connection-attempt order. Backend add/edit/delete operations update the native file and Velocity's live server registry, but should still be tested on staging before production use.
- **Configuration restore requires restart:** restore replaces `velocity.toml` from a timestamped snapshot and deliberately does not silently reconcile a running proxy; restart to apply the restored full configuration.

## Troubleshooting

### Panel does not open

1. Check the Velocity console for `Vertex Web Panel HTTP listening on ...` or the HTTPS equivalent.
2. Confirm the configured port and bind address in `plugins/vertexvelocitywebpanel/config.yml`.
3. Confirm the matching Pterodactyl allocation is attached to the Velocity server, not a Paper backend.
4. Check provider firewall/security group rules and host port conflicts.
5. If bound to `127.0.0.1`, remote clients cannot connect directly; use a trusted reverse proxy or change the bind intentionally.
6. Confirm the URL scheme: `http://` for HTTP, `https://` for HTTPS.

### Port bind failure / `Address already in use`

Another process is already listening on the port, or a second panel instance is configured to use it. Select an unused allocated port, update the plugin configuration and restart Velocity. Do not change the Minecraft port as a workaround.

### Plugin fails to load

1. Read the first Java exception and its `Caused by` chain in the Velocity console.
2. Verify the artifact was built using JDK 25 and `mvn clean package` completed successfully.
3. Verify the target Velocity version is compatible with `velocity-api:4.3.0` in `pom.xml`.
4. Confirm that you uploaded the shaded JAR from `target/`, not a source ZIP or unshaded artifact.
5. Check for duplicate old JARs in `plugins/` and remove only after backing up.

### Login or setup fails

- Ensure the first-owner setup has not already been completed in the existing database.
- Use the token printed in the Velocity console only during initial setup.
- Check the proxy console for SQLite permission, disk-full or schema errors.
- Wait for the rate-limit window if login attempts have been exhausted.
- If TOTP is enabled, check the authenticator's clock synchronization and enter the current six-digit code.
- If the owner has lost access to the authenticator, there are no built-in recovery codes; recover the database/configuration securely and use a controlled recovery process rather than deleting files blindly.

### API returns `401` or `403`

- `401 Unauthorized`: verify the base URL, API key spelling and whether the key was revoked. Copy the key from its creation response; it cannot be shown again.
- `403 Forbidden`: API keys are read-only; use the correct browser role for administrative routes.
- Confirm the request header is exactly `Authorization: Bearer YOUR_KEY`.
- Do not include `/api/v1` in `VERTEX_URL`, because the sample clients append it.


### Backend appears `CHECKING` or `OFFLINE`

- `CHECKING` means a first completed ping has not yet been recorded.
- Confirm the backend is listed under the proxy's `servers` configuration and its address/port is correct.
- Verify network reachability from the Velocity host/container to that backend.
- Check the timeout value and inspect Velocity logs for connection errors.
- Remember that a ping probe is not a full gameplay health check.

### Fallback editor does not save or routing does not change

- Verify the plugin can read and write the proxy-root `velocity.toml`.
- Check that the `[servers]` section and backend names match the registered Velocity server names.
- The editor creates `velocity.toml.vertex-backup` before writing. Inspect that backup and compare the file before restarting.
- Restart Velocity to apply the saved `try` order. The editor does not dynamically reload configuration.
- Work from a backup and review configuration changes before applying them to a production proxy.

### SQLite / disk problems

- Ensure the plugin data directory is writable and the disk has free space.
- Stop Velocity before copying the database for a consistent backup.
- Do not delete `vertex-panel.sqlite` as a generic troubleshooting step; it contains users, API key metadata, history, audit records and TOTP data.

## Upgrade and backup

Before updating:

1. Stop Velocity cleanly.
2. Back up the complete plugin data directory `plugins/vertexvelocitywebpanel/` and current `velocity.toml`.
3. Keep a copy of the previous JAR.
4. Upload the new shaded JAR and remove only obsolete duplicate plugin JARs.
5. Start the proxy and inspect the logs before exposing the panel.
6. Recheck the API, login, TOTP, in-game commands and routing page.

For a database backup, stop Velocity and copy `vertex-panel.sqlite` plus relevant configuration files to a protected location. Encrypt backups if they leave the host. A `velocity.toml.vertex-backup` is created by the fallback editor, but it is not a full plugin/database backup.


## Project documentation

- [REST API reference](docs/API.md)
- [Security and setup](docs/SECURITY_AND_SETUP.md)
- [Python REST client](examples/python/vertex_client.py)

## Contributing / reporting a bug

When reporting an issue, include the plugin version, Java version, Velocity version, the exact endpoint or command, sanitized logs and steps to reproduce. Remove API keys, setup tokens, session cookies, passwords, webhook URLs and private player information before sharing logs. 
