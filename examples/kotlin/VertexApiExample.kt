import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

fun main() {
    val base = System.getenv("VERTEX_URL")?.trimEnd('/')
        ?.takeIf { it.isNotBlank() } ?: error("VERTEX_URL environment variable is required")
    val key = System.getenv("VERTEX_API_KEY")?.takeIf { it.isNotBlank() }
        ?: error("VERTEX_API_KEY environment variable is required")
    val request = HttpRequest.newBuilder(URI.create("$base/api/v1/stats"))
        .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer $key")
        .header("Accept", "application/json").GET().build()
    val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
    check(response.statusCode() in 200..299) { "Vertex API HTTP ${response.statusCode()}" }
    println(response.body())
}
