import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class VertexApiExample {
    public static void main(String[] args) throws Exception {
        String configuredBase = System.getenv("VERTEX_URL");
        String key = System.getenv("VERTEX_API_KEY");
        if (configuredBase == null || configuredBase.isBlank() || key == null || key.isBlank()) {
            throw new IllegalStateException("Set VERTEX_URL and VERTEX_API_KEY environment variables");
        }
        String base = configuredBase.replaceAll("/$", "");
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/api/v1/stats"))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + key)
                .header("Accept", "application/json")
                .GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new IllegalStateException("Vertex API returned HTTP " + response.statusCode());
        System.out.println(response.body());
    }
}
