# Loom

**A clean, predictable scripting language for Minecraft — because writing server logic shouldn't require guessing the correct English sentence.**

Loom gives you a structured, modern scripting language that runs natively inside your Paper server. **Stop fighting parsers that try to interpret pseudo-English.** With Loom, you get real syntax, real scoping, and real developer tooling. Scripts live as plain `.loom` files, are edited in a Monaco-powered web IDE (the same engine as VS Code), and every save is automatically version-controlled with Git. No recompiling. No restarts. No scouring wikis to figure out why your phrasing won't compile. Just write, reload, and run.

---

## Why Loom over Skript?

Skript was revolutionary for its time, but trying to program in conversational English is a trap. What starts as an easy `on join: send "hello"` quickly turns into a nightmare of unpredictable syntax, indentation errors, and guessing which exact phrasing the parser wants today. English is ambiguous; code shouldn't be.

* **Real, predictable syntax:** Loom uses a familiar, C-family/Kotlin-style syntax with braces and standard functions. If you know *any* standard programming language, you already know how to write Loom.
* **Actual Developer Tooling:** You aren't left editing `.sk` files in Notepad++ hoping for the best. Loom ships with a web IDE featuring a Language Server Protocol (LSP) for **real-time diagnostics, autocompletion, and hover docs**.
* **Automatic Version Control:** You don't have to worry about breaking your server and losing your working state. Loom automatically Git-commits every save you make in the editor.

---

## For server admins

### What you can do

* **React to any game event** — player joins, deaths, block breaks, chat, weather changes, and more, using strictly typed event objects.
* **Create custom commands** — register `/my-command` directly from a script with actual lexical scoping.
* **Automate recurring tasks** — run code every N ticks, seconds, or minutes with predictable loops.
* **Manipulate the world** — place blocks, fill regions, summon entities, teleport players.
* **Edit scripts live** — open the browser editor, make a change, save — the script hot-reloads instantly without dropping the server TPS.

### Quick start

1. Drop `Loom-1.0-all.jar` into your `plugins/` folder and start the server
2. Open `http://your-server-ip:7070` in a browser
3. Run `/loom confirm <CODE>` in Minecraft (the code is shown in the browser)
4. Click **+ New Script**, write your script, click **Load**

### Example scripts

Notice the syntax. It's explicit, standard, and won't break just because you added a plural where it expected a singular.

**Welcome message + diamond gift:**

```loom
script "Greeter" {
  on PlayerJoin(player) {
    // Standard string interpolation and function calls.
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
    // Nested blocks that make visual sense, instead of relying on exact colon placement.
    after 3 seconds {
      give(player, Item.FEATHER, 4)
      player.message("Here's a soft landing kit.")
    }
  }
}

```

### In-game commands

| Command | Description |
| --- | --- |
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

Loom is built like a true programming language, not a regex engine glued to Bukkit events.

### Architecture

```
Loom/
├── lang/               # The Loom scripting language (Real AST, not regex parsing)
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
| --- | --- |
| Ktor 3.1 (Netty) | Embedded HTTP + WebSocket server |
| JGit 7.3 | Git operations without a system git binary |
| kotlinx.serialization | JSON for the WebSocket protocol |
| kotlinx.coroutines | Ktor async runtime |

All dependencies are relocated under `io.github.earth1283.loom.shadow.*` to avoid classpath conflicts with other plugins.

### Extending Loom

Because Loom doesn't rely on string matching to parse logic, extending it is as simple as defining standard functions and passing arguments.

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

**Add completion/hover docs** — edit the `BUILTINS`, `BUILTIN_DOCS`, and `PLAYER_PROPS` lists in `LoomLanguageServer.kt` so the web IDE can instantly provide context to admins writing scripts.

---

## Docs

* [Getting Started](docs/getting-started.md)
* [Language Reference](docs/language-reference.md)
* [Built-in API Reference](docs/api-reference.md)
* [Web Editor Guide](docs/web-editor.md)
* [Configuration Reference](docs/configuration.md)
* [Script Examples](docs/examples.md)

---

## License

MIT - If one of you guys sell it for money, it's going to be AGPLv3 
