# Loom

**A lightweight scripting language for Minecraft — write server logic without touching Java.**

Loom gives you a clean, readable scripting language that runs inside your Paper server. Scripts live as plain `.loom` files, are edited in a Monaco-powered web IDE (the same editor engine as VS Code), and every save is automatically version-controlled with Git. No recompiling. No restarts. Just write and reload.

---

## For server admins

### What you can do

- **React to any game event** — player joins, deaths, block breaks, chat, weather changes, and more
- **Create custom commands** — register `/my-command` directly from a script with no plugin.yml changes
- **Automate recurring tasks** — run code every N ticks, seconds, or minutes
- **Manipulate the world** — place blocks, fill regions, summon entities, teleport players
- **Reward and manage players** — give items, heal, set game mode, send titles, play sounds
- **Edit scripts live** — open the browser editor, make a change, save — the script hot-reloads instantly

### Quick start

1. Drop `Loom-1.0-all.jar` into your `plugins/` folder and start the server
2. Open `http://your-server-ip:7070` in a browser
3. Run `/loom confirm <CODE>` in Minecraft (the code is shown in the browser)
4. Click **+ New Script**, write your script, click **Load**

### Example scripts

**Welcome message + diamond gift:**
```loom
script "Greeter" {
  on PlayerJoin(player) {
    player.message("Welcome back, ${player.name}!")
    give(player, Item.DIAMOND, 1)
  }
}
```

**Hourly broadcast:**
```loom
script "Announcer" {
  every 72000 ticks {
    broadcast("Remember to vote at vote.myserver.com!")
  }
}
```

**Custom command:**
```loom
script "Warp" {
  command "spawn" {
    teleport(sender, Location(0, 64, 0))
    sender.message("Teleported to spawn!")
  }
}
```

**Anti-fall kit on death:**
```loom
script "DeathKit" {
  on PlayerDeath(player) {
    after 3 seconds {
      give(player, Item.FEATHER, 4)
      player.message("Here's a soft landing kit.")
    }
  }
}
```

### In-game commands

| Command | Description |
|---|---|
| `/loom confirm <code>` | Authenticate a web editor session |
| `/loom load <name>` | Load and activate a script |
| `/loom unload <name>` | Unload a running script |
| `/loom reload [name]` | Reload one script or all of them |
| `/loom list` | List all scripts and their status |
| `/loom new <name>` | Create a new blank script file |
| `/loom delete <name>` | Delete a script |
| `/loom editor` | Get the clickable editor URL |
| `/loom info <name>` | Show a script's registered events and commands |
| `/loom revoke [all]` | Revoke active web editor sessions |

### Configuration (`plugins/Loom/config.yml`)

```yaml
web:
  enabled: true
  host: "0.0.0.0"   # bind address; use 127.0.0.1 if behind a reverse proxy
  port: 7070

scripts:
  auto-load: true    # load all .loom files on startup
  timeout-ms: 5000
  max-scripts: 100

git:
  auto-commit: true
  auto-commit-message: "Auto-save {script}"

security:
  ops-only: true     # restrict /loom and the editor to server operators

debug: false
```

---

## For developers

### Architecture

```
Loom/
├── lang/               # The Loom scripting language
│   ├── Lexer.kt        # text → token stream
│   ├── Token.kt        # token type enum
│   ├── Parser.kt       # tokens → AST
│   ├── Ast.kt          # sealed AST node hierarchy
│   ├── Interpreter.kt  # tree-walk evaluator
│   ├── Environment.kt  # lexically-scoped variable store
│   └── LoomError.kt    # typed errors + Diagnostic data class
├── api/
│   ├── MinecraftBindings.kt  # Paper API → Loom callable wrappers
│   ├── LoomScript.kt         # per-script lifecycle handle
│   └── ScriptManager.kt      # load/unload/dispatch coordinator
├── git/
│   └── ScriptRepository.kt   # JGit wrapper (init, commit, log, diff, checkout)
├── web/
│   ├── WebServer.kt           # Ktor/Netty embedded HTTP + WS server
│   ├── AuthManager.kt         # in-game confirm-code auth flow
│   └── LoomLanguageServer.kt  # WS language server (diagnostics, completion, hover)
├── command/
│   └── LoomCommand.kt         # /loom subcommand handler
├── listener/
│   ├── LoomEventListener.kt   # Bukkit event → script dispatch
│   └── ScriptCommandListener.kt # unknown /commands → Loom handlers
└── config/
    └── LoomConfig.kt           # typed config.yml wrapper
```

### Building

```bash
source setup.sh          # sets JAVA_HOME to Java 21
./gradlew shadowJar      # builds Loom-1.0-all.jar in build/libs/
./gradlew runServer      # starts a local Paper 1.21 test server
```

### Dependencies (all shaded into the jar)

| Library | Purpose |
|---|---|
| Ktor 3.1 (Netty) | Embedded HTTP + WebSocket server |
| JGit 7.3 | Git operations without a system git binary |
| kotlinx.serialization | JSON for the WebSocket protocol |
| kotlinx.coroutines | Ktor async runtime |

All dependencies are relocated under `io.github.earth1283.loom.shadow.*` to avoid classpath conflicts with other plugins.

### Extending Loom

**Add a new built-in function** — edit `MinecraftBindings.kt`:
```kotlin
env.define("myFunction", callable { args ->
    // args is List<Any?>
    val name = args.str(0)   // safe string cast
    val count = args.num(1)  // safe double cast
    // do something
    null // return value (null = void)
})
```

**Add a new built-in event** — edit `LoomEventListener.kt`:
```kotlin
@EventHandler
fun onMyEvent(e: MyBukkitEvent) {
    scripts.dispatchEvent("MyEvent", mapOf(
        "player" to MinecraftBindings.PlayerObject(e.player),
        "someValue" to e.someValue,
    ))
}
```

**Add completion/hover docs** — edit the `BUILTINS`, `BUILTIN_DOCS`, and `PLAYER_PROPS` lists in `LoomLanguageServer.kt`.

---

## Docs

- [Getting Started](docs/getting-started.md)
- [Language Reference](docs/language-reference.md)
- [Built-in API Reference](docs/api-reference.md)
- [Web Editor Guide](docs/web-editor.md)
- [Configuration Reference](docs/configuration.md)
- [Script Examples](docs/examples.md)

---

## License

MIT
