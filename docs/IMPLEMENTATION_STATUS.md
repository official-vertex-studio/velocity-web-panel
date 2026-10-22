# Implementation status — 2026-10-22

## Source implemented in this milestone

- Velocity plugin entry point and lifecycle hooks.
- Target dependency declared as `com.velocitypowered:velocity-api:4.3.0` with Java 25 release.
- Generated YAML config with HTTP / HTTPS / BOTH modes, bind address, ports and keystore settings.
- Embedded HTTP and HTTPS listeners using JDK `HttpServer` / `HttpsServer`.
- SQLite database schema for users, settings, player events, metric samples and audit logs.
- First-run owner setup using a one-time console token.
- PBKDF2-HMAC-SHA256 password hashing and in-memory cookie sessions.
- Dashboard UI, live player list, server registry view, event history, metrics graph, account screen and audit screen.
- REST endpoints for health, setup, login/logout, stats, players, transfer, disconnect, servers, analytics, config view/edit, users, audit and an informational maintenance flag.
- Backend ping sampling using Velocity's registered-server ping API.

## Incomplete or requiring validation

- Real build against Velocity API 4.3.0 and Java 25; this container currently has Java 21 and no Maven executable.
- Real proxy launch / in-game tests. No claim is made that the source compiles or runs until those checks are performed.
- Automated tests for TOTP enrollment/login/disable, API-key authorization, role boundaries, session revocation, and native TOML editing.
- Native fallback editing changes only the `try` array in the existing `velocity.toml`; it does not edit server addresses or dynamically reload the proxy. Review the generated backup and restart Velocity.
- TOTP secrets are stored in the plugin SQLite database; restrict access to the plugin data directory and database backups. There are no recovery codes yet.
- Backup/restore workflow for the panel database beyond the `velocity.toml.vertex-backup` file created by routing edits.
- Enforced maintenance mode for Minecraft login events and bypass permissions.
- Formal independent security review, HTTPS certificate tests, and load tests.
- Real-time push transport; the UI polls the REST API. WebSocket/SSE is not implemented.

## Required next validation steps

1. Build with JDK 25+ and Maven against the configured repository.
2. Fix all compile errors using the actual 4.3.0 API.
3. Start a clean Velocity 4.3.0 proxy with a test backend.
4. Verify plugin loading, first-run setup, login/logout, cookies, roles and HTTPS.
5. Join with a real Minecraft client and verify the live player count, server ping and transfer/kick actions.
6. Restart the proxy and verify SQLite persistence and config behavior.
7. Add automated tests for setup token one-time use, password verification, session expiry and endpoint authorization.
8. Test TOTP, API-key permissions, session revocation and `velocity.toml` backup/restore on a staging proxy.
9. Implement the remaining specification modules and repeat the tests before calling the release complete.


## Live Network Map and Live Activity Center update (2026-10-22)

Added to the source tree:

- A dedicated Network Map page with an SVG topology view, proxy/backend nodes, backend health state, connected player counts, measured ping latency, node detail popups, backend overview, and a recent network-event list.
- A dedicated Activity Center page with text search, event-type filter, server filter, time-range filter, paginated-by-limit history loading, event detail text, timestamps, automatic refresh through the existing five-second refresh loop, and CSV export.
- Authenticated `GET /api/v1/network` and `GET /api/v1/activity?limit=N` endpoints.
- Backend connectivity state transitions are written to the existing SQLite event table, so a status change remains in history after restart. Initial backend checks are also recorded as initial state events.

Important scope/verification notes:

- Live updates use authenticated HTTP polling every five seconds, not WebSocket push yet.
- Network topology links currently show the proxy-to-backend topology. The transfer/event summaries use observed `SERVER_SWITCH` events; source-to-destination pairs are not available from the current event listener, so the map does not claim to visualize exact player travel paths.
- Connectivity reflects Velocity backend ping results, not a guarantee that every game feature on the backend is healthy.
- This environment has Java 21 and no Maven executable, while the project targets Java 25 / Velocity API 4.3.0. A real Maven compile, dependency resolution, and live Velocity integration test could not be run here.


## Player Intelligence and Advanced Analytics update (2026-10-22)

Added to the source tree:

- Historical player directory searchable by username or UUID, showing observed logins, estimated connection frequency per week, estimated total playtime, average completed session length, transfer count and last-seen timestamp.
- Player profile endpoint and UI with UUID, first/last observed timestamps, full-retention aggregate session estimates, visited backends, transfer/error counts, and recent event history.
- Browser CSV export for the player directory and selected player history.
- Profile actions for currently connected players, with disconnect/transfer role enforcement on the server. Administrative disconnects, transfer requests, and asynchronous transfer exceptions are recorded as player events where observable.
- Historical analytics with reporting periods of 1, 7, 30, 90 or 365 days, peak and average sampled concurrency, unique observed players, connections/disconnections, transfer counts, period-over-period connection changes, average completed session duration, and hourly/daily/monthly timeline buckets.
- SQLite storage for backend ping samples, including latency and success/failure state, and per-backend average/min/max latency and sample-based availability percentage.
- Retention cleanup for backend ping samples.

