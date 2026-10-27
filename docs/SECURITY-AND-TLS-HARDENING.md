# Security and TLS hardening (2.1.0 work-in-progress)

## Authentication protections

- IP request rate limit remains configured by `security.login-attempts-per-minute`.
- Failed password/TOTP attempts now add progressive temporary blocks (from 30 seconds, capped at 15 minutes); the failure state is in memory and resets on restart.
- Cookie-authenticated state-changing requests must include an `Origin` matching the request `Host`. Cookie flags include `HttpOnly`, `SameSite=Strict`, and `Secure` when the panel listener is HTTPS or `web-panel.reverse-proxy: true`.
- Owner-only `POST /api/v1/sessions/revoke-all` revokes all web sessions. A successful login always creates a fresh cryptographically random session token.
- Role checks deny mutation requests to VIEWER/MODERATOR roles; sensitive endpoints retain their more specific owner/admin checks.
- ACME state directories are restricted to owner-only POSIX permissions when supported. Certbot failure output is suppressed from normal logs to reduce accidental disclosure.

## ACME staging and HTTP-01

Set `acme.staging: true` to use Let’s Encrypt staging for issuance/renewal tests. Staging certificates are intentionally not trusted by browsers. Switch it back to `false` only after a successful staging run. The plugin serves `/.well-known/acme-challenge/<token>` from its private webroot.

For HTTP-01 to succeed, the configured public domain must resolve to this host and inbound TCP port 80 must reach the plugin HTTP listener (directly or through a reverse proxy forwarding that path). A container allocation or firewall cannot be opened by the plugin itself. Verify from outside the hosting network that `http://YOUR_DOMAIN/.well-known/acme-challenge/test` reaches the panel challenge handler (a missing token should return 404, not the website homepage).

The panel reports active mode, certificate expiry, SHA-256 fingerprint, last ACME check and last error via authenticated `GET /api/v1/tls` and `/api/v1/status`. Certificate replacement is only attempted after the detector verifies validity and private-key matching; listener reload failure attempts to restore the previous listener.

## Pterodactyl reverse-proxy mode

If Certbot is unavailable in the game-server container, disable plugin ACME and terminate HTTPS at a trusted host/provider reverse proxy (for example, the hosting provider’s managed proxy). Set `web-panel.reverse-proxy: true` so session cookies use `Secure`, and bind the panel to a private/local interface such as `127.0.0.1` if the proxy shares the same network namespace. Configure the reverse proxy to forward `/api/` and static assets to the panel and to enforce HTTPS externally. Do not enable `trust-proxy-headers` unless the proxy is trusted and strips/replaces client-supplied forwarding headers. Never expose the backend HTTP port publicly in this mode.

## Validation status

This is implementation documentation, not a claim of production validation. This environment did not have Maven installed and only had JDK 21, while the project targets Java 25. A real ACME staging issuance/renewal and a live reverse-proxy test still need to be run on the target host.

## Additional hardening in 2.1.0

- Browser session rotation is available at `POST /api/v1/session/rotate`. It invalidates the token presented by the browser and returns a fresh HttpOnly cookie. API-key callers cannot use this endpoint.
- Owner-only global session revocation is available at `POST /api/v1/sessions/revoke-all`; the Security Center exposes a confirmation action. This intentionally signs every browser out.
- Cookie `Secure` is enabled for direct HTTPS and for `web-panel.reverse-proxy: true`. Only enable reverse-proxy mode when the public entry point really terminates HTTPS. The proxy must not be directly bypassable from untrusted networks.
- On POSIX filesystems, `config.yml` is restricted to owner read/write (`0600`). ACME state directories use owner-only permissions (`0700`). On non-POSIX filesystems, configure equivalent ACLs in the host/container.
- Request error logs record the URL path, not the full URI/query string, to reduce accidental leakage of query parameters.

## Required deployment validation (not implied to have passed)

1. Build with JDK 25: `mvn clean verify`.
2. Use a dedicated test domain and `acme.staging: true`; confirm public DNS resolves to the host and TCP/80 reaches the panel's HTTP listener for `/.well-known/acme-challenge/<token>`.
3. Confirm a staging certificate is not treated as a browser-trusted production certificate. Use a separate ACME state directory or remove the test state before switching to production issuance.
4. Confirm renewal and listener reload with a test certificate, then test rollback by presenting an invalid certificate/key pair.
5. Test reverse proxy mode with HTTPS at the proxy, HTTP only on a private upstream, `Secure` cookies, strict Origin checks, and no direct public upstream access.
6. Exercise each API endpoint with OWNER, ADMINISTRATOR, VIEWER, READ-only API key and READ_WRITE API key. Confirm sensitive routes reject insufficient privileges.
7. Test login throttling from repeated failures, TOTP failures, session rotation, individual revocation, global revocation and logout.
8. Check filesystem permissions and logs after startup, failed login, ACME failure and a failed API request.

ACME HTTP-01 requires public port 80 to be reachable from the certificate authority. The plugin cannot open host firewall rules or install Certbot in a restricted Pterodactyl container. In that environment, terminate HTTPS at a trusted reverse proxy and keep the plugin listener private.
