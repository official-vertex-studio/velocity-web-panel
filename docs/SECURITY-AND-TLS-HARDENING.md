# Security and TLS hardening — 5.6.0

## Automatic HTTPS certificate handling

When `web-panel.mode: HTTPS` is enabled, the plugin scans `/etc/letsencrypt/live/*/` for Certbot lineages containing `fullchain.pem` and `privkey.pem`. It verifies certificate validity dates, the leaf-to-issuer chain signatures, the certificate path against the Java trust store, private-key readability, and cryptographic matching between the private key and the leaf certificate. With one lineage, the leaf certificate SAN must match its directory name. With multiple lineages, the machine canonical hostname must match a certificate SAN to disambiguate them; otherwise the plugin uses its fallback rather than guessing.

The plugin checks the certificate every 30 seconds. Certbot's own systemd timer or cron job remains responsible for renewal. After Certbot updates its live symlinks/files, the plugin updates its reloadable TLS key manager so new HTTPS connections use the renewed certificate without rebinding the listener or restarting Velocity. Existing TLS sessions continue until their normal connection lifecycle ends.

If no usable Certbot certificate is available, the plugin reuses `tls/keystore.p12` or creates a self-signed fallback in the plugin data directory. The fallback encrypts traffic but is not publicly trusted; browsers display a certificate warning. It may also not match a public DNS hostname the plugin cannot discover. A browser-trusted public endpoint requires a valid certificate issued for the exact hostname users visit.

The Velocity service account must be able to read both Certbot files, especially `privkey.pem`. If Velocity runs inside a Pterodactyl/container environment while Certbot runs on the host, `/etc/letsencrypt` must be mounted into the container and readable there; the plugin cannot read host files that are not exposed to its process. The plugin deliberately does not weaken `/etc/letsencrypt` permissions automatically. If access is denied, it logs a diagnostic and keeps the last known-good Certbot context during a transient refresh failure; on first startup it uses the local fallback instead. Certificates expiring within 30 days generate a throttled warning in the proxy log. No certificate path, keystore path, or password needs to be entered in `config.yml`; set only `web-panel.mode: HTTPS`.

## Login abuse protection

Login and initial setup are rate-limited per client IP. Login additionally has per-account and global request buckets, progressive temporary blocking after repeated credential failures, and a semaphore limiting concurrent PBKDF2 password checks. Unknown usernames still perform a dummy PBKDF2 operation to reduce timing-based account enumeration. Two-factor failures contribute to the same progressive IP/account blocking state. The in-memory rate-limit state is periodically pruned; the global login bucket limits attacker-controlled per-account bucket growth.

## Reverse proxy mode

If Nginx, Caddy, or a hosting provider terminates HTTPS, keep the plugin listener in HTTP mode and set `web-panel.reverse-proxy: true`. Only trust forwarded headers from explicitly configured proxy addresses. The proxy must prevent untrusted clients from bypassing the public TLS endpoint and reaching the upstream listener directly.

## Session and API security

- Session cookies are HttpOnly and SameSite=Strict; the Secure flag is enabled for direct HTTPS or configured trusted reverse-proxy mode.
- State-changing cookie-authenticated requests must pass the Origin check.
- Keep the panel bound to loopback or a private interface unless access is protected by a firewall or trusted reverse proxy.
- Do not expose API keys, setup tokens, webhook URLs, or passwords in logs or public repositories.
- Restrict access to the plugin data directory. The generated keystore is written with owner-only POSIX permissions where supported.

## Validation status

Static source checks and configuration parsing are performed for this archive, but a release build and live Certbot/Pterodactyl test still need to be run on the target host. The project targets Java 25.
