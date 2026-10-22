export class VertexClient {
  constructor({ baseUrl, apiKey, timeoutMs = 5000 }) {
    if (!baseUrl || !apiKey) throw new Error('Set VERTEX_URL and VERTEX_API_KEY before creating VertexClient');
    this.baseUrl = baseUrl.replace(/\/$/, '');
    this.apiKey = apiKey;
    this.timeoutMs = timeoutMs;
  }

  async request(path) {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), this.timeoutMs);
    try {
      const response = await fetch(`${this.baseUrl}/api/v1${path}`, {
        headers: { Authorization: `Bearer ${this.apiKey}`, Accept: 'application/json' },
        signal: controller.signal,
      });
      const payload = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(`Vertex API ${response.status}: ${payload.error ?? response.statusText}`);
      return payload;
    } finally {
      clearTimeout(timeout);
    }
  }

  health() { return fetch(`${this.baseUrl}/api/v1/health`).then(async r => { if (!r.ok) throw new Error(`Health check failed: ${r.status}`); return r.json(); }); }
  stats() { return this.request('/stats'); }
  players({ server, search, limit = 100 } = {}) { const q = new URLSearchParams(); if (server) q.set('server', server); if (search) q.set('search', search); q.set('limit', String(limit)); return this.request(`/players?${q}`); }
  playerProfile(uuid, limit = 100) { return this.request(`/players/${encodeURIComponent(uuid)}?limit=${encodeURIComponent(limit)}`); }
  playerIntelligence(search = '', limit = 100) { return this.request(`/player-intelligence?${new URLSearchParams({search, limit: String(limit)})}`); }
  servers() { return this.request('/servers'); }
  server(name) { return this.request(`/servers/${encodeURIComponent(name)}`); }
  commands() { return this.request('/commands'); }
  network() { return this.request('/network'); }
  analytics({ days = 7, limit = 240, events = 100 } = {}) { return this.request(`/analytics?${new URLSearchParams({ days: String(days), limit: String(limit), events: String(events) })}`); }
  activity({ limit = 100 } = {}) { return this.request(`/activity?limit=${encodeURIComponent(limit)}`); }
  events({ limit = 100 } = {}) { return this.request(`/events?limit=${encodeURIComponent(limit)}`); }
  maintenance() { return this.request('/maintenance'); }
}
