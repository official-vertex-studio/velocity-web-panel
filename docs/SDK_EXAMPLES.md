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
- Node.js: `examples/node/vertex-client.mjs` (complete helper)
- Go: `examples/go/main.go`
- Java: `examples/java/VertexApiExample.java`
- C#/.NET: `examples/dotnet/Program.cs`
- PHP: `examples/php/vertex.php`
- Ruby: `examples/ruby/vertex.rb`
- Rust: `examples/rust/`
- PowerShell: `examples/powershell/vertex.ps1`
- Kotlin: `examples/kotlin/VertexApiExample.kt`

Each language uses standard HTTPS requests with `Authorization: Bearer ...`. In languages where the example is intentionally minimal, use the same request helper pattern and change the path/query to call any route listed above. API keys use `READ` or `READ_WRITE` scope. `READ` is limited to GET requests; `READ_WRITE` can call write endpoints that allow the Administrator role. Owner-only routes still require an owner web session.
