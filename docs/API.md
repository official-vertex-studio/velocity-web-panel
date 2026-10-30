# REST API reference

Base path: `/api/v1`. JSON is used for request and response bodies. Limits are clamped server-side. Use HTTPS outside a trusted local network.

## Authentication

- `GET /health` and `GET /setup-status` are public.
- `POST /setup` consumes the one-time console setup token to create the first owner.
- `POST /login` creates an `HttpOnly`, `SameSite=Strict` session cookie named `vv_session`, or returns `202` with a short-lived challenge when TOTP is enabled.
- `POST /login/2fa` completes the challenge with a six-digit TOTP code.
- `POST /logout` invalidates the current session.
- Browser/session-authenticated endpoints use the cookie. External applications should use an owner-created API key in `Authorization: Bearer vv_live_...`.
- API keys have `READ` or `READ_WRITE` scope. `READ` keys can only use read methods permitted to viewers. `READ_WRITE` keys do not inherit the Administrator role: they are limited to backend probes, alert acknowledgement and three predefined safe Command Center actions. Configuration edits, player moderation and account administration require an interactive browser session. Never place keys in client-side browser code.

## API key management (OWNER only)

### `POST /integrations/keys`

Request:

```json
{"name":"Trusted integration","scope":"READ"}
```

Response (the raw key is shown once):

```json
{"ok":true,"name":"Trusted read-only client","apiKey":"vv_live_...","scope":"READ","warning":"Copy this key now. It cannot be displayed again."}
```

### `GET /integrations/keys`

Lists metadata only: `id`, `name`, `createdBy`, `createdAt`, `lastUsed`, `revoked`, and `scope`. Raw keys and hashes are never returned.

### `DELETE /integrations/keys/{id}`

Revokes a key. The next request using it will fail authentication. A key cannot be un-revoked; create a new one instead.

## Read endpoints

| Method | Route | Purpose |
|---|---|---|
| GET | `/health` | Public health and version info |
| GET | `/status` | Authenticated combined stats, MOTD, maintenance and TLS detection snapshot |
| GET | `/tls` | Authenticated active listener mode and detected certificate metadata |
| GET | `/motd` | Read normal/maintenance MOTD profiles |
| GET | `/motd/preview` | Read the selected MOTD profile based on maintenance state |
| GET | `/setup-status` | Whether initial setup is required |
| GET | `/me` | Current account identity, role and expiry |
| GET | `/sessions` | Active web sessions (OWNER only; token values are never exposed) |
| DELETE | `/sessions/{id}` | Revoke a session by the 16-character ID returned by `/sessions` (OWNER only) |
| GET | `/stats` | Online player count, backend count, uptime, JVM memory, HTTP error counter, maintenance flag and latest events |
| GET | `/players?server=lobby&search=Alex&limit=100` | Connected players; optional backend filter, username substring and limit (max 500) |
| GET | `/player-intelligence?search=...&limit=100` | Historical player directory based on collected events |
| GET | `/players/{uuid}?limit=100` | Player profile, estimated playtime, observed sessions, visited backends and recent history |
| GET | `/servers` | Registered backends, connected players, ping status and latency |
| GET | `/servers/{name}` | Detailed backend status and its connected players |
| GET | `/servers/config` | Read configured backend entries from the proxy-root `velocity.toml` |
| POST | `/servers` | OWNER; add a backend to `velocity.toml` and register it in the live proxy. JSON: `name`, `host`, `port` |
| PUT | `/servers/{name}` | OWNER; update a backend entry and live registry. JSON: `name`, `host`, `port`; refuses edits while players are connected |
| DELETE | `/servers/{name}` | OWNER; remove a backend entry and live registry; refuses removal while players are connected or when it would remove the only fallback |
| GET | `/velocity-config/backups` | List up to 20 timestamped `velocity.toml` snapshots |
| POST | `/velocity-config/backups/restore` | OWNER; JSON `filename`; restores a snapshot to disk and requires a proxy restart |
| GET | `/commands` | Proxy-wide in-game commands and their permission nodes |
| GET | `/network` | Proxy/backend topology snapshot and recent transfer-event summary |
| GET | `/activity?limit=250` | Retained event history for the activity center |
| GET | `/analytics?limit=240&events=100&days=7` | Stored samples, events, historical summary, timeline and backend latency/availability aggregates |
| GET | `/events?limit=100` | Recent player/network events |
| GET | `/audit?limit=100` | Audit events (OWNER/ADMINISTRATOR session) |
| GET | `/config` | Safe config view (OWNER/ADMINISTRATOR session; secrets are omitted) |
| GET | `/maintenance` | Maintenance state and UUID whitelist |
| GET | `/maintenance/status` | Maintenance state plus online/whitelisted-online counts and active MOTD profile |
| GET | `/2fa` | Current account TOTP status (session) |
| GET/POST | `/routing` | Read/write the fallback order in native `velocity.toml` (OWNER writes; ADMINISTRATOR may read) |

