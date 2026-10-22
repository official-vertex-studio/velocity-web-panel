using System.Net.Http.Headers;
using System.Net.Http.Json;

var baseUrl = (Environment.GetEnvironmentVariable("VERTEX_URL") ?? throw new Exception("VERTEX_URL is required")).TrimEnd('/');
var apiKey = Environment.GetEnvironmentVariable("VERTEX_API_KEY") ?? throw new Exception("VERTEX_API_KEY is required");
using var client = new HttpClient { BaseAddress = new Uri(baseUrl), Timeout = TimeSpan.FromSeconds(5) };
using var request = new HttpRequestMessage(HttpMethod.Get, "/api/v1/servers");
request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", apiKey);
using var response = await client.SendAsync(request);
response.EnsureSuccessStatusCode();
var servers = await response.Content.ReadFromJsonAsync<List<Server>>() ?? new();
foreach (var server in servers) Console.WriteLine($"{server.Name}: {server.Health}, {server.PlayerCount} players");

public sealed class Server
{
    public string Name { get; set; } = "";
    public string Health { get; set; } = "UNKNOWN";
    public int PlayerCount { get; set; }
}
