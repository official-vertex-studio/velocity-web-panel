// Example for discord.js v14. Register the slash command separately in your bot.
// Store VERTEX_URL and VERTEX_API_KEY in environment variables, never in source control.
const { EmbedBuilder } = require('discord.js');

async function createVertexStatusEmbed() {
  if (!process.env.VERTEX_URL || !process.env.VERTEX_API_KEY) throw new Error('Set VERTEX_URL and VERTEX_API_KEY');
  const base = process.env.VERTEX_URL.replace(/\/$/, '');
  const response = await fetch(`${base}/api/v1/servers`, {
    headers: { Authorization: `Bearer ${process.env.VERTEX_API_KEY}` },
    signal: AbortSignal.timeout(5000),
  });
  if (!response.ok) throw new Error(`Vertex API returned ${response.status}`);
  const servers = await response.json();
  return new EmbedBuilder()
    .setColor(0x9b5cff)
    .setTitle('Vertex Velocity · Network status')
    .setDescription(servers.map(s => {
      const icon = s.health === 'ONLINE' ? '🟢' : s.health === 'OFFLINE' ? '🔴' : '🟡';
      const latency = Number.isFinite(s.latencyMs) ? `${s.latencyMs} ms` : '—';
      return `${icon} **${s.name}** · ${s.playerCount} players · ${latency}`;
    }).join('\n') || 'No backends registered.')
    .setTimestamp();
}

module.exports = { createVertexStatusEmbed };
