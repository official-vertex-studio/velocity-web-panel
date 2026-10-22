import { VertexClient } from './vertex-client.mjs';

const vertex = new VertexClient({
  baseUrl: process.env.VERTEX_URL,
  apiKey: process.env.VERTEX_API_KEY,
});

const [stats, servers, players] = await Promise.all([
  vertex.stats(), vertex.servers(), vertex.players(),
]);
console.log(`Online players: ${stats.onlinePlayers}`);
console.table(servers.map(s => ({ name: s.name, state: s.health, players: s.playerCount, latency: s.latencyMs })));
console.log(players.map(p => `${p.username} (${p.server})`).join(', '));