## Mutating endpoints (interactive browser session required, except the limited `READ_WRITE` routes listed below)

| Method | Route | Access |
|---|---|---|
| POST | `/setup` | Public only while initial owner setup is pending |
| POST | `/login` | Public, rate-limited |
| POST | `/logout` | Current session |
| POST | `/players/{uuid}/disconnect` | OWNER, ADMINISTRATOR, MODERATOR |
| POST | `/players/{uuid}/transfer` | OWNER, ADMINISTRATOR, MODERATOR |
| PUT | `/config` | OWNER |
| POST | `/2fa/setup` | Current session; create a pending TOTP secret |
| POST | `/2fa/enable` | Current session; confirm TOTP secret with a six-digit code |
| POST | `/2fa/disable` | Current session; requires account password and current TOTP code |
| PUT | `/routing` | OWNER; updates the native Velocity `try` fallback array, creates backups and requires a proxy restart to apply |
| POST | `/servers` | OWNER; add backend in native config and live registry |
| PUT | `/servers/{name}` | OWNER; update backend config and live registry; no connected players allowed |
| DELETE | `/servers/{name}` | OWNER; remove backend config and live registry; no connected players or last-fallback removal allowed |
| POST | `/velocity-config/backups/restore` | OWNER; restore a timestamped Velocity config snapshot; proxy restart required |
| GET/POST | `/users` | OWNER |
| POST | `/users/{username}` | OWNER; disable/enable account |
| PUT | `/maintenance` | OWNER/ADMINISTRATOR interactive session |
| PUT | `/motd` | OWNER/ADMINISTRATOR interactive session |
| POST | `/maintenance/whitelist` | OWNER/ADMINISTRATOR interactive session; JSON `uuid` or online `playerName` |
| POST/DELETE | `/maintenance/whitelist/{uuid}` | OWNER/ADMINISTRATOR interactive session |
| POST | `/aliases/reload` | OWNER/ADMINISTRATOR interactive session; API keys may invoke this through the safe Command Center allowlist only |

## TOTP and routing

Enable TOTP from **Security Center**. The panel shows a Base32 secret for manual entry in an authenticator app; confirm it with a valid code before TOTP becomes mandatory at the next login. Disabling TOTP requires the current password and code. Keep access to your authenticator and secure the plugin SQLite database because it stores the TOTP secret.

The Servers page can add, edit, and delete backend entries in the `[servers]` section of the proxy-root `velocity.toml`. The operation validates names, host and port; preserves unrelated text; creates `velocity.toml.vertex-backup` plus timestamped snapshots; and updates Velocity's live server registry. Editing/deleting a backend is refused while it has connected players. Deletion is also refused if it would remove the only fallback entry. Test these operations on a staging proxy before production use. The routing editor changes only the `try` array; restart Velocity to apply routing-order changes. Restoring a whole-file snapshot also requires a restart.

## Examples

### Get current stats with a read-only key

```bash
curl "$VERTEX_PANEL_URL/api/v1/stats" \
  -H "Authorization: Bearer $VERTEX_PANEL_API_KEY"
```

### Safe operations using a READ_WRITE key

The supported write scope is intentionally narrow. For example, request a backend health probe:

```bash
curl -X POST "$VERTEX_PANEL_URL/api/v1/monitoring/probe" \
  -H "Authorization: Bearer $VERTEX_PANEL_WRITE_KEY" \
  -H "Content-Type: application/json" \
  -d '{}'
```

