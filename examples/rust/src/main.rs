use serde_json::Value;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let base = std::env::var("VERTEX_URL")?.trim_end_matches('/').to_string();
    let key = std::env::var("VERTEX_API_KEY")?;
    let response = reqwest::Client::new()
        .get(format!("{base}/api/v1/stats"))
        .bearer_auth(key)
        .timeout(std::time::Duration::from_secs(5))
        .send().await?.error_for_status()?;
    let stats: Value = response.json().await?;
    println!("Online players: {}", stats["onlinePlayers"]);
    println!("Backends: {}", stats["backendCount"]);
    Ok(())
}
