# Vertex Velocity Web Panel 5.6.0 — automatic TLS

## Enable it

In `plugins/vertexvelocitywebpanel/config.yml`, set only:

```yaml
web-panel:
  mode: HTTPS
```

No PEM paths, keystore paths, or passwords need to be entered manually.

## Certificate selection

1. The plugin scans `/etc/letsencrypt/live/*/` for Certbot `fullchain.pem` and `privkey.pem` pairs.
2. It verifies that the files are readable, all certificates are currently valid, the leaf/issuer chain is trusted, and the private key matches the leaf certificate. With one lineage, the certificate SAN must match its directory name; with multiple lineages, the machine canonical hostname must match a SAN to disambiguate them.
3. If several Certbot lineages exist, the machine canonical hostname must match the certificate SAN to disambiguate them; otherwise the plugin logs a diagnostic and uses its fallback rather than guessing.
4. If a valid Certbot certificate is found, it is preferred over the plugin keystore.
5. Otherwise, an existing `tls/keystore.p12` is reused. If it is missing or invalid, a self-signed fallback is generated and stored there with owner-only file permissions.

The fallback provides encryption but is not trusted by public browsers. It may also not match a public DNS name that the plugin cannot know automatically. A trusted, warning-free connection requires a valid certificate for the exact public panel hostname.

## Renewal and hot reload

Certbot's systemd timer or cron job performs the actual Let's Encrypt renewal. The plugin does not run `certbot renew` itself and does not replace Certbot's ACME workflow. While HTTPS is running, the plugin checks certificate file identity/metadata every 30 seconds. When Certbot updates the live symlinks or target files, the plugin updates its reloadable TLS key manager so new TLS connections use the renewed certificate without stopping the HTTP listener or restarting Velocity. Existing TLS sessions continue until their normal connection lifecycle ends.

Keep Certbot's automatic renewal timer enabled. The Velocity process must have read access to both the live certificate and its private key. If Velocity runs in a Pterodactyl/container environment and Certbot runs on the host, the Certbot directory must be mounted into the container; the plugin cannot read host files outside its filesystem namespace. The plugin does not automatically weaken `/etc/letsencrypt` permissions. If the private key is unreadable, the console reports the issue; on first startup the plugin uses its self-signed fallback, and during a transient refresh issue it keeps the last known-good Certbot context.

## Expiry and diagnostics

Certificates with 30 days or less remaining generate a throttled console warning. Invalid chains, SAN/lineage mismatches, unreadable keys, and ambiguous multiple certificates are reported in the proxy log. No private key material is written to logs.

## Login protection

Login and owner setup have per-IP throttles. Login also has a per-account request bucket and a global bucket, progressive temporary blocking for repeated bad credentials, dummy PBKDF2 work for unknown usernames to reduce timing-based enumeration, and a semaphore limiting concurrent expensive password checks. TOTP failures contribute to the progressive IP/account lockout.
