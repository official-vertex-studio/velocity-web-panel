# Vertex Velocity Web Panel — Security and setup

## First launch and password access

1. Start the Velocity proxy with this plugin installed.
2. Read the one-time owner setup token in the Velocity console.
3. Open the panel URL and create the owner account. Use a unique password with at least 12 characters.
4. Create separate named accounts and grant only the role each operator needs.
5. Do not share the owner account. Account management and administrative actions are written to the audit log.

Passwords are stored as salted PBKDF2-HMAC-SHA256 hashes, not plaintext. Login attempts are rate-limited per observed client IP. Session cookies are HttpOnly and SameSite=Strict; direct HTTPS connections additionally use Secure cookies.

## HTTP / HTTPS configuration

The Settings page can save the following options to the plugin `config.yml`: enabled state, HTTP/HTTPS/BOTH mode, bind address, ports, TLS keystore path/type, sample interval, retention, login attempt limit and session lifetime. The plugin does not hot-rebind sockets: restart Velocity after changing the listener, bind or TLS options. Existing PEM certificates are accepted only when the certificate is currently valid and the unencrypted PKCS#8 private key cryptographically matches it. Automatic ACME issuance/renewal is available through Certbot HTTP-01 when `acme.enabled` is enabled. Certbot must be installed in the same runtime, the public domain must resolve to the proxy, and public TCP/80 must reach the challenge handler. See the README ACME setup instructions. This integration does not install Certbot or manage host-level permissions.

Example private HTTP listener (recommended only on localhost or a trusted private network):

```yaml
web-panel:
  enabled: true
  mode: HTTP
  bind-address: 127.0.0.1
  http-port: 8084
  https-port: 8443
  https:
    enabled: false
    keystore: ''
    keystore-password: ''
    keystore-type: PKCS12
```

To prepare a PKCS12 keystore for a direct HTTPS test, use a JDK `keytool` command on a trusted machine and replace the example values with your own hostname and password:

```bash
keytool -genkeypair -alias vertex-panel -keyalg RSA -keysize 3072 -validity 365 -storetype PKCS12 -keystore vertex-panel.p12 -dname "CN=panel.example.com"
```

Copy the keystore to a protected path on the proxy host, then configure:

```yaml
web-panel:
  mode: HTTPS
  bind-address: 127.0.0.1
  https-port: 8443
  https:
    enabled: true
    keystore: '/absolute/path/to/vertex-panel.p12'
    keystore-password: 'YOUR_KEYSTORE_PASSWORD'
    keystore-type: PKCS12
```

A self-signed certificate is suitable for a local test only and will show browser trust warnings. For a public domain, use a certificate issued for that domain and restrict access with firewall rules. If TLS is terminated by a reverse proxy, keep this plugin bound to localhost and configure HTTPS at the reverse proxy; do not expose the backend HTTP port publicly. Avoid committing `config.yml` or keystore files to source control.

## Important limitations

- HTTP sends credentials over the network without transport encryption. Use it only on localhost or a trusted network.
- Changes to the listener and TLS configuration require a full Velocity restart. The web interface reports this requirement after saving.
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
