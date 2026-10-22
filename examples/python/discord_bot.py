"""Discord.py slash commands backed by Vertex API.
Install: python -m pip install -U discord.py requests
Set DISCORD_TOKEN, VERTEX_URL and VERTEX_API_KEY as environment variables.
"""
import os
import discord
from discord import app_commands
from discord.ext import commands
from vertex_client import VertexClient

intents = discord.Intents.default()
bot = commands.Bot(command_prefix="!", intents=intents)
vertex = VertexClient()

@bot.event
async def on_ready():
    try:
        synced = await bot.tree.sync()
        print(f"Logged in as {bot.user}; synced {len(synced)} slash commands")
    except Exception as exc:
        print(f"Slash-command sync failed: {exc}")

@bot.tree.command(name="minecraft", description="Show Vertex network status")
async def minecraft(interaction: discord.Interaction):
    await interaction.response.defer(thinking=True)
    try:
        stats = vertex.stats()
        servers = vertex.servers()
        online = stats.get("onlinePlayers", 0)
        total = stats.get("backendCount", len(servers))
        embed = discord.Embed(title="Vertex Velocity · Network status", color=0x8B5CF6)
        embed.add_field(name="Players online", value=str(online), inline=True)
        embed.add_field(name="Backends", value=str(total), inline=True)
        uptime = stats.get("uptimeSeconds")
        if uptime is not None:
            embed.add_field(name="Proxy uptime", value=f"{int(uptime)//86400}d {int(uptime)%86400//3600}h", inline=True)
        lines = []
        for server in servers[:20]:
            health = str(server.get("health", "UNKNOWN")).upper()
            icon = "🟢" if health == "ONLINE" else ("🔴" if health == "OFFLINE" else "🟡")
            lines.append(f"{icon} **{server.get('name', 'unknown')}** — {server.get('playerCount', 0)} players")
        embed.add_field(name="Servers", value="\n".join(lines) or "No backends found", inline=False)
        embed.set_footer(text="Data supplied by Vertex Velocity Web Panel API")
        await interaction.followup.send(embed=embed)
    except Exception:
        # Avoid leaking API keys, host details or raw request headers to Discord.
        await interaction.followup.send("Could not reach the Vertex panel. Check bot logs and API settings.", ephemeral=True)

@bot.tree.command(name="minecraft_players", description="List online players, optionally filter by server")
@app_commands.describe(server="Optional backend name")
async def minecraft_players(interaction: discord.Interaction, server: str | None = None):
    await interaction.response.defer(thinking=True, ephemeral=True)
    try:
        players = vertex.players(server=server, limit=50)
        if not players:
            await interaction.followup.send("No online players found.", ephemeral=True)
            return
        lines = [f"• **{p.get('username', '?')}** — `{p.get('server', '?')}` — {p.get('ping', '?')} ms" for p in players[:50]]
        embed = discord.Embed(title=f"Online players ({len(players)})", description="\n".join(lines), color=0x8B5CF6)
        await interaction.followup.send(embed=embed, ephemeral=True)
    except Exception:
        await interaction.followup.send("Could not retrieve the player list. Check the panel and API key.", ephemeral=True)

if __name__ == "__main__":
    token = os.environ.get("DISCORD_TOKEN")
    if not token:
        raise SystemExit("DISCORD_TOKEN environment variable is required")
    bot.run(token)
