# Getting Started with Loom

## Installation

1. Download `Loom-1.0-all.jar` from the releases page
2. Place it in your server's `plugins/` folder
3. Start or restart your Paper 1.21 server
4. The plugin will create `plugins/Loom/` with:
   - `config.yml` — all configuration options
   - `scripts/` — your `.loom` script files (a git repo is auto-initialized here)

## Your first script

### 1. Create a script file

In-game, run:
```
/loom new hello
```

This creates `plugins/Loom/scripts/hello.loom` with a starter template.

### 2. Edit it in the web editor

Run `/loom editor` in-game — you'll get a clickable link to `http://your-server:7070`.

Open it in your browser. You'll see a confirmation code:

```
Open Minecraft and run:
/loom confirm ABC12345
```

Run that command in-game. The browser unlocks immediately.

### 3. Write your script

Select `hello` from the sidebar. Replace the content with:

```loom
script "Hello" {
  on PlayerJoin(player) {
    player.message("Hey ${player.name}, welcome!")
    player.title("Welcome!", "to My Server")
  }
}
```

### 4. Save and load

- Click **Save** (enter a commit message or leave blank for auto)
- Click **Load** to activate the script

The editor shows a green **[RUNNING]** status. Join or rejoin and you'll see your message.

### 5. Make a live change

Edit the message to something different, save, then click **Reload**. The script hot-reloads without a server restart.

## Script file structure

Every Loom script follows this pattern:

```loom
script "Name" {
  // event handlers, commands, and schedules go here
}
```

Everything inside the `script { }` block runs once on load to register your handlers. The handlers themselves run when their triggering event occurs.

## Without the web editor

You can also manage scripts entirely from the console/in-game:

```bash
# Create and edit the file directly on disk
nano plugins/Loom/scripts/myscript.loom

# Load it
/loom load myscript

# After editing the file
/loom reload myscript

# See all scripts
/loom list
```

## Next steps

- [Language Reference](language-reference.md) — full syntax guide
- [API Reference](api-reference.md) — all built-in functions and Player properties
- [Script Examples](examples.md) — ready-to-use scripts
- [Web Editor Guide](web-editor.md) — git history, diff view, keyboard shortcuts
- [Configuration](configuration.md) — config.yml options
