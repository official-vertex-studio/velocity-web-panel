<?php
$base = rtrim(getenv('VERTEX_URL') ?: '', '/');
$key = getenv('VERTEX_API_KEY') ?: '';
if (!$base || !$key) { fwrite(STDERR, "Set VERTEX_URL and VERTEX_API_KEY\n"); exit(2); }
$url = $base . '/api/v1/stats';
$ctx = stream_context_create(['http' => ['method' => 'GET', 'timeout' => 5, 'ignore_errors' => true, 'header' => "Authorization: Bearer {$key}\r\nAccept: application/json\r\n"]]);
$body = file_get_contents($url, false, $ctx);
$status = $http_response_header[0] ?? '';
if ($body === false || !preg_match('/\s2\d\d\s/', $status)) { fwrite(STDERR, "Vertex API request failed: {$status}\n"); exit(1); }
$data = json_decode($body, true, 512, JSON_THROW_ON_ERROR);
printf("Online players: %d\nBackends: %d\n", $data['onlinePlayers'] ?? 0, $data['backendCount'] ?? 0);
