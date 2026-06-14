# Configuration Reference

Loom's configuration file lives at `plugins/Loom/config.yml`. All settings have safe defaults — you don't need to touch anything to get started, but everything is adjustable.

Edit the file and run `/loom reload-config` (or restart) to apply changes.

---

## Full annotated config

```yaml
# ┌─────────────────────────────────────────────────────────────────┐
# │                     Loom Configuration                          │
# └─────────────────────────────────────────────────────────────────┘

web:
  # Enable or disable the built-in Monaco web editor.
  # If disabled, scripts can still be managed via /loom commands
  # and by editing .loom files directly on disk.
  enabled: true

  # Host/IP address to bind the web server to.
  # Use "0.0.0.0" to listen on all network interfaces (default).
  # Use "127.0.0.1" if you're placing a reverse proxy (Nginx, Caddy)
  # in front and don't want the port exposed directly.
  host: "0.0.0.0"

  # TCP port the editor is served on.
  # Make sure this port is open in your firewall/security group
  # if you want remote access.
  port: 7070

scripts:
  # Automatically load all .loom files found in plugins/Loom/scripts/
  # when the plugin starts up. Set to false to require manual /loom load.
  auto-load: true

  # Maximum time (milliseconds) a single script call (event handler,
  # command body, etc.) is allowed to run before the plugin logs a
  # warning. This is a soft limit — scripts are not forcibly killed,
  # but long-running synchronous code will lag the main server thread.
  timeout-ms: 5000

  # Maximum number of scripts that can be loaded simultaneously.
  # Prevents runaway script creation from exhausting server resources.
  max-scripts: 100

git:
  # Automatically create a git commit every time a script is saved
  # from the web editor. The committer is the authenticated player's
  # Minecraft username.
  auto-commit: true

  # Template for auto-commit messages.
  # {script} is replaced with the script's filename (without extension).
  auto-commit-message: "Auto-save {script}"

security:
  # When true (default), only server operators can use /loom commands
  # and authenticate with the web editor.
  # Set to false if you want non-op players to run scripts
  # (combine with a permissions plugin for fine-grained control).
  ops-only: true

# Enable verbose debug logging. Prints extra information about
# script loading, event dispatch, and web server activity.
# Has a small performance impact. Leave false in production.
debug: false
```

---

## Option reference

### `web.enabled`
**Default:** `true`  
**Type:** Boolean

Set to `false` to disable the HTTP/WebSocket server entirely. All REST API endpoints and the Monaco editor will be unavailable. Scripts can still be managed with `/loom` commands.

### `web.host`
**Default:** `"0.0.0.0"`  
**Type:** String (IP address)

The interface to bind the embedded web server to. `0.0.0.0` exposes the editor on all network interfaces. Use `127.0.0.1` if you're fronting with a reverse proxy like Nginx or Caddy.

### `web.port`
**Default:** `7070`  
**Type:** Integer (1–65535)

The port the editor and API are served on. Avoid ports already used by your server (25565 for game traffic, 25575 for RCON).

### `scripts.auto-load`
**Default:** `true`  
**Type:** Boolean

When enabled, all `.loom` files in `plugins/Loom/scripts/` are loaded when the plugin starts. When disabled, scripts must be loaded manually with `/loom load <name>`.

### `scripts.timeout-ms`
**Default:** `5000`  
**Type:** Long (milliseconds)

Advisory timeout for individual script executions. Loom does not currently hard-terminate long-running scripts (that would require a separate thread), but the runtime logs a warning to the console when a handler takes longer than this.

### `scripts.max-scripts`
**Default:** `100`  
**Type:** Integer

Maximum number of scripts that can be loaded at one time. Scripts beyond this limit are rejected with an error in the `/loom load` output.

### `git.auto-commit`
**Default:** `true`  
**Type:** Boolean

When enabled, each Save from the editor automatically creates a git commit. When disabled, you must manually commit via the editor's **Git → Commit** button or the `/git/commit` REST endpoint.

### `git.auto-commit-message`
**Default:** `"Auto-save {script}"`  
**Type:** String

Template for auto-generated commit messages. `{script}` is substituted with the script name (no extension). Example: `"Auto-save greeter"`.

### `security.ops-only`
**Default:** `true`  
**Type:** Boolean

When `true`, only server operators (`/op playername`) can:
- Run any `/loom` subcommand
- Authenticate with the web editor
- Load/unload/delete scripts

When `false`, any player can use `/loom`. If you disable this, consider using a permissions plugin to restrict specific subcommands.

### `debug`
**Default:** `false`  
**Type:** Boolean

Enables verbose output in the server console. Useful when diagnosing script loading issues, language server errors, or web authentication problems. Not recommended for production.

---

## Recommended production config

```yaml
web:
  enabled: true
  host: "127.0.0.1"   # reverse proxy only
  port: 7070

scripts:
  auto-load: true
  timeout-ms: 2000    # tighter limit
  max-scripts: 50

git:
  auto-commit: true
  auto-commit-message: "Save {script} via editor"

security:
  ops-only: true

debug: false
```