A `READ_WRITE` key may also acknowledge alerts or invoke only `probe_backends`, `reload_aliases`, and `database_check` through `/command-center`. It cannot edit MOTD, maintenance, aliases configuration, backend registry or routing.

### Node.js helper

```js
export async function vertexGet(path) {
  const response = await fetch(`${process.env.VERTEX_PANEL_URL}/api/v1${path}`, {
    headers: { Authorization: `Bearer ${process.env.VERTEX_PANEL_API_KEY}` },
    signal: AbortSignal.timeout(5000),
  });
  if (!response.ok) throw new Error(`Vertex API HTTP ${response.status}`);
  return response.json();
}
```

## Data semantics

- UUID/player history exists only from the time the plugin started collecting events.
- Playtime/session durations are estimates from observed login/disconnect pairs.
- Backend availability is computed from ping samples, not a complete gameplay probe.
- `SERVER_SWITCH` events identify the destination backend but may not provide a reliable previous-backend pair.
- `latencyMs` can be absent while a backend has not completed its first ping.
- The API currently uses HTTP polling. It does not expose a WebSocket/SSE stream.


## Proxy-wide in-game commands

The plugin registers these commands with Velocity, so they are handled at the proxy and can be used while players are connected to any registered backend. No separate Paper plugin is required for these commands.

| Command | Purpose | Permission |
|---|---|---|
| `/vstats` | Global online count, backend count and average player ping | Public |
| `/vservers` | Backend names and connected-player counts | Public |
| `/vplayers [backend]` | Online player list, optionally filtered to a backend | `vertexvelocity.command.players` |
| `/vserver` | Shows your current backend | Public |
| `/vserver <player>` | Shows another player's backend and ping | `vertexvelocity.command.server` |
| `/vping` | Your current proxy ping | Public |
| `/vhelp` | Lists commands | Public |

Each command has a `vertex...` alias, for example `/vertexstats`. Grant permission nodes through your permissions manager. The commands report proxy-visible data; they do not read private plugin data from every backend.

## More language examples

Language-neutral client examples are provided under `examples/`. They use the same REST API and default to a read-only bearer API key. Set `VERTEX_URL` to the panel base URL and `VERTEX_API_KEY` to a key created by the owner in **API & Integrations**. Never embed keys in a public client or commit them to source control.

Examples of filtered requests:

```bash
curl --get "$VERTEX_URL/api/v1/players" \
  --data-urlencode 'server=lobby' --data-urlencode 'limit=50' \
  -H "Authorization: Bearer $VERTEX_API_KEY"

curl "$VERTEX_URL/api/v1/servers/lobby" \
  -H "Authorization: Bearer $VERTEX_API_KEY"

curl "$VERTEX_URL/api/v1/commands" \
  -H "Authorization: Bearer $VERTEX_API_KEY"
```


## Complete SDK method map

All SDKs call the same language-neutral REST routes; there is no separate API implementation per language. The generic authenticated GET helper can call any read-only route. The helper methods provided in the SDK examples cover:

| SDK | Example location | Included operations |
|---|---|---|
| Python | `examples/python/vertex_client.py` | health, stats, players and filters, player profile, player directory, servers/details, commands, network, activity, events, analytics, maintenance |
| Node.js | `examples/node/vertex-client.mjs` | health, stats, players and filters, player profile, player directory, servers/details, commands, network, activity, events, analytics, maintenance |
| Go, Java, C#/.NET, PHP, Ruby, Rust, PowerShell, Kotlin | `examples/` language folders | authenticated HTTP/JSON examples; call additional routes by changing the `/api/v1/...` path in the request |

API keys have a scope: `READ` allows safe read requests available to viewers; `READ_WRITE` allows only the explicit safe-operation allowlist documented above. Neither API-key scope provides owner or administrator identity. Owner-only and administrator-only endpoints remain browser-session-only. Use the least-privilege scope and revoke exposed keys immediately.


### MOTD, maintenance, and command aliases

