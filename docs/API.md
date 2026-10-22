# REST API reference

Base path: `/api/v1`. JSON is used for request and response bodies. Limits are clamped server-side. Use HTTPS outside a trusted local network.

## Authentication

- `GET /health` and `GET /setup-status` are public.
- `POST /setup` consumes the one-time console setup token to create the first owner.
- `POST /login` creates an `HttpOnly`, `SameSite=Strict` session cookie named `vv_session`, or returns `202` with a short-lived challenge when TOTP is enabled.
- `POST /login/2fa` completes the challenge with a six-digit TOTP code.
- `POST /logout` invalidates the current session.
- Browser/session-authenticated endpoints use the cookie. External applications should use an owner-created API key in `Authorization: Bearer vv_live_...`.
- API keys are **read-only**: they are accepted only for GET requests. They must not be placed in client-side browser code.

## API key management (OWNER only)

### `POST /integrations/keys`

Request:

```json
{"name":"Discord bot"}
```

Response (the raw key is shown once):

```json
{"ok":true,"name":"Discord bot","apiKey":"vv_live_...","warning":"Copy this key now. It cannot be displayed again."}
```

### `GET /integrations/keys`

Lists metadata only: `id`, `name`, `createdBy`, `createdAt`, `lastUsed`, `revoked`. Raw keys and hashes are never returned.

### `DELETE /integrations/keys/{id}`

Revokes a key. The next request using it will fail authentication. A key cannot be un-revoked; create a new one instead.

## Read endpoints

| Method | Route | Purpose |
|---|---|---|
| GET | `/health` | Public health and version info |
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
| GET | `/commands` | Proxy-wide in-game commands and their permission nodes |
| GET | `/network` | Proxy/backend topology snapshot and recent transfer-event summary |
| GET | `/activity?limit=250` | Retained event history for the activity center |
| GET | `/analytics?limit=240&events=100&days=7` | Stored samples, events, historical summary, timeline and backend latency/availability aggregates |
| GET | `/events?limit=100` | Recent player/network events |
| GET | `/audit?limit=100` | Audit events (OWNER/ADMINISTRATOR session) |
| GET | `/config` | Safe config view (OWNER/ADMINISTRATOR session; secrets are omitted) |
| GET | `/maintenance` | Informational maintenance state |
| GET | `/2fa` | Current account TOTP status (session) |
| GET/POST | `/routing` | Read/write the fallback order in native `velocity.toml` (OWNER writes; ADMINISTRATOR may read) |

## Mutating endpoints (session required; API keys are denied)

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
| PUT | `/routing` | OWNER; updates the native Velocity `try` fallback array and creates `velocity.toml.vertex-backup` |
| GET/POST | `/users` | OWNER |
| POST | `/users/{username}` | OWNER; disable/enable account |
| PUT | `/maintenance` | OWNER, ADMINISTRATOR; informational only |

## TOTP and routing

Enable TOTP from **Security Center**. The panel shows a Base32 secret for manual entry in an authenticator app; confirm it with a valid code before TOTP becomes mandatory at the next login. Disabling TOTP requires the current password and code. Keep access to your authenticator and secure the plugin SQLite database because it stores the TOTP secret.

The routing page edits only the `try` array in the `[servers]` section of `velocity.toml`. The first item becomes the preferred initial/fallback server. It writes a `velocity.toml.vertex-backup` copy before saving. Restart Velocity to apply changes. Verify the path and backup before using this feature on a production proxy. It does not alter backend addresses.

## Examples

### Get current stats with a read-only key

```bash
curl "$VERTEX_PANEL_URL/api/v1/stats" \
  -H "Authorization: Bearer $VERTEX_PANEL_API_KEY"
```

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

### Discord bot usage

Call the Node.js helper from a scheduled task or a Discord slash-command handler, then format only the fields needed for the response. Keep the API key in the bot's environment variables and never expose it in embeds or logs. Respect Discord's interaction response deadline and defer long-running commands before making network calls.

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

Examples are provided in Node.js/Discord.js, Go, Java, Python, C#, PHP, Ruby, Rust, PowerShell, and Kotlin under `examples/`. They use the same language-neutral HTTPS REST API and a read-only bearer API key. Set `VERTEX_URL` to the panel base URL and `VERTEX_API_KEY` to an API key created by the owner in **API & Integrations**. Never embed keys in a public client or commit them to source control.

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
| Discord.js | `examples/discordjs/status-command.js` | reusable status embed; import into a bot command |
| Go, Java, C#/.NET, PHP, Ruby, Rust, PowerShell, Kotlin | `examples/` language folders | authenticated HTTP/JSON examples; call additional routes by changing the `/api/v1/...` path in the request |

API keys are read-only. A request using an API key to a write endpoint must not be used for player actions, routing changes, maintenance changes, account management, or configuration updates.

## Python Discord bot (`discord.py`) walkthrough

The complete sample is `examples/python/discord_bot.py`; it registers `/minecraft` and `/minecraft_players`. It uses `examples/python/vertex_client.py`.

1. Create a read-only API key in the panel's **API & Integrations** page. Copy it once and keep it private.
2. Install Python 3.10 or newer and run `python -m pip install -U discord.py requests`.
3. In the Discord Developer Portal, create a bot and invite it with the `bot` and `applications.commands` scopes. Keep the bot token private.
4. Set environment variables on the machine/container that runs the bot:

```bash
# Linux / Pterodactyl startup environment
export DISCORD_TOKEN='your-discord-bot-token'
export VERTEX_URL='https://panel.example.com'
export VERTEX_API_KEY='vv_live_your_read_only_key'
python examples/python/discord_bot.py
```

On Windows PowerShell, use `$env:DISCORD_TOKEN='...'`, `$env:VERTEX_URL='https://panel.example.com'`, and `$env:VERTEX_API_KEY='...'`, then run `python examples/python/discord_bot.py`. Set these as panel/container environment variables in production rather than committing them to a file. `VERTEX_URL` is the panel origin only; the SDK adds `/api/v1` itself. Use HTTPS for remote access.

5. Once the bot logs in and syncs slash commands, run `/minecraft` for an overview or `/minecraft_players` to privately view the online player list. The example defers its interaction before making HTTP requests so Discord's response deadline is respected.
6. If commands do not appear, check that the bot was invited with `applications.commands`, that the bot is online, and that command sync succeeded. If the API returns HTTP 401, create a new key or verify the environment variables; if it returns HTTP 403, ensure the key is being used only for supported read-only routes.

The sample intentionally sends generic errors to Discord so it does not disclose API keys, raw headers, or private panel details. Check server-side bot logs when troubleshooting, but do not log the key.
