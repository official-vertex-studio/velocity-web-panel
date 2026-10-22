package main

import (
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"strings"
	"time"
)

func vertexGet(path string, target any) error {
	base := strings.TrimRight(os.Getenv("VERTEX_URL"), "/")
	key := os.Getenv("VERTEX_API_KEY")
	if base == "" || key == "" {
		return fmt.Errorf("set VERTEX_URL and VERTEX_API_KEY environment variables")
	}
	req, err := http.NewRequest(http.MethodGet, base+"/api/v1"+path, nil)
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+key)
	req.Header.Set("Accept", "application/json")
	client := &http.Client{Timeout: 5 * time.Second}
	res, err := client.Do(req)
	if err != nil {
		return err
	}
	defer res.Body.Close()
	if res.StatusCode < 200 || res.StatusCode >= 300 {
		return fmt.Errorf("Vertex API returned %s", res.Status)
	}
	return json.NewDecoder(res.Body).Decode(target)
}

func main() {
	var stats map[string]any
	if err := vertexGet("/stats", &stats); err != nil {
		panic(err)
	}
	fmt.Printf("Online players: %v\n", stats["onlinePlayers"])
}
