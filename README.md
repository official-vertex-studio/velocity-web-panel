# 🚀 Vertex Velocity Web Panel

### 🌐 Control your Velocity network. From one web interface.

**A web administration plugin and REST API for Minecraft Velocity proxies.**

<div align="center">

[🌍 Vertex Studio](https://vertexdev-studio-website.vercel.app/) · [📚 Documentation](https://vertexdev-studio-website.vercel.app/) · [🐛 Report a Bug](../../issues) · [💡 Request a Feature](../../issues)

![Java](https://img.shields.io/badge/Java-25-orange?style=for-the-badge&logo=openjdk&logoColor=white)
![Maven](https://img.shields.io/badge/Build-Maven-blue?style=for-the-badge&logo=apachemaven&logoColor=white)
![Velocity](https://img.shields.io/badge/Platform-Velocity-6f42c1?style=for-the-badge)
![License](https://img.shields.io/badge/License-GPL--3.0-green?style=for-the-badge)
![Status](https://img.shields.io/badge/Status-Development-yellow?style=for-the-badge)

</div>

---

## 📖 Table of Contents

- [🌟 Overview](#-overview)
- [✨ Features](#-features)
- [🖥️ Dashboard](#️-dashboard)
- [👥 Player Intelligence](#-player-intelligence)
- [🖧 Server Management](#-server-management)
- [📊 Analytics and Monitoring](#-analytics-and-monitoring)
- [🔌 REST API and Integrations](#-rest-api-and-integrations)
- [🎮 In-Game Commands](#-in-game-commands)
- [🔐 Security](#-security)
- [📋 Requirements](#-requirements)
- [📦 Installation](#-installation)
- [🛠️ Build from Source](#️-build-from-source)
- [⚙️ Configuration](#️-configuration)
- [🌐 Network and Ports](#-network-and-ports)
- [🔑 First Launch](#-first-launch)
- [🔗 API Authentication](#-api-authentication)
- [💻 Integration Examples](#-integration-examples)
- [💾 Database and Backups](#-database-and-backups)
- [🔄 Updating the Plugin](#-updating-the-plugin)
- [🧰 Troubleshooting](#-troubleshooting)
- [🧪 Testing and Validation](#-testing-and-validation)
- [🗺️ Roadmap and Limitations](#️-roadmap-and-limitations)
- [🤝 Contributing](#-contributing)
- [📜 License](#-license)
- [🏢 Vertex Studio](#-vertex-studio)

---

## 🌟 Overview

**Vertex Velocity Web Panel** is a web administration plugin designed for the official Minecraft Velocity proxy.

It provides a centralized interface for monitoring players, inspecting backend servers, reviewing historical activity and integrating proxy information into external applications through a REST API.

Instead of relying exclusively on the Minecraft console, administrators can access supported management features through a web browser.

### 🎯 Project goals

- 🌐 Centralize proxy monitoring in a web dashboard.
- 👥 Make connected-player information easier to inspect.
- 🖧 Simplify visibility into registered backend servers.
- 📊 Store and review supported historical metrics.
- 🔌 Provide an API for bots, websites and external tools.
- 🔐 Protect administrative features with authentication and permissions.
- 🧩 Keep the project compatible with the official Velocity proxy architecture.

> [!IMPORTANT]
> Vertex Velocity Web Panel is a **Velocity plugin**, not a standalone proxy, not a Minecraft client and not a Paper/Spigot backend plugin. Install it on the Velocity proxy itself.

### 🏗️ Architecture

```text
                  Minecraft Players
                         |
                         v
                Velocity Proxy
                         |
             Vertex Velocity Web Panel
                         |
            +------------+------------+
            |            |            |
            v            v            v
        Web Panel     REST API     SQLite
            |            |
            v            v
      Administrator   External Apps
                      Discord Bots
                      Websites
                      Scripts
```

The plugin operates on information available to Velocity and the events it records. Backend-specific data, such as an economy balance managed exclusively by a Paper plugin, requires a separate integration.

---

## ✨ Features

### 🌐 Web Administration

- 📊 Dashboard with proxy statistics.
- 👥 Connected-player directory.
- 🖧 Registered backend overview.
- 📡 Backend ping and availability snapshots.
- 📈 Historical analytics from stored samples.
- 🧾 Recent activity and audit records.
- 🔐 Account and security management.
- 🔑 API key management.
- 🛡️ Session management.
- ⚙️ Supported configuration controls.
- 🔀 Fallback-server order management.
- 🎨 Dark interface with purple accents and responsive navigation.

### 🎮 Proxy-Wide Commands

- 📊 View proxy statistics.
- 🖧 Inspect registered backend servers.
- 👥 List connected players where permitted.
- 📍 Inspect a player's current backend.
- 📡 View supported ping information.
- 📚 Access command help.

### 🔌 External Integrations

The REST API uses HTTP and JSON, making it suitable for integrations written in different programming languages.

Examples can be found in the `examples/` directory.

### ⚠️ Feature availability

The precise availability of a feature depends on the plugin version, configuration and implementation status. Consult the source code, release notes and validation results for the version you install.

---

## 🖥️ Dashboard

The web dashboard is intended to provide a centralized overview of your proxy.

Depending on the installed version, supported dashboard information may include:

- 🟢 Online player count.
- 🖧 Registered backend count.
- ⏱️ Proxy uptime.
- 🧠 JVM memory information.
- 📡 Backend latency and ping status.
- 📰 Recent player and network events.
- 📊 Historical measurements.
- 🔐 Security and account information.

The dashboard does not automatically provide data from every plugin installed on your backend servers.

For example, retrieving a player's balance from an economy plugin requires an appropriate integration with the system responsible for that balance.

---

## 👥 Player Intelligence

Player Intelligence is intended to make proxy-level player activity easier to investigate.

Supported information may include:

- 🔎 Player search.
- 🆔 UUID-based profile lookup.
- 📍 Current backend.
- 🕒 Recorded connection and disconnection events.
- 🔀 Observed backend transitions.
- ⏱️ Estimated session duration.
- 📊 Historical player activity.
- 🧾 Relevant connection and network events.
- 🛡️ Supported moderation actions.

### 📌 Historical data

Historical information begins when the plugin is installed and records the relevant events.

The plugin cannot reconstruct events that happened before recording began. Session durations can also be incomplete if the proxy terminates unexpectedly or relevant events are missing.

---

## 🖧 Server Management

Vertex Velocity Web Panel is designed to improve visibility into the backend servers registered with Velocity.

### 📡 Backend monitoring

Depending on the version, the panel may expose:

- Backend names.
- Registered backend addresses or safe backend metadata.
- Connected-player counts.
- Ping measurements.
- Backend availability observations.
- Historical latency samples.
- Recent network transitions.

### 🔀 Fallback routing

Supported versions may provide an interface for editing the fallback order represented by the `try` list in Velocity's native `velocity.toml`.

**Important:** editing this list is not the same as changing a backend's address or dynamically reloading every Velocity configuration setting.

Back up `velocity.toml` before making changes. If the installed implementation requires a restart, restart the proxy to apply the updated configuration.

---

## 📊 Analytics and Monitoring

Historical analytics depend on the events and samples recorded by the installed version.

Potential metrics include:

- 👥 Concurrent-player measurements.
- 📈 Player activity over time.
- ⏱️ Proxy uptime.
- 📡 Backend latency samples.
- 🟢 Backend availability observations.
- 🕒 Estimated session durations.
- 🧾 Recorded network events.
- 🧠 JVM memory observations.

### ⚠️ Understanding the metrics

- **Sampled data:** a short outage between polling intervals may not be recorded.
- **Backend ping:** a successful ping does not prove that every game feature is working.
- **Session duration:** estimates depend on recorded connection events.
- **Retention:** older records may be removed according to the configured retention policy.
- **Maintenance:** an informational maintenance flag does not necessarily block Minecraft logins.

Use the analytics as operational observations, not as a guarantee that every aspect of your network is healthy.

---

## 🔌 REST API and Integrations

The REST API allows external applications to retrieve supported proxy information.

### 📡 Base URL

For HTTP:

```text
http://YOUR-HOST:YOUR-PORT/api/v1
```

For HTTPS:

```text
https://YOUR-HOST:YOUR-PORT/api/v1
```

Use HTTPS when communicating across an untrusted network.

### 📋 Common API routes

The following table summarizes routes documented for the project. Always check the API implementation and `docs/API.md` for the exact routes, response schemas and permissions in your installed version.

| Method | Endpoint | Purpose |
|---|---|---|
| `GET` | `/health` | Health and version information |
| `GET` | `/setup-status` | Initial setup status |
| `POST` | `/setup` | Initial owner setup, when available |
| `POST` | `/login` | Authenticate a panel user |
| `POST` | `/login/2fa` | Complete a TOTP login challenge |
| `POST` | `/logout` | End the current session |
| `GET` | `/me` | Inspect the current authenticated user |
| `GET` | `/stats` | Proxy statistics |
| `GET` | `/players` | Connected-player information |
| `GET` | `/players/{uuid}` | Player profile and recorded history |
| `GET` | `/player-intelligence` | Historical player search |
| `GET` | `/servers` | Registered backend servers |
| `GET` | `/servers/{name}` | Backend details |
| `GET` | `/commands` | Command metadata |
| `GET` | `/network` | Network overview |
| `GET` | `/activity` | Recent activity |
| `GET` | `/events` | Recorded events |
| `GET` | `/analytics` | Historical metrics and analytics |
| `GET` | `/maintenance` | Maintenance information |
| `GET` | `/config` | Supported safe configuration view |
| `GET` | `/audit` | Audit records |
| `GET` | `/sessions` | Session metadata |
| `GET` | `/integrations/keys` | API key metadata |
| `GET` | `/routing` | Fallback routing information |

Some routes require an authenticated browser session and specific permissions. Routes that modify data must be checked individually before use.

### 📈 Example: retrieve proxy statistics

```bash
curl --fail-with-body \
  "http://127.0.0.1:25569/api/v1/stats" \
  -H "Authorization: Bearer YOUR_API_KEY"
```

Replace the address, port and API key with your own values.

### 👥 Example: retrieve connected players

```bash
curl --fail-with-body \
  "http://127.0.0.1:25569/api/v1/players?limit=50" \
  -H "Authorization: Bearer YOUR_API_KEY"
```

### 🖧 Example: retrieve registered backends

```bash
curl --fail-with-body \
  "http://127.0.0.1:25569/api/v1/servers" \
  -H "Authorization: Bearer YOUR_API_KEY"
```

These examples assume the relevant endpoints and read-only API-key authentication are enabled in the installed version.

### 📚 API documentation

For complete endpoint documentation, request formats, responses and permissions, see:

- [`docs/API.md`](docs/API.md)
- [`docs/SECURITY_AND_SETUP.md`](docs/SECURITY_AND_SETUP.md)

---

## 🎮 In-Game Commands

The plugin can register commands directly with Velocity, allowing supported commands to be used across the proxy network.

| Command | Description |
|---|---|
| `/vstats` | Display proxy statistics |
| `/vservers` | List registered backend servers |
| `/vplayers [backend]` | List connected players, optionally filtered by backend |
| `/vserver` | Show your current backend |
| `/vserver <player>` | Inspect another player's backend, where permitted |
| `/vping` | Display supported ping information |
| `/vhelp` | Display command help |

Depending on the version, aliases may also be available, such as `/vertexstats`, `/vertexservers`, `/vertexplayers`, `/vertexserver`, `/vertexping` and `/vertexhelp`.

### 🔐 Permissions

Some commands may require permission nodes, including:

```text
vertexvelocity.command.players
vertexvelocity.command.server
```

Check the command registration and permission requirements in the source code of your installed release before granting access.

For LuckPerms, permissions can be assigned to the appropriate group using the LuckPerms command interface.

Example:

```text
/lp group admin permission set vertexvelocity.command.players true
```

Do not grant administrative permissions to untrusted players.

---

## 🔐 Security

Security is essential because the panel can expose information about your Minecraft network and may provide administrative operations.

### 👤 Account roles

The intended permission model distinguishes roles such as:

- 👑 **OWNER** — owner-level account and integration management.
- 🛡️ **ADMINISTRATOR** — supported administrative operations.
- 🔨 **MODERATOR** — supported player moderation actions.
- 👁️ **VIEWER** — read-only panel access.
- 🔑 **API key** — a separate machine credential intended for read-only access.

Actual permissions depend on the installed version and must be enforced server-side.

### 🔑 Password protection

The documented implementation uses salted PBKDF2-HMAC-SHA256 password hashes.

Use a unique password, preferably at least 12 characters long, and store it in a password manager.

### 🛡️ Session protection

The documented implementation includes HTTP-only session cookies, session expiration and session revocation features.

A proxy restart may invalidate in-memory sessions. Confirm the behavior of your installed version before relying on a particular session policy.

### 🔐 Two-factor authentication

Versions containing TOTP support may allow an authenticator application to generate six-digit login codes.

Before enabling two-factor authentication on your owner account:

1. 📱 Configure your authenticator application.
2. ✅ Confirm enrollment with a current code.
3. 🔄 Test a fresh login.
4. 🔐 Keep access to the authenticator application.

If recovery codes are not implemented in your release, losing access to the authenticator may require a controlled recovery procedure.

### 🔑 API key protection

When using API keys:

- Store keys in environment variables or a secret manager.
- Never commit real keys to Git.
- Never include keys in public screenshots or issue reports.
- Revoke exposed keys immediately.
- Use the minimum permissions supported by the integration.
- Do not embed privileged secrets in public frontend JavaScript.

If the implementation displays an API key only once, copy it securely when it is created.

### 🌐 Network protection

- 🔒 Prefer HTTPS for external access.
- 🏠 Keep the listener private when possible.
- 🧱 Restrict access using firewall and hosting-provider controls.
- 🔑 Keep passwords, setup tokens, webhook URLs and keystore passwords secret.
- 💾 Protect database and configuration backups.
- 🧪 Test administrative operations on a staging proxy first.

**Plain HTTP is not suitable for transmitting credentials over an untrusted public network.**

---

## 📋 Requirements

The exact requirements depend on the release you install.

| Component | Requirement |
|---|---|
| Minecraft platform | Official Velocity proxy |
| Java | JDK 25 for the current Java 25 build configuration |
| Build tool | Maven 3.9 or compatible newer version |
| Dependencies | Resolved from the repositories declared in `pom.xml` |
| Network | An available TCP port for the web panel |
| Hosting | A Velocity server or compatible Pterodactyl container |
| Storage | Writable plugin data directory for configuration and SQLite |

### ⚠️ Compatibility

Use a Velocity version compatible with the API dependency declared in the release's `pom.xml`.

Do not assume that a JAR built for one Java or Velocity version is compatible with every other version.

---

## 📦 Installation

### 1. ⬇️ Download the release

Open the repository's GitHub Releases page and select the version you want to install.

Download the compiled plugin JAR from the release's **Assets** section.

Do not upload the source ZIP as a plugin.

### 2. 🛑 Stop the Velocity proxy

Stop the Velocity proxy cleanly using your server panel or hosting control panel.

Do not install this plugin into a Paper backend's `plugins/` directory.

### 3. 💾 Create a backup

Before a new installation, upgrade or configuration change, back up:

```text
plugins/vertexvelocitywebpanel/
velocity.toml
```

Some files may not exist yet on a fresh installation.

### 4. 📂 Upload the JAR

Place the compiled plugin JAR into the Velocity proxy's plugin directory:

```text
plugins/
└── vertex-velocity-web-panel-VERSION.jar
```

The filename above is illustrative. Use the actual JAR name supplied by the release.

Remove obsolete duplicate copies of the same plugin only after backing them up.

### 5. ▶️ Start Velocity

Start the proxy and inspect the console.

Look for the plugin's initialization messages and the web listener address. If an exception occurs, read the full stack trace before proceeding.

### 6. 🔑 Complete initial setup

If the installation requires an owner setup token, retrieve it from the Velocity console and use it to create the initial owner account.

Do not share the token.

### 7. 🌐 Open the panel

Open the configured address in your browser.

Example for a local HTTP listener:

```text
http://127.0.0.1:25569
```

Replace the address and port with your actual configuration.

### 8. ✅ Verify the installation

Check that:

- The panel loads.
- Login works.
- Statistics appear.
- Backend information is displayed.
- API authentication works.
- Commands register correctly.
- No repeated errors appear in the proxy console.

Perform these checks before exposing the panel publicly.

---

## 🛠️ Build from Source

### 📥 Clone the repository

```bash
git clone https://github.com/official-vertex-studio/velocity-web-panel.git
cd velocity-web-panel
```

### ☕ Verify Java and Maven

```bash
java -version
mvn -version
```

Make sure the selected JDK supports the Java version declared by the project.

### 🏗️ Build the plugin

```bash
mvn clean package
```

Wait for Maven to finish and verify that the final result reports:

```text
BUILD SUCCESS
```

The compiled JAR should be located in the `target/` directory. Its exact name depends on the artifact version and Maven configuration.

### 📦 Verify the output

Check that the JAR exists and corresponds to the version you intended to build.

A successful ZIP integrity check or JavaScript syntax check alone does not prove that the Java plugin compiles or works at runtime.

### 🔄 Build troubleshooting

If Maven reports a missing dependency or class:

1. Read the first relevant error and its full `Caused by` chain.
2. Verify the JDK and Maven versions.
3. Check the artifact coordinates and versions in `pom.xml`.
4. Check repository availability and network settings.
5. Rebuild after correcting the underlying issue.

Do not randomly change dependency versions to hide a compilation error.

---

## ⚙️ Configuration

The plugin's configuration is normally stored in its Velocity plugin data directory, for example:

```text
plugins/vertexvelocitywebpanel/config.yml
```

Allow the plugin to generate its initial configuration, then edit the settings supported by your release.

### 🧩 Example configuration

The following is an **illustrative template**, not a guaranteed drop-in configuration for every release. Preserve the keys and structure generated by your installed version.

```yaml
web-panel:
  enabled: true
  mode: HTTP
  bind-address: 127.0.0.1
  http-port: 25569
  https-port: 8443
  https:
    enabled: false
    keystore: ""
    keystore-password: ""
    keystore-type: PKCS12

security:
  session-minutes: 60
  login-attempts-per-minute: 8
  trust-proxy-headers: false

statistics:
  sample-seconds: 15
  retention-days: 30

discord:
  enabled: false
  webhook-url: ""
```

### ⚠️ Configuration notes

- 🌐 The Minecraft listener and web panel listener use separate ports.
- 🔒 HTTP is appropriate only for localhost or a trusted private network.
- 🔐 HTTPS requires a correctly configured certificate or keystore when using the embedded HTTPS listener.
- 🔄 Listener changes may require a full Velocity restart.
- 🧾 Configuration keys can change between releases.
- 🔑 Never publish configuration files containing real passwords, webhook URLs or other secrets.
- 🧪 Check the configuration documentation and source code for your exact version.

---

## 🌐 Network and Ports

### 🔌 Minecraft port versus panel port

The Minecraft proxy port and the web panel port are separate.

For example:

```text
Minecraft proxy: YOUR-HOST:25567
Web panel:       YOUR-HOST:25569
```

These values are examples only. Keep your existing Minecraft port unless you specifically intend to change it.

### 🐳 Pterodactyl deployment

For remote panel access, verify each of the following:

1. ⚙️ The configured web port matches the intended port.
2. 🔌 The correct allocation is attached to the Velocity container.
3. 🌐 The listener is bound to an address reachable through the container network.
4. 🧱 Firewall and hosting-provider rules allow the intended TCP connection.
5. 🔗 The browser URL uses the correct hostname, port and HTTP/HTTPS scheme.

A plugin cannot automatically create a Pterodactyl allocation or open the host firewall.

### 🏠 Private listener

For access through a local reverse proxy, a loopback listener can reduce direct exposure:

```yaml
bind-address: 127.0.0.1
```

The reverse proxy must be configured to reach the listener. Do not assume a loopback listener is accessible from another container.

### 🔐 HTTPS

For public access, use a valid HTTPS certificate.

You can terminate TLS at a properly configured reverse proxy or use the embedded HTTPS functionality if your release supports it.

If using a reverse proxy, restrict direct access to the underlying panel port and trust forwarded headers only when they come from a trusted proxy.

---

## 🔑 First Launch

Some releases use a one-time setup token when no owner account exists.

### Recommended procedure

1. ▶️ Start Velocity.
2. 📜 Read the proxy console.
3. 🔑 Retrieve the setup token, if required.
4. 🌐 Open the panel.
5. 👑 Create the owner account.
6. 🔐 Set a unique password.
7. 🔒 Configure HTTPS before remote public use.
8. 🧪 Test login and logout.

### If no setup token appears

Check whether:

- An owner account already exists.
- The existing SQLite database contains account information.
- The plugin encountered a database initialization error.
- The plugin failed before completing startup.

Do not delete the database as a first troubleshooting step.

---

## 🔗 API Authentication

If your release supports API key management:

1. 👑 Sign in with the appropriate account.
2. 🔑 Open the API or integrations section.
3. ➕ Create a key with a descriptive name.
4. 📋 Copy the key securely.
5. 🔐 Store it outside your source code.
6. 🧪 Test a permitted read request.
7. ♻️ Revoke the key if it is no longer required.

### 🔒 Read-only access

The documented API key model is intended for read-only integrations. Verify the implementation and permissions of your installed version before relying on this restriction.

Do not use a browser owner's session cookie as a substitute for a dedicated integration credential.

---

## 💻 Integration Examples

The `examples/` directory may contain small clients demonstrating how to call the REST API.

Depending on the release, examples may include:

| Language | Example location |
|---|---|
| 🐍 Python | `examples/python/` |
| 🟢 Node.js | `examples/node/` |
| 🤖 Discord.js | `examples/discordjs/` |
| 🐹 Go | `examples/go/` |
| ☕ Java | `examples/java/` |
| 🟣 C# / .NET | `examples/dotnet/` |
| 🐘 PHP | `examples/php/` |
| 💎 Ruby | `examples/ruby/` |
| 🦀 Rust | `examples/rust/` |
| 🪟 PowerShell | `examples/powershell/` |
| 🟠 Kotlin | `examples/kotlin/` |

Not every release necessarily contains every example listed above. Check the actual repository tree.

### 🔐 Recommended environment variables

For an external application, use environment variables such as:

```text
VERTEX_URL=https://panel.example.com
VERTEX_API_KEY=YOUR_SECRET_API_KEY
```

Never commit actual secret values to Git.

### 🧪 Integration best practices

- Set a finite HTTP timeout.
- Handle non-success HTTP responses.
- Validate JSON fields before using them.
- Avoid logging API keys or authorization headers.
- Encode UUIDs, backend names and query values appropriately.
- Test integrations with a disposable key before deployment.

The example clients are starting points, not a guarantee of independently versioned or officially published SDK packages.

---

## 💾 Database and Backups

The plugin uses SQLite for supported historical records and related panel data.

Depending on the release, stored information may include:

- 👥 Observed player activity.
- 📊 Historical metric samples.
- 🧾 Audit records.
- 🔐 Account metadata.
- 🔑 API key metadata.
- 🕒 Session-related information.

The precise schema and contents depend on the implementation.

### 📦 Recommended backup procedure

1. 🛑 Stop Velocity cleanly.
2. 📂 Locate the plugin data directory.
3. 💾 Copy the SQLite database and configuration files.
4. 🔐 Store the backup in a protected location.
5. ♻️ Test restoration before depending on the backup.

Protect backups as sensitive data. They may contain account records, historical player information and security-related information.

**Do not delete the SQLite database as a generic troubleshooting step.** Doing so may destroy account information and history.

---

## 🔄 Updating the Plugin

Use this procedure when updating between supported releases.

1. 📖 Read the release notes for the target version.
2. 🛑 Stop the Velocity proxy.
3. 💾 Back up the plugin data directory and `velocity.toml`.
4. 📦 Keep a copy of the currently installed JAR.
5. ⬇️ Download the correct JAR for the target release.
6. 🧹 Remove obsolete duplicate copies of the plugin, if present.
7. 📂 Upload the new JAR.
8. ▶️ Start Velocity.
9. 📜 Check the console for errors.
10. 🌐 Test login, dashboard, API and supported commands.
11. 🔀 Recheck routing configuration if you use the fallback editor.

### ⚠️ Version compatibility

Do not assume every release can be upgraded or downgraded without a backup.

Read any migration notes before replacing a working installation. If a release changes the database schema or configuration format, follow its specific migration instructions.

---

## 🧰 Troubleshooting

### ❌ The panel does not open

Check:

- The plugin initialized successfully.
- The configured port is correct.
- The listener is running.
- The bind address permits the intended connection.
- The Pterodactyl allocation and firewall rules are correct.
- The browser URL uses the correct scheme.
- The port is not already in use.

### 🔌 `Address already in use`

Another process may already occupy the selected port.

Choose an available port, update the configuration and restart Velocity. Do not change the Minecraft listener as a workaround for a web-port conflict.

### 💥 `ClassNotFoundException` or `NoClassDefFoundError`

These errors commonly indicate a missing class or dependency at runtime.

1. Read the full exception and `Caused by` chain.
2. Confirm that you uploaded the intended compiled JAR.
3. Verify the dependency declarations and Maven Shade configuration.
4. Check whether the required runtime dependency is packaged correctly.
5. Rebuild using the JDK required by the project.
6. Test on the exact target Velocity version.

Do not assume that the plugin works merely because Velocity continues starting after the exception.

### 🛠️ Maven build fails

Run:

```bash
java -version
mvn -version
mvn clean package
```

Inspect the first meaningful compilation or dependency-resolution error.

Confirm repository access and dependency versions before modifying `pom.xml`.

### 🔑 Login or initial setup fails

Check whether initial setup has already been completed and whether the existing database is accessible.

Also verify the setup token, password, rate limits and any enabled two-factor authentication.

Never share setup tokens or passwords when requesting support.

### 🚫 API returns `401 Unauthorized`

Check:

- The base URL and port.
- The endpoint path.
- The API key value.
- Whether the key has been revoked.
- Whether the request uses the expected authentication header.

Example:

```http
Authorization: Bearer YOUR_API_KEY
```

### 🛡️ API returns `403 Forbidden`

The authenticated identity may not have permission to perform the requested operation.

Verify the route's authorization requirements. If API keys are read-only in your version, use the appropriate authenticated panel account for supported administrative actions.

### 🖧 Backend shows offline or checking

Verify that the backend is registered in Velocity, its address is correct and the proxy can reach it.

A ping failure may indicate network issues, an incorrect address, an unavailable server or a timeout. A successful ping does not prove that the entire backend is functioning correctly.

### 🔀 Routing changes do not take effect

Check whether the installed version writes changes to the native `velocity.toml` file and requires a restart.

Inspect the configuration and any backup created by the plugin before restarting. Never edit production routing configuration without a backup.

### 💾 SQLite errors

Check disk space, directory permissions and database accessibility.

Stop Velocity before copying the database for a backup. Avoid deleting database files unless you have a verified recovery plan.

---

## 🧪 Testing and Validation

Before deploying a release publicly, verify the behavior of the exact artifact you intend to distribute.

### 🏗️ Build validation

- [ ] Maven completes successfully.
- [ ] The correct JDK is used.
- [ ] The expected JAR exists.
- [ ] Runtime dependencies are packaged correctly.
- [ ] The target Velocity version is compatible.

### 🌐 Runtime validation

- [ ] The proxy starts without plugin exceptions.
- [ ] The web listener binds to the intended address and port.
- [ ] The Minecraft listener remains available.
- [ ] The dashboard loads.
- [ ] The plugin shuts down cleanly.

### 🔐 Security validation

- [ ] Initial owner setup works as expected.
- [ ] Login and logout work.
- [ ] Session expiration and revocation work.
- [ ] Role permissions are enforced server-side.
- [ ] API key restrictions are tested.
- [ ] TOTP works if supported and enabled.
- [ ] Secrets do not appear in logs or safe configuration responses.

### 🎮 Gameplay and API validation

- [ ] Supported in-game commands work.
- [ ] Player events are recorded correctly.
- [ ] Backend information is accurate.
- [ ] Historical samples persist across restarts.
- [ ] API responses match the documented schema.
- [ ] Administrative actions are tested with the intended roles.

### 🚀 Release validation

- [ ] Release notes describe the actual changes.
- [ ] The tag matches the intended release commit.
- [ ] The JAR comes from the correct build.
- [ ] The source archive matches the released source.
- [ ] The installation instructions match the artifact.
- [ ] Known limitations are documented.

A green CI workflow, a valid ZIP archive or a successful JavaScript syntax check does not by itself prove that the plugin works on a real Velocity proxy.

---

## 🗺️ Roadmap and Limitations

The roadmap depends on the current implementation and the goals of future releases.

Potential areas for continued development include:

- 🧪 More automated unit and integration tests.
- 🔐 Additional security testing and review.
- 📊 Expanded analytics and historical reporting.
- 📡 More comprehensive backend health checks.
- 🔄 Improved live dashboard updates.
- 🛡️ Stronger account recovery workflows.
- 📚 Expanded API and integration documentation.
- 🧩 Additional external integration examples.
- ⚙️ Safer configuration editing and migration tooling.
- 🚀 Improved release automation.

### ⚠️ General limitations

- Historical records cannot be reconstructed for periods before recording began.
- Backend-specific plugin data requires an appropriate integration.
- Ping and sampled latency are not complete health checks.
- Configuration changes may require a proxy restart.
- Features can differ between versions.
- Security and runtime behavior must be validated on the exact release.

Consult the source code and release notes before assuming a planned feature is implemented.

---

## 🤝 Contributing

Contributions, bug reports and suggestions are welcome.

### 🐛 Reporting a bug

When opening an issue, include:

- 📌 The plugin release version.
- ☕ The Java version.
- 🧩 The Velocity version.
- 🖥️ The hosting environment, if relevant.
- 📜 The relevant sanitized error log.
- 🔁 Steps to reproduce the problem.
- ✅ The behavior you expected.
- ❌ The behavior you observed.

### 🔐 Protect sensitive information

Before publishing logs, remove:

- Passwords.
- API keys.
- Setup tokens.
- Session cookies.
- Discord webhook URLs.
- Keystore passwords.
- Private player information.

### 💡 Suggesting features

Describe the problem the feature would solve, the expected behavior and any relevant compatibility requirements.

---

## 📜 License

Vertex Velocity Web Panel is distributed under the **GNU General Public License v3.0 (GPL-3.0)**, subject to the license file included in the relevant release.

See [`LICENSE`](LICENSE) for the complete license terms.

### 🏷️ Vertex Studio branding

The Vertex Studio name, logos and other protected branding may be subject to separate trademark or branding terms.

See [`TRADEMARKS.md`](TRADEMARKS.md), if included in the repository, for the applicable policy.

The GPL license does not automatically grant permission to misrepresent an unofficial fork as an official Vertex Studio release.

---

## 🏢 Vertex Studio

**Vertex Studio** develops tools and software for gaming communities, server administration and developer workflows.

🌍 Website: [vertexdev-studio-website.vercel.app](https://vertexdev-studio-website.vercel.app/)

📦 Project: Vertex Velocity Web Panel

💻 Platform: Minecraft Velocity

🛠️ Build system: Maven

📜 License: GPL-3.0

---

<div align="center">

### 💙 Built for Minecraft network administrators.

**Vertex Velocity Web Panel — Control your Velocity network. From one web interface.**

⭐ If you find the project useful, consider starring the repository on GitHub.

</div>