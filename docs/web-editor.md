# Web Editor Guide

Loom's web editor is a Monaco-based IDE (the same editor engine that powers VS Code) served directly from the plugin. It provides syntax highlighting, tab completion, live diagnostics, and a built-in Git history panel.

## Accessing the editor

1. Make sure `web.enabled: true` in `config.yml`
2. Start the server — the editor is available at `http://your-server-ip:7070` (configurable)
3. Open the URL in any modern browser

## Authentication

The editor is locked until you authenticate in-game. This prevents stream snipers from accessing your scripts if you share your screen.

**Flow:**

1. Open the editor URL in your browser
2. You'll see a unique 8-character confirmation code, e.g. `ABC12345`
3. In Minecraft, run `/loom confirm ABC12345`
4. The browser unlocks immediately — no page refresh needed

Authentication is session-based. Closing the browser tab does not revoke your session. To revoke all active sessions:
```
/loom revoke all
```

To revoke a specific session (session ID shown in debug mode):
```
/loom revoke <sessionId>
```

## Editor layout

```
┌─────────────┬─────────────────────────────────┐
│  SCRIPTS    │  [toolbar]                       │
│             │                                  │
│  hello ●    │  (Monaco editor)                 │
│  greeter ●  │                                  │
│  warp   ○   │                                  │
│             ├─────────────────────────────────┤
│  + New      │  Problems │ Git Log │ Output     │
│  ⟳ Refresh  │  [bottom panel]                  │
└─────────────┴─────────────────────────────────┘
```

**Sidebar** — lists all `.loom` files in the scripts directory. Status dots:
- Green ● = RUNNING
- Red ● = ERROR (hover to see the error)
- Grey ○ = UNLOADED

**Toolbar** — script name, plus action buttons:
- **Load** — activate the script on the server
- **Unload** — deactivate without deleting
- **Save** — write to disk and auto-commit to git
- **Validate** — check for syntax errors without saving
- **Delete** — permanently delete the file
- **Git** — jump to the Git Log panel

**Bottom panel** — three tabs:
- **Problems** — live syntax/runtime error list with line numbers
- **Git Log** — commit history for the current script
- **Output** — timestamps log of editor actions (loads, saves, errors)

## Editing

### Syntax highlighting

The editor recognises all Loom keywords (`script`, `on`, `command`, `every`, `after`, `var`, `fun`, `if`, `else`, `while`, `for`, `return`, and more), built-in functions (`broadcast`, `player`, `fill`, etc.), and event names (`PlayerJoin`, `BlockBreak`, etc.) — each in its own colour.

### Tab completion

The editor provides completions for:
- All Loom keywords
- All built-in functions (with a snippet that puts the cursor inside the parentheses)
- All Minecraft event names
- Variables and functions defined in the current script (parsed live from the AST)
- `player.` properties — type `player.` and get a filtered list of every Player method and property

Completions appear automatically as you type. Press **Tab** or **Enter** to accept.

### Hover documentation

Hover over any built-in function name to see its signature and description in a tooltip.

### Live diagnostics

Errors appear as red underlines in the editor within ~600 ms of you stopping typing. The **Problems** tab lists every error with its line and column number. Clicking an error navigates to that line.

Diagnostics currently include:
- Lexer errors (invalid characters, unterminated strings)
- Parse errors (missing braces, unexpected tokens)

Runtime errors are only reported after loading; they appear in the Output tab.

## Auto-save and git

Every time you click **Save**, the plugin:

1. Writes the source to `plugins/Loom/scripts/<name>.loom`
2. Creates a git commit in the scripts directory with:
   - Your Minecraft username as the commit author
   - The commit message you entered (or `Auto-save <name>.loom` if left blank)

The scripts directory is a real git repository. You can also interact with it directly on the server filesystem (`git log`, `git diff`, etc.).

## Git Log panel

Switch to the **Git Log** tab while a script is open to see its commit history. Each entry shows:

```
[a3f7b2c]  Update greeter.loom  — Steve  at 2025-06-14 14:22
```

Click the short hash (e.g. `a3f7b2c`) to load that version into the editor. A confirmation dialog appears before overwriting your current unsaved changes.

After loading an old version, click **Save** to persist it as a new commit (restores the old state without rewriting history).

## REST API

The web server exposes a simple REST API. All endpoints require the `X-Session-Id` header set to your authenticated session ID.

### Scripts

| Method | Path | Description |
|---|---|---|
| `GET` | `/scripts` | List all scripts with state |
| `GET` | `/scripts/:name` | Get source of a script |
| `PUT` | `/scripts/:name` | Create a new script `{source}` |
| `POST` | `/scripts/:name` | Save source `{source, commitMessage}` |
| `DELETE` | `/scripts/:name` | Delete a script |
| `POST` | `/scripts/:name/load` | Load/activate |
| `POST` | `/scripts/:name/unload` | Unload |
| `POST` | `/scripts/:name/reload` | Reload |
| `POST` | `/scripts/:name/validate` | Validate source `{source}` without saving |

### Git

| Method | Path | Description |
|---|---|---|
| `GET` | `/git/:name/log` | Commit history for a script |
| `GET` | `/git/log` | Full repository log |
| `GET` | `/git/:name/diff` | Unstaged diff for a script |
| `GET` | `/git/status` | Repository status map |
| `GET` | `/git/:name/show/:hash` | Get file content at a commit |
| `POST` | `/git/:name/checkout/:hash` | Restore file to a commit on disk |
| `POST` | `/git/commit` | Manual commit all staged changes `{message}` |

### Auth

| Method | Path | Description |
|---|---|---|
| `GET` | `/auth/init` | Get a new `{sessionId, message}` with confirmation code |
| `GET` | `/auth/status/:sessionId` | Check `{authenticated, player}` |
| `POST` | `/auth/revoke/:sessionId` | Revoke a session |

### WebSocket

Connect to `ws://your-server:7070/ws/<sessionId>` for live language server messages.

**Client → server messages:**

```json
{ "type": "validate", "payload": { "name": "hello", "source": "..." } }
{ "type": "complete",  "payload": { "source": "...", "line": 5, "col": 12 } }
{ "type": "hover",     "payload": { "word": "broadcast" } }
{ "type": "ping" }
```

**Server → client messages:**

```json
{ "type": "diagnostics", "diagnostics": [ { "line": 3, "col": 5, "message": "...", "severity": "ERROR" } ] }
{ "type": "completions",  "items": [ { "label": "broadcast", "kind": "function", "doc": "..." } ] }
{ "type": "hover",        "word": "broadcast", "doc": "broadcast(msg) — Send a message..." }
{ "type": "pong" }
```

## Running behind a reverse proxy

To serve the editor over HTTPS with Nginx:

```nginx
location / {
    proxy_pass http://127.0.0.1:7070;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "Upgrade";
    proxy_set_header Host $host;
}
```

Set `web.host: "127.0.0.1"` in `config.yml` to restrict the plugin's listener to localhost only.