Data-quality notes:

- Player history only includes events observed since plugin installation and within configured retention.
- Session durations are estimates paired from recorded LOGIN and DISCONNECT events; sessions spanning restarts or missing events may be undercounted.
- Error counts include only events explicitly recorded with error/failed types; Velocity does not expose every possible backend or client failure through the current listeners.
- Backend availability is based on successful ping samples, not a full gameplay health check.
- The panel still polls the API every five seconds; WebSocket push and a full production test suite remain outstanding.
- JavaScript syntax was checked with Node.js and `PanelDatabase.java` was compiled with the available JDK 21 as a syntax-level check. This is not a build of the complete project against Velocity API 4.3.0.

## Premium panel / settings update (2026-10-22)

- Expanded the English admin UI with a dedicated Security Center page and grouped settings for listener mode, bind address, HTTP/HTTPS ports, TLS keystore settings, session lifetime, login rate limits, data retention and sample interval.
- The settings API validates supported values and saves them to the plugin `config.yml`; secret keystore passwords are not returned in the config API response. Listener/TLS/bind changes explicitly require a full Velocity restart.
- Added role-aware navigation for settings, security/audit and owner-only account management.
- Added deployment/security documentation for first-launch password setup, HTTP limitations, HTTPS keystore configuration and reverse-proxy recommendations.
- Validation in this workspace: browser JavaScript syntax check and ZIP integrity check. A complete Maven build with JDK 25 and a real Velocity runtime test remain unperformed; do not treat this source update as a verified production JAR.


## External API keys and Discord notifications update (2026-10-22)

Added in source: owner-only read-only API key creation/listing/revocation endpoints. Keys are generated with a cryptographically secure random token, only SHA-256 hashes are persisted in SQLite, the raw key is returned only at creation, and API-key authentication is denied for non-GET requests. API-key management actions are written to the audit table.

Added in source: optional Discord webhook delivery for proxy startup, backend state transitions, and newly detected high-latency transitions. Webhook delivery runs asynchronously, has connection/read timeouts, and accepts only official HTTPS Discord webhook URL prefixes.

The follow-up source update adds TOTP enrollment and login challenge handling, a Security Center workflow for TOTP and session revocation, an API & Integrations screen, and a native `velocity.toml` fallback-order editor with a backup. Automated security tests and live validation are still outstanding.

Validation status: source-level edits were made, but the complete project has not been built with JDK 25 and tested on a live Velocity proxy in this environment. API-key and webhook behavior must be exercised on a staging proxy before production.


## Premium interaction polish update (2026-10-22)

- Added a collapsible sidebar with accessible button labels and a mobile-friendly collapse behavior.
- Added shimmering skeleton-style loading for dashboard statistic cards before the first successful stats response.
- Preserved reduced-motion preferences and existing value/card transitions.
- Added read-only external API key management and owner-only REST session revocation in the same source milestone.

Validation: inline browser JavaScript passes `node --check`; `PanelDatabase.java` compiles with the available JDK 21 as a syntax check. Maven is not installed in this environment and only Java 21 is available, while the project targets Java 25; the full plugin build and runtime behavior remain unverified.


## TOTP, routing and integrations UI update (2026-10-22)

Added to source:

- RFC 6238 TOTP support using HMAC-SHA1, six-digit codes, 30-second steps and a ±1-step validation window. Enrollment remains pending until a valid code is confirmed. Login creates a five-minute challenge after password verification; disabling TOTP requires both password and current TOTP code.
- Security Center controls for TOTP enrollment/status/disable and listing/revoking active web sessions.
- API & Integrations page for owner-only API key creation/list/revocation, one-time key display, and Node.js / Python / cURL quick starts. Added standalone SDK examples for Node.js, discord.js v14, Python, Go, C# and Java.
- Routing editor reads the `try` array from the proxy-root `velocity.toml`, validates names against registered Velocity backends, writes a `.vertex-backup` copy, and updates only the `try` array. Restart is required.
- Additional premium animation polish: staggered card entrances, purple glow, shimmer, transitions, and `prefers-reduced-motion` handling.

Validation performed: inline JavaScript passes `node --check`; the TOTP helper compiles with the available JDK 21; `javac` found no Java structural/syntax errors when parsing the project. Full Maven compilation and dependency resolution were not possible because Maven is not installed and this environment has JDK 21 while the project targets Java 25. No live Velocity/Pterodactyl test has been performed. Treat this as a development source update, not a production-verified release.


## Proxy commands and expanded SDKs

The source now registers proxy-wide `/vstats`, `/vservers`, `/vplayers`, `/vserver`, `/vping`, and `/vhelp` commands with `/vertex...` aliases. The REST API includes `/commands`, `/servers/{name}`, and filtering for `/players?server=&search=&limit=`. Additional examples cover PHP, Ruby, Rust, PowerShell, and Kotlin alongside Go, Java, Python, Node.js/Discord.js, and C#. These additions have not been verified in a full Java 25 Maven build or live Velocity runtime in this environment.
