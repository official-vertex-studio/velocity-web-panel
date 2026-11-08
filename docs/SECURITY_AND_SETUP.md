# Vertex Velocity Web Panel — Security and setup

## First launch and password access

1. Start the Velocity proxy with this plugin installed.
2. Read the one-time owner setup token in the Velocity console.
3. Open the panel URL and create the owner account. Use a unique password with at least 12 characters.
4. Create separate named accounts and grant only the role each operator needs.
5. Do not share the owner account. Account management and administrative actions are written to the audit log.

Passwords are stored as salted PBKDF2-HMAC-SHA256 hashes, not plaintext. Login attempts are rate-limited per observed client IP, per account and globally, with progressive temporary blocking and capped concurrent password hashing. Session cookies are HttpOnly and SameSite=Strict; direct HTTPS connections additionally use Secure cookies.

## HTTP / HTTPS configuration

Set `web-panel.mode: HTTPS` in `config.yml`. No certificate path, private-key path, or keystore password is required. On startup the plugin searches the standard Certbot location `/etc/letsencrypt/live/*/` and validates a discovered `fullchain.pem` + `privkey.pem` pair. If no usable Certbot certificate is found, it reuses its own `tls/keystore.p12` or creates a self-signed certificate automatically. The generated certificate is retained and reused on subsequent starts.

The plugin checks the active Certbot files every 30 seconds and hot-reloads a renewed certificate for new HTTPS connections without restarting Velocity. Certbot's own systemd timer or cron job remains responsible for the actual renewal. The Velocity process must be able to read the Certbot certificate and private-key files. The plugin does not install Certbot or obtain public certificates itself. A self-signed certificate provides encryption but causes browser trust warnings; use a host-installed Certbot certificate for a browser-trusted public HTTPS endpoint. With one Certbot lineage, its directory name must match a certificate SAN. With multiple lineages, the machine canonical hostname must match a certificate SAN to disambiguate them; otherwise the plugin uses the fallback rather than guessing.

Example:

```yaml
web-panel:
  enabled: true
  mode: HTTPS
  bind-address: 0.0.0.0
  http-port: 25569
```

For a reverse proxy that terminates TLS, keep the plugin in `HTTP` mode and set `web-panel.reverse-proxy: true` only when the upstream proxy is trusted and cannot be bypassed by untrusted clients. Restart Velocity after changing the listener mode.

## Important limitations

- HTTP sends credentials over the network without transport encryption. Use it only on localhost or a trusted network.
- Changes to listener mode, bind address or port require a full Velocity restart. Renewed certificate files are hot-reloaded automatically while HTTPS remains enabled.
- Maintenance mode can deny new Velocity logins when enabled. UUID whitelist enforcement is configurable; test it on a staging proxy before enabling it on a production network.
- Do not treat the current plugin as tested for public production use until the shaded JAR is built with the target JDK and tested on a real Velocity proxy.

## External integrations and API keys

Create API keys only while authenticated as the owner using `POST /api/v1/integrations/keys`. Copy the returned key immediately: the database stores only its SHA-256 hash, and the raw value cannot be retrieved later. List metadata at `GET /api/v1/integrations/keys` and revoke a key with `DELETE /api/v1/integrations/keys/{id}`. These keys are read-only and cannot execute mutation endpoints.

Store API keys in environment variables or a secrets manager. Never commit them, place them in front-end JavaScript, or print them in logs. Use HTTPS for traffic outside localhost/trusted private networks. Revoke any key suspected of exposure and create a replacement.

Owner-only active-session endpoints are available at `GET /api/v1/sessions` and `DELETE /api/v1/sessions/{id}`. The API returns a short session identifier, not the cookie/session token. The Security Center includes a session list and revocation controls. Session identifiers are shortened hashes; raw cookie tokens are never shown.

## Discord webhook alerts

Set `discord.enabled: true` and `discord.webhook-url` to an official HTTPS Discord webhook URL in the plugin config. Keep the URL secret. The plugin sends a message on proxy startup, backend state changes and newly detected latency spikes. Delivery is asynchronous and can fail if Discord or the network is unavailable; check proxy logs. Do not assume a notification was delivered merely because the local event was recorded.

## Security controls not yet available

TOTP-based two-factor authentication is now included in the source: enroll from Security Center, confirm a six-digit code, and use the code after password entry at login. Disabling TOTP requires the current password and code. Recovery codes are not implemented in this release. Keep access to your authenticator; if it is lost, an operator with filesystem/database access may need to perform a carefully backed-up administrative recovery. The plugin SQLite database stores the TOTP secret, so protect the plugin data directory and its backups. Before public production use, review the role matrix, run dependency/security scans, and exercise login, session revocation, TOTP and API-key revocation on a staging proxy.
