"""Vertex Velocity Web Panel read-only REST client. Requires: pip install requests"""
import os
from urllib.parse import urlencode, quote
import requests

class VertexClient:
    def __init__(self, base_url=None, api_key=None, timeout=5):
        self.base_url = (base_url or os.environ["VERTEX_URL"]).rstrip("/")
        self.api_key = api_key or os.environ["VERTEX_API_KEY"]
        self.timeout = timeout
        self.session = requests.Session()
        self.session.headers.update({"Authorization": f"Bearer {self.api_key}", "Accept": "application/json"})

    def get(self, path, params=None):
        response = self.session.get(f"{self.base_url}/api/v1{path}", params=params, timeout=self.timeout)
        response.raise_for_status()
        return response.json()

    def health(self): return self.get("/health")
    def stats(self): return self.get("/stats")
    def players(self, server=None, search=None, limit=100):
        return self.get("/players", {k:v for k,v in {"server":server,"search":search,"limit":limit}.items() if v is not None})
    def player_profile(self, uuid, limit=100): return self.get(f"/players/{quote(uuid, safe='')}", {"limit":limit})
    def player_intelligence(self, search="", limit=100): return self.get("/player-intelligence", {"search":search,"limit":limit})
    def servers(self): return self.get("/servers")
    def server(self, name): return self.get(f"/servers/{quote(name, safe='')}")
    def commands(self): return self.get("/commands")
    def network(self): return self.get("/network")
    def activity(self, limit=100): return self.get("/activity", {"limit":limit})
    def events(self, limit=100): return self.get("/events", {"limit":limit})
    def analytics(self, days=7, limit=240, events=100): return self.get("/analytics", {"days":days,"limit":limit,"events":events})
    def maintenance(self): return self.get("/maintenance")

if __name__ == "__main__":
    client = VertexClient()
    stats = client.stats()
    print(f"Online players: {stats.get('onlinePlayers', '?')}")
    print(f"Backends: {stats.get('backendCount', '?')}")
    for server in client.servers():
        print(f"{server.get('name')}: {server.get('health')} · {server.get('playerCount', 0)} players")