- `GET /api/v1/motd` — view the server-list MOTD configuration.
- `PUT /api/v1/motd` — owner-only; save normal and maintenance MOTD lines.
- `GET /api/v1/maintenance` — view maintenance state and UUID whitelist.
- `PUT /api/v1/maintenance` — owner/administrator; update enabled state, messages, whitelist enforcement and UUID whitelist.
- `POST /api/v1/maintenance/whitelist/{uuid}` — owner/administrator; add a valid player UUID.
- `DELETE /api/v1/maintenance/whitelist/{uuid}` — owner/administrator; remove a UUID.
- `GET /api/v1/aliases` — owner/administrator; inspect alias configuration.
- `PUT /api/v1/aliases` — owner-only; validate, save and register alias definitions immediately.
- `POST /api/v1/aliases/reload` — owner/administrator; reload `aliases.yml`.

Command aliases are executed by Velocity with the original command source, so Velocity permission checks on the target command still apply. `/valiasreload` is also available in-game/console to reload `aliases.yml`.


## Monitoring and Operations (4.2.0+)

| Method | Endpoint | Access | Purpose |
|---|---|---|---|
| GET | `/api/v1/monitoring` | Authenticated viewer or higher | Backend health, JVM usage and alert counts |
| GET | `/api/v1/alerts?status=OPEN` | OWNER, ADMINISTRATOR, MODERATOR, VIEWER or READ_WRITE API key | Read persisted alerts |
| POST | `/api/v1/alerts/{id}/ack` | OWNER/ADMINISTRATOR session or READ_WRITE API key | Acknowledge one alert |
| POST | `/api/v1/alerts/ack-all` | OWNER/ADMINISTRATOR session or READ_WRITE API key | Acknowledge all open alerts |
| GET/PUT | `/api/v1/monitoring/config` | OWNER/ADMINISTRATOR read; OWNER write | Read/update latency, cooldown and alert-history policy |
| POST | `/api/v1/monitoring/probe` | OWNER/ADMINISTRATOR session or READ_WRITE API key | Request fresh backend pings |
| GET | `/api/v1/diagnostics` | OWNER/ADMINISTRATOR interactive session | Sanitized JVM, SQLite and listener diagnostics |
| GET | `/api/v1/diagnostics/export` | OWNER/ADMINISTRATOR interactive session | Download sanitized JSON support bundle |
| GET | `/api/v1/search?q=...` | Authenticated | Search players, known profiles, backends and recent events |
| GET/POST | `/api/v1/command-center` | OWNER/ADMINISTRATOR interactive session; READ_WRITE key may POST only allowlisted actions | Safe operations; no arbitrary console or shell execution |
| GET | `/api/v1/exports/*.csv` | Authenticated, except audit export requires OWNER/ADMINISTRATOR | Export metrics, events, player directory, backend metrics and audit data |
| POST/GET | `/api/v1/backups/database` | OWNER interactive session | Create/list consistent SQLite snapshots |
| GET | `/api/v1/backups/database/{filename}` | OWNER interactive session | Download a SQLite snapshot |
| GET | `/api/v1/openapi.json` | Authenticated | OpenAPI 3.1 API specification |

The safe Command Center supports only `probe_backends`, `reload_aliases`, and `database_check`. It intentionally does not run arbitrary Velocity console commands, operating-system commands, or scripts. API keys cannot execute player moderation actions; those require an interactive panel account.

SQLite snapshots are stored under the plugin data directory in `database-backups/`. Download and store backups off-host. To restore manually, stop Velocity first, preserve the current database, then replace `vertex-panel.sqlite` with a verified snapshot while the process is stopped. Never overwrite a live SQLite database file.


## Version 4.3.0 — API key scope hardening and support bundle

`READ` keys may call GET/HEAD/OPTIONS endpoints that are available to the Viewer role. `READ_WRITE` keys do **not** become administrator accounts: they may only call these write operations:

- `POST /api/v1/monitoring/probe` — submit backend health probes.
- `POST /api/v1/alerts/ack-all` and `POST /api/v1/alerts/{id}/ack` — acknowledge alerts.
- `POST /api/v1/command-center` — call only `probe_backends`, `reload_aliases`, or `database_check`.

Configuration changes, player moderation, backend changes, maintenance/MOTD changes, user management, session administration, API-key management, database backups, and audit access require the appropriate interactive browser account.

`GET /api/v1/diagnostics/export` is available to interactive owners and administrators and downloads a sanitized JSON support bundle. It excludes credentials, cookies, API keys, webhook URLs, TLS private keys, filesystem paths and backend addresses. Review the bundle before sharing it with a third party.
