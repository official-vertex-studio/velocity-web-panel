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

The JAR's `src/main/resources/defaults/` files are templates only. Runtime settings belong in the plugin data directory. Do not edit resources inside the JAR after packaging. Existing `config.yml` remains the source for listener settings; `maintenance.yml` stores maintenance state and UUID whitelist. `motd.yml` is loaded by `MotdService` and applied to Velocity ping responses. When HTTPS is enabled, the listener automatically checks Certbot lineages under `/etc/letsencrypt/live`, validates the certificate chain, hostname lineage, file permissions and private-key match, and otherwise reuses or generates a self-signed fallback keystore. Renewed Certbot files are detected every 30 seconds and hot-reloaded for new TLS connections. Certbot's own system timer/cron remains responsible for certificate issuance and renewal; the plugin does not implement ACME issuance. Changing listener mode, bind address or port requires a restart. TOTP recovery codes are implemented.

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
└── web/index.html                    # Embedded dashboard
```

The `defaults/*.yml` files are the single source of truth for newly generated configuration files. Runtime configuration is copied to the plugin data directory and persisted there; the JAR resources themselves are not edited at runtime.

## Management API additions

- `GET /api/v1/status` — authenticated combined snapshot of stats, MOTD, maintenance, panel protocol and server count.
- `GET /api/v1/motd` and `GET /api/v1/motd/preview` — current MOTD configuration and selected profile.
- `PUT /api/v1/motd` — validate, atomically persist and apply MOTD configuration immediately (owner/admin browser session only).
- `GET /api/v1/maintenance` and `GET /api/v1/maintenance/status` — maintenance state, UUID whitelist size and online counts.
- `PUT /api/v1/maintenance` — owner/admin browser session only; update maintenance state and messages and, if configured, immediately disconnect connected players who are no longer allowed.
- `POST /api/v1/maintenance/whitelist` with `{ "uuid": "..." }`, or `{ "playerName": "..." }` for an online player (owner/admin browser session only).
- `POST` / `DELETE /api/v1/maintenance/whitelist/{uuid}` — add/remove a UUID.
- `POST /api/v1/aliases/reload` — reload aliases from `aliases.yml`.

All management endpoints require authentication. API keys have explicit `READ` or `READ_WRITE` scope. `READ_WRITE` keys do not inherit administrator identity and are limited to backend probes, alert acknowledgements and the predefined safe Command Center actions (`probe_backends`, `reload_aliases`, `database_check`). Configuration changes and player moderation require an authorized interactive browser session.

## TLS certificate handling

Set `web-panel.mode: HTTPS`. The plugin scans `/etc/letsencrypt/live/*/` for Certbot `fullchain.pem` and `privkey.pem`, validates certificate dates, issuer chain and Java trust path, verifies the private-key match, and checks the SAN against the Certbot lineage name or canonical host name. With one lineage, its directory name must match a certificate SAN; with multiple lineages, the machine canonical hostname must match a SAN to disambiguate them, otherwise the plugin uses its fallback. If no usable Certbot certificate is found, the plugin reuses `tls/keystore.p12` or generates a self-signed fallback.

The Certbot systemd timer/cron job performs renewal. The plugin checks the files every 30 seconds and updates the reloadable TLS key manager for new connections without restarting Velocity. The Velocity service user must be able to read the Certbot private key; the plugin does not weaken host permissions. A self-signed fallback encrypts traffic but browsers will show a trust warning.

## Login protection

Login and initial setup use per-IP throttles. Login also uses per-account and global request limits, progressive temporary blocks after repeated failures, dummy PBKDF2 work for unknown usernames, and a semaphore to cap concurrent password hashing. TOTP failures contribute to the same progressive IP/account blocking state.

## Validation status and manual acceptance tests

The source archive integrity is not equivalent to a successful Java build. Before production deployment, run `mvn clean verify` with JDK 25 and test the exact Velocity runtime. At minimum verify:

1. Start with maintenance disabled; an arbitrary UUID can connect.
2. Enable maintenance with whitelist enforcement enabled; a non-whitelisted UUID is denied and a whitelisted UUID is allowed.
3. Disable whitelist enforcement while maintenance is enabled; new connections are denied.
4. Enable `kick-existing-players`, remove a connected player's UUID, and confirm the player is disconnected.
5. Change MOTD using the API; confirm the next Minecraft server-list ping uses the new profile and that `motd.yml` survives restart.
6. Use a `READ` API key against a write route (expect 403), then use `READ_WRITE` against an explicitly supported write route (expect success), and verify owner-only routes remain denied.
7. Test TLS with one matching Certbot lineage, multiple unrelated lineages, a SAN mismatch, a mismatched private key, an expired certificate, unreadable private-key permissions, a renewed Certbot symlink target and no Certbot certificate. Verify that renewed files take effect on new connections without restarting Velocity and that the fallback is reported as self-signed.
8. Test alias reload with valid, invalid, reserved and colliding command names.
