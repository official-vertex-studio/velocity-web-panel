if ([string]::IsNullOrWhiteSpace($env:VERTEX_URL) -or [string]::IsNullOrWhiteSpace($env:VERTEX_API_KEY)) { throw 'Set VERTEX_URL and VERTEX_API_KEY first.' }
$base = $env:VERTEX_URL.TrimEnd('/')
$key = $env:VERTEX_API_KEY
$headers = @{ Authorization = "Bearer $key"; Accept = 'application/json' }
$stats = Invoke-RestMethod -Method Get -Uri "$base/api/v1/stats" -Headers $headers -TimeoutSec 5
Write-Host "Online players: $($stats.onlinePlayers)"
Write-Host "Backends: $($stats.backendCount)"
