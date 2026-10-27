# Code architecture

The Java code is grouped by responsibility:

- `VertexVelocityWebPanel.java` — Velocity lifecycle, dependency wiring and proxy event handlers. A dedicated `bootstrap` package remains a possible future extraction, not a current package.
- `commands` — Velocity proxy-wide commands.
- `config` — validated configuration and configuration migration.
- `db` — SQLite persistence and schema operations.
- `security` — password hashing, TOTP and authentication primitives.
- `service` — business logic independent from HTTP transport (maintenance state is persisted by `MaintenanceService`).
- `web/WebPanelServer.java` — embedded JDK HTTP/HTTPS listener, routing, response serialization and API authorization.

## Runtime configuration

The JAR's `src/main/resources/defaults/` files are templates only. Runtime settings belong in the plugin data directory. Do not edit resources inside the JAR after packaging. Existing `config.yml` remains the source for listener settings; `maintenance.yml` stores maintenance state and UUID whitelist. `motd.yml` is loaded by `MotdService` and applied to Velocity ping responses. `tls.yml` supplies extra search roots for automatic TLS asset discovery. The listener selects HTTP/HTTPS at startup; changing listener ports or TLS assets requires a restart. PEM discovery validates certificate dates and verifies that an unencrypted PKCS#8 private key matches the certificate. ACME issuance/renewal is integrated through Certbot HTTP-01 when explicitly enabled; this depends on Certbot being installed in the runtime and public TCP/80 reaching the challenge handler. TOTP recovery codes are implemented.

## API maintenance routes

- `GET /api/v1/maintenance` — read maintenance state (subject to normal API authentication).
- `PUT /api/v1/maintenance` — update state and messages; OWNER or ADMINISTRATOR browser session required.
- `POST /api/v1/maintenance/whitelist/{uuid}` — add a UUID; OWNER or ADMINISTRATOR browser session required.
- `DELETE /api/v1/maintenance/whitelist/{uuid}` — remove a UUID; OWNER or ADMINISTRATOR browser session required.

Changes are persisted atomically in `maintenance.yml` and audit logged. During maintenance, a new connection is allowed only when whitelist enforcement is enabled and its UUID is on the whitelist. Disabling whitelist enforcement therefore blocks all new connections. If `allow-existing-players` is true, an already-connected UUID can pass a duplicate/reconnect pre-login check; `kick-existing-players` disconnects connected players who are no longer allowed when maintenance settings or whitelist membership change.


## 4.0.1 implementation layout

```text
src/main/java/fr/vertexvelocity/webpanel/
├── VertexVelocityWebPanel.java       # Velocity lifecycle and proxy events
├── commands/
│   ├── ProxyCommands.java            # Built-in proxy-wide commands
│   └── CommandAliasService.java       # Configurable Velocity aliases
├── config/PanelConfig.java           # YAML-backed settings and validation
├── db/PanelDatabase.java             # SQLite persistence, metrics and audit
├── security/
│   ├── PasswordUtil.java
│   └── Totp.java
├── service/
│   ├── MaintenanceService.java       # State, messages and UUID whitelist
│   ├── MotdService.java              # Normal/maintenance server-list MOTD
│   └── TlsCertificateDetector.java   # TLS material discovery and validation
└── web/WebPanelServer.java           # Embedded JDK HTTP/HTTPS server + API routes

src/main/resources/
├── defaults/config.yml               # Main config template
├── defaults/maintenance.yml          # Maintenance defaults
├── defaults/motd.yml                 # MOTD profiles
├── defaults/tls.yml                  # TLS search-path defaults
└── web/index.html                    # Embedded dashboard
```

The `defaults/*.yml` files are the single source of truth for newly generated configuration files. Runtime configuration is copied to the plugin data directory and persisted there; the JAR resources themselves are not edited at runtime.

## Management API additions

- `GET /api/v1/status` — authenticated combined snapshot of stats, MOTD, maintenance, TLS detection and server count.
- `GET /api/v1/motd` and `GET /api/v1/motd/preview` — current MOTD configuration and selected profile.
- `PUT /api/v1/motd` — validate, atomically persist and apply MOTD configuration immediately (owner/admin browser session or a `READ_WRITE` API key).
- `GET /api/v1/maintenance` and `GET /api/v1/maintenance/status` — maintenance state, UUID whitelist size and online counts.
- `PUT /api/v1/maintenance` — update maintenance state and messages; if configured, immediately disconnect connected players who are no longer allowed.
- `POST /api/v1/maintenance/whitelist` with `{ "uuid": "..." }`, or `{ "playerName": "..." }` for an online player.
- `POST` / `DELETE /api/v1/maintenance/whitelist/{uuid}` — add/remove a UUID.
- `POST /api/v1/aliases/reload` — reload aliases from `aliases.yml`.

All management endpoints require authentication. API keys have explicit `READ` or `READ_WRITE` scope; write-capable keys are intended for automation and should be kept separate from owner credentials.

## TLS discovery

`web-panel.mode: AUTO` searches the plugin `tls/` folder, plugin data folder, `./tls`, common Let's Encrypt and system TLS directories, plus extra directories in `tls.yml`. Supported PEM pairs include `fullchain.pem` + `privkey.pem`, `tls.crt` + `tls.key` and other documented names. Existing PKCS12/PFX/JKS keystores are also checked. A valid PEM certificate does not require a keystore password. AUTO chooses HTTPS when a valid unexpired asset is found; otherwise it falls back to HTTP with a warning. When `acme.enabled: true`, the plugin also integrates with Certbot for initial HTTP-01 issuance, scheduled renewal checks and HTTPS listener reload. See the README for DNS, port-forwarding, Certbot installation and permission requirements.

## Validation status and manual acceptance tests

The source archive integrity is not equivalent to a successful Java build. Before production deployment, run `mvn clean verify` with JDK 25 and test the exact Velocity runtime. At minimum verify:

1. Start with maintenance disabled; an arbitrary UUID can connect.
2. Enable maintenance with whitelist enforcement enabled; a non-whitelisted UUID is denied and a whitelisted UUID is allowed.
3. Disable whitelist enforcement while maintenance is enabled; new connections are denied.
4. Enable `kick-existing-players`, remove a connected player's UUID, and confirm the player is disconnected.
5. Change MOTD using the API; confirm the next Minecraft server-list ping uses the new profile and that `motd.yml` survives restart.
6. Use a `READ` API key against a write route (expect 403), then use `READ_WRITE` against an explicitly supported write route (expect success), and verify owner-only routes remain denied.
7. Test TLS with a valid PEM pair, a mismatched key, an expired certificate, an encrypted keystore without a password and no certificate at all. AUTO must never report HTTPS active unless the HTTPS listener started successfully.
8. Test alias reload with valid, invalid, reserved and colliding command names.
