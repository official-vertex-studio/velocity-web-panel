# SDK examples

The REST API is shared across all supported languages. Set `VERTEX_URL` to the panel origin (for example `https://panel.example.com`) and `VERTEX_API_KEY` to a read-only key created in **API & Integrations**. Do not append `/api/v1` to `VERTEX_URL`; SDKs add it. Use HTTPS for remote connections.

## Routes supported by the API

- `GET /api/v1/health`
- `GET /api/v1/stats`
- `GET /api/v1/players?server=lobby&search=Alex&limit=50`
- `GET /api/v1/players/{uuid}?limit=100`
- `GET /api/v1/player-intelligence?search=Alex&limit=100`
- `GET /api/v1/servers` and `GET /api/v1/servers/{name}`
- `GET /api/v1/commands`, `/network`, `/activity?limit=100`, `/events?limit=100`, `/analytics?days=7&limit=240&events=100`, `/maintenance`

## SDKs

- Python: `examples/python/vertex_client.py` (complete helper)
- Python Discord bot: `examples/python/discord_bot.py` (`discord.py`, `/minecraft`, `/minecraft_players`)
- Node.js: `examples/node/vertex-client.mjs` (complete helper)
- Discord.js v14 embed helper: `examples/discordjs/status-command.js`
- Go: `examples/go/main.go`
- Java: `examples/java/VertexApiExample.java`
- C#/.NET: `examples/dotnet/Program.cs`
- PHP: `examples/php/vertex.php`
- Ruby: `examples/ruby/vertex.rb`
- Rust: `examples/rust/`
- PowerShell: `examples/powershell/vertex.ps1`
- Kotlin: `examples/kotlin/VertexApiExample.kt`

Each language uses standard HTTPS requests with `Authorization: Bearer ...`. In languages where the example is intentionally minimal, use the same request helper pattern and change the path/query to call any route listed above. API keys are read-only and cannot perform administrative mutations.

## Python Discord bot setup

See the step-by-step guide in `docs/API.md` under **Python Discord bot walkthrough**. Short version:

```bash
python -m pip install -U discord.py requests
export DISCORD_TOKEN='...'
export VERTEX_URL='https://panel.example.com'
export VERTEX_API_KEY='vv_live_...'
python examples/python/discord_bot.py
```

Use PowerShell `$env:NAME='value'` syntax on Windows. Never commit tokens or API keys to Git.
