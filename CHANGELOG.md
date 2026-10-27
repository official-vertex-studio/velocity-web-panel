# Changelog

## 4.0.0 — Runtime packaging and release baseline (2026-10-27)

- Bump Maven artifact and Velocity plugin metadata to `4.0.0`.
- Add an explicit Spring Boot 4 MVC runtime dependency to the shaded plugin package, targeting the reported missing `WebMvcWebApplicationTypeDeducer` class.
- Make HTTP the default listener mode and disable HTTPS by default for initial setup.
- Correct the `/api/v1/health` version field to report `4.0.0`.
- Add a release checklist and Git tag workflow guide.

**Validation status:** source/static checks only in the preparation environment. Maven/Java 25 compilation and a real Velocity startup must pass before publishing this release.

# Changelog

## 3.0.0 — Velocity API compatibility and consolidation (2026-10-27)

- Updated Maven artifact version and Velocity plugin metadata to `3.0.0`.
- Fixed MOTD ping construction to use the Velocity `ServerPing.Builder` methods `onlinePlayers`, `maximumPlayers`, and `samplePlayers` instead of the unavailable `players(ServerPing.Players)` method.
- Fixed command aliases to use the asynchronous `CommandManager.executeAsync` API and handle its completion failure.
- Preserved player-count display, optional player sample behavior, MOTD description, and favicon when rebuilding the ping response.
- This source archive has been statically reviewed for the two reported API mismatches and packaged. It has **not** been compiled with JDK 25/Maven or integration-tested against a running Velocity proxy in this environment; run `mvn -U clean verify` before deploying.


## 2.0.0 ACME reliability update

- Store Certbot state, logs, work files and issued certificates inside the plugin data directory instead of hard-coding `/etc/letsencrypt`, reducing unnecessary root-path assumptions in containers.
- Use the same explicit Certbot state directories for issuance and renewal.
- Attempt to restore the previous HTTPS listener if loading a renewed certificate fails.
- Clarify Pterodactyl, DNS, HTTP-01, port allocation and end-to-end validation requirements. ACME remains dependent on an installed Certbot executable and external network configuration.


## Unreleased — maintenance/configuration refactor

- Added `MaintenanceService` to isolate maintenance state and persist it in the plugin data directory.
- Added UUID-only maintenance whitelist operations and audit-log entries through the existing authenticated API.
- Added default MOTD, maintenance and TLS configuration templates under `src/main/resources/defaults/`.
- Added `docs/ARCHITECTURE.md` to describe package responsibilities and current implementation boundaries.

### Not yet complete

- Maintenance connection blocking and MOTD ping application are not wired into Velocity events in this change.
- At this earlier maintenance-refactor stage, ACME issuance/renewal was not implemented; it was added in a later change and is now integrated through Certbot, subject to the validation checklist below.
- The archive has not been Maven-compiled in this environment because Maven is unavailable. Treat this as a source patch requiring a local `mvn clean verify` and staging test, not a production-ready release.

## Unreleased — command aliases
- Added configurable Velocity command aliases loaded from `plugins/vertexvelocitywebpanel/aliases.yml`.
- Added permission checks, optional player-only execution, argument/player placeholders, input validation and registration diagnostics.
- Existing built-in `/vstats`, `/vservers`, `/vplayers`, `/vserver`, `/vping` and `/vhelp` commands remain unchanged.
- Aliases are loaded at proxy startup; restart Velocity after editing `aliases.yml`.

## 2.0.0 — functional services follow-up
- Connected the persistent maintenance service to Velocity pre-login checks; non-whitelisted connections are denied while maintenance and whitelist enforcement are enabled.
- Connected the configurable `motd.yml` profile to Velocity ping responses, with separate normal and maintenance MOTDs.
- Added authenticated `GET /api/v1/motd` and owner-only `PUT /api/v1/motd` endpoints; changes are persisted and applied without restarting the proxy.
- Added TLS keystore auto-discovery for `keystore.p12` / `keystore.jks` in the plugin data directory and `tls/` subdirectory. The password remains explicitly configured; this is not automatic ACME/Let's Encrypt issuance or PEM certificate conversion.
- Fixed stats output to read the persistent maintenance service rather than a removed in-memory field.

## 2.0.0 implementation completion pass (2026-10-26)

- Added persisted `READ` and `READ_WRITE` API-key scopes with automatic SQLite schema migration. Read-only keys are restricted to GET requests; write-scoped keys use administrator-level authorization and cannot access owner-only routes.
- Added panel controls to edit and save the maintenance state, denial message, whitelist enforcement, and UUID whitelist. Maintenance mode is enforced in Velocity's pre-login event.
- Added panel JSON editors for normal/maintenance MOTD configuration and custom Velocity command aliases.
- Added alias configuration validation, immediate registration/reload, `/valiasreload`, and REST endpoints for viewing, updating, and reloading aliases.
- Added API documentation for the maintenance, MOTD, alias, and API-key scope endpoints.
- Validation performed in this environment: inline panel JavaScript passes `node --check`; edited Java files pass delimiter-balance checks; ZIP integrity is checked after packaging. A full Maven/Java 25 build and live Velocity integration test remain necessary because this environment has Java 21 and no Maven installation.


## 2.0.0 — MOTD, maintenance, TLS and API hardening pass

- Moved the new-install main configuration template to `src/main/resources/defaults/config.yml` so runtime defaults are resource-backed rather than embedded as a Java text block.
- Added TLS asset discovery from common PEM pairs and PKCS12/PFX/JKS keystores, a generated `tls.yml` search-path template, automatic `AUTO` protocol selection, TLS status API and HTTP fallback if auto-detected HTTPS cannot initialize.
- Improved maintenance YAML loading, atomic state updates, explicit online-player kicking, UUID whitelist checks, online-name whitelist convenience endpoint and maintenance status API.
- Expanded MOTD preview/status endpoints and enabled scoped `READ` / `READ_WRITE` API keys for supported MOTD, maintenance and alias-reload operations.
- Documented the source tree, runtime resources, TLS discovery and management API.
- Validation available in this environment: YAML parse checks, inline JavaScript syntax check and Java parser pass without syntax diagnostics. Full Maven compilation and Velocity integration tests remain pending because this environment has JDK 21 and no Maven, while the project targets JDK 25.

## 2.0.0 implementation hardening — 2026-10-26

- Maintenance now denies new connections whenever maintenance is enabled and the UUID is not explicitly allowed; disabling whitelist enforcement no longer accidentally allows everyone.
- `kick-existing-players` is re-applied after maintenance policy updates and whitelist changes, so removing an allowed UUID can immediately disconnect that player when configured.
- Maintenance whitelist add/remove and YAML writes roll back in-memory changes on persistence errors and use atomic file replacement where supported.
- MOTD API updates validate a candidate snapshot and persist atomically before swapping the live configuration, avoiding partial in-memory changes on invalid input or disk errors.
- TLS API status now distinguishes an actually active HTTPS listener from a merely detected certificate.
- Architecture documentation updated with maintenance semantics and live validation scenarios.

Validation in this environment: bundled YAML files parsed successfully and inline dashboard JavaScript passed `node --check`. Full Maven/JDK 25 compilation and live Velocity integration tests remain unperformed because Maven and JDK 25 are not available here.
