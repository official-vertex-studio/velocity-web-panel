# Finalization and deployment validation

## TOTP recovery codes

When TOTP is enabled, the panel generates ten one-time recovery codes. The plaintext codes are returned only in the successful enable response and shown once in the Security Center. The database stores SHA-256 hashes only. A recovery code is consumed atomically on successful login and cannot be reused. Store the codes offline; disabling and enabling TOTP generates a new set. There is currently no separate recovery-code regeneration endpoint.

## ACME / public certificates

The plugin now supports **automatic ACME issuance and renewal through Certbot's HTTP-01 webroot mode**. This is an integration with the Certbot executable, not a bundled ACME protocol client. Set `acme.enabled: true`, `acme.domain`, and `acme.email` in `config.yml`. Certbot must be installed and executable in the same container/runtime, with permission to write the plugin-local `plugins/vertexvelocitywebpanel/acme/` state directory. The domain must resolve to the proxy host and public TCP/80 must reach the plugin HTTP listener (directly or via a reverse proxy forwarding `/.well-known/acme-challenge/`). The plugin stores Certbot state under `plugins/vertexvelocitywebpanel/acme/`, attempts initial issuance at startup, checks renewal every `acme.renewal-check-hours`, and attempts to reload HTTPS when the certificate expiry changes. Failed issuance is logged and retried on the next scheduled check; the panel stays HTTP-only until a certificate can be loaded.

Before production, verify the full flow against a staging domain/ACME staging endpoint if available, including DNS, port forwarding, challenge retrieval, first issuance, renewal, and listener reload. Certbot's installation method and port/firewall permissions vary by container image, so the feature cannot install Certbot or open host-level ports/allocations itself. If Certbot cannot run inside Pterodactyl, manage ACME at a host-level reverse proxy instead. Never expose authenticated panel traffic over plain HTTP.

## Native Velocity configuration and restart behavior

The panel can persist selected backend/routing changes to `velocity.toml` and update supported live backend registrations. Changes to listener ports, bind addresses, TLS listeners, and native settings that Velocity reads only at startup are marked `restartRequired`; restart the proxy to apply them. The panel does not promise a complete hot reload of all native Velocity settings.

## Required release checks

Before publishing a production release:

1. Build with JDK 25 and run `mvn clean verify`.
2. Install the shaded JAR on the target Velocity version and confirm plugin startup without warnings/errors.
3. Test TOTP enrollment, normal TOTP login, each recovery code once, reuse rejection, TOTP disable, and database persistence across restart.
4. Test HTTPS with a matching certificate/key pair, a mismatched pair, an expired certificate, an unavailable HTTPS port, and AUTO fallback to HTTP. Test ACME first issuance, failed HTTP-01 validation, scheduled renewal, and HTTPS listener reload using a staging domain before production.
5. Test every listener/config change that reports `restartRequired` by restarting Velocity and confirming the saved setting is applied.
6. Test alias reload with both valid and invalid YAML; invalid YAML must leave current aliases registered.
7. Test API read/write scopes and ensure owner-only operations remain owner-only.

These checks are a release checklist, not a claim that the checks have already been executed. The build and real-proxy tests must be run in an environment with JDK 25, Maven, dependency access, and a test Velocity proxy.
