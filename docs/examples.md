# Script Examples

Ready-to-use Loom scripts. Copy any of these into the web editor or drop them into `plugins/Loom/scripts/` and run `/loom load <name>`.

---

## Player experience

### Welcome kit

Gives new players a starter kit with a short delay so their inventory is ready.

```loom
script "WelcomeKit" {
  on PlayerJoin(player) {
    after 1 seconds {
      player.title("Welcome!", "Enjoy your stay")
      player.give(Item.STONE_SWORD, 1)
      player.give(Item.BREAD, 8)
      player.give(Item.TORCH, 16)
      player.give(Item.OAK_PLANKS, 32)
      player.message("You received a starter kit!")
    }
  }
}
```

### VIP welcome

Different messages for ops vs regular players.

```loom
script "VIPWelcome" {
  on PlayerJoin(player) {
    if (player.isOp) {
      broadcast("§6[Staff] ${player.name} is online!")
      player.title("§6Welcome back,", "§e${player.name}")
    } else {
      broadcast("§7${player.name} joined the server.")
    }
  }
}
```

### Death counter

Tracks deaths per session and broadcasts milestones.

```loom
script "DeathCounter" {
  var deaths = {}

  on PlayerDeath(player) {
    var name = player.name
    if (!contains(deaths, name)) {
      deaths[name] = 0
    }
    deaths[name] = deaths[name] + 1
    var d = deaths[name]
    if (d == 10) {
      broadcast("${name} has died 10 times. Rough session!")
    }
    player.actionBar("Deaths this session: ${d}")
  }

  on PlayerQuit(player) {
    // clean up on logout
    deaths[player.name] = 0
  }
}
```

### Respawn heal

Full-heals players 3 seconds after respawning.

```loom
script "RespawnHeal" {
  on PlayerRespawn(player) {
    after 3 seconds {
      player.heal()
      player.feed()
      player.message("Fully healed on respawn!")
    }
  }
}
```

---

## Server management

### Auto announcements

Broadcasts rotating messages every 10 minutes.

```loom
script "Announcer" {
  var messages = [
    "Vote for us at vote.myserver.com!",
    "Join our Discord at discord.gg/example",
    "Use /rules to see the server rules",
    "Need help? Type /help or ask a staff member",
  ]
  var index = 0

  every 12000 ticks {
    broadcast("§e[Info] " + messages[index])
    index = (index + 1) % len(messages)
  }
}
```

### Player count broadcaster

Posts player count to console every minute. Useful for log parsing.

```loom
script "PlayerCountLogger" {
  every 1 minutes {
    log("Player count: ${playerCount()}")
  }
}
```

### AFK checker

Warns players who haven't moved in 10 minutes.

```loom
script "AFKChecker" {
  var lastPos = {}

  on PlayerMove(player, fromX, fromY, fromZ, toX, toY, toZ) {
    lastPos[player.name] = {"x": toX, "y": toY, "z": toZ, "t": 0}
  }

  every 20 ticks {
    for (p in players()) {
      var name = p.name
      if (!contains(lastPos, name)) {
        lastPos[name] = {"x": p.x, "y": p.y, "z": p.z, "t": 0}
      }
      var info = lastPos[name]
      info["t"] = info["t"] + 1
      // 600 ticks = 30 seconds of no movement (adjust as needed)
      if (info["t"] == 600) {
        p.actionBar("§7You appear to be AFK")
      }
    }
  }
}
```

---

## World manipulation

### Random tree farm

Places a tree sapling every 5 minutes in a random spot inside a region.

```loom
script "TreeFarm" {
  every 6000 ticks {
    var x = floor(random() * 20) - 10
    var z = floor(random() * 20) - 10
    setBlock("world", x, 64, z, "OAK_SAPLING")
    setBlock("world", x, 63, z, "DIRT")
  }
}
```

### Platform builder command

Builds a glass platform under the player's feet.

```loom
script "Platform" {
  command "platform" {
    var px = floor(sender.x)
    var py = floor(sender.y) - 1
    var pz = floor(sender.z)
    fill(sender.world, px - 3, py, pz - 3, px + 3, py, pz + 3, "GLASS")
    sender.message("Platform created!")
  }
}
```

### Border warning

Warns players approaching a world border at ±500 blocks.

```loom
script "BorderWarning" {
  on PlayerMove(player, fromX, fromY, fromZ, toX, toY, toZ) {
    var dist = max(abs(toX), abs(toZ))
    if (dist > 480 and dist < 500) {
      player.actionBar("§cApproaching world border!")
    }
    if (dist >= 500) {
      player.teleport("world", 0, 64, 0)
      player.message("§cYou were teleported back — world border exceeded!")
    }
  }
}
```

---

## Mini-games

### Hot potato

Gives a random online player a flaming potato every 30 seconds. Anyone holding one when the next round fires is "out" and gets a damage effect.

```loom
script "HotPotato" {
  var holder = null

  every 600 ticks {
    var online = players()
    if (len(online) < 2) { return }

    if (holder != null) {
      holder.effect("HARM", 1, 0)
      broadcast("§c${holder.name} was holding the hot potato!")
    }

    var idx = floor(random() * len(online))
    holder = online[idx]
    holder.give(Item.BAKED_POTATO, 1)
    holder.playSound("ENTITY_BLAZE_SHOOT")
    broadcast("§e${holder.name} has the hot potato!")
  }
}
```

### Drop party

Drops a wave of items at a fixed location every hour.

```loom
script "DropParty" {
  every 72000 ticks {
    broadcast("§5§lDROP PARTY at spawn! /warp dropzone")
    var prizes = [
      Item.DIAMOND, Item.EMERALD, Item.GOLD_INGOT,
      Item.IRON_INGOT, Item.NETHERITE_SCRAP
    ]
    for (p in prizes) {
      setBlock("world", 0, 70, 0, p)
      after 1 seconds {
        setBlock("world", 0, 70, 0, "AIR")
      }
    }
  }
}
```

---

## Custom commands

### /heal command

Heals the player (or a named target).

```loom
script "HealCommand" {
  command "heal" {
    if (argCount == 0) {
      sender.heal()
      sender.feed()
      sender.message("§aYou were healed!")
    } else {
      var target = player(args[0])
      if (target == null) {
        sender.message("§cPlayer not found: ${args[0]}")
      } else {
        target.heal()
        target.feed()
        sender.message("§aHealed ${target.name}")
        target.message("§aYou were healed by ${sender.name}")
      }
    }
  }
}
```

### /fly toggle

```loom
script "FlyCommand" {
  var flyState = {}

  command "fly" {
    var name = sender.name
    if (!contains(flyState, name)) { flyState[name] = false }
    flyState[name] = !flyState[name]
    sender.setFlying(flyState[name])
    if (flyState[name]) {
      sender.message("§aFlight enabled")
    } else {
      sender.message("§cFlight disabled")
    }
  }
}
```

### /speed

Sets player walk speed.

```loom
script "SpeedCommand" {
  command "speed" {
    if (argCount < 1) {
      sender.message("Usage: /speed <0.1–1.0>")
      return
    }
    var spd = num(args[0])
    if (spd == null or spd < 0.1 or spd > 1.0) {
      sender.message("§cSpeed must be between 0.1 and 1.0")
      return
    }
    // Note: walkSpeed is not yet a direct Loom property;
    // this uses the effect workaround
    sender.effect("SPEED", 999999, floor(spd * 5))
    sender.message("§aSpeed set to ${args[0]}")
  }
}
```

---

## Utility scripts

### Server stats to action bar

Keeps all players informed of current player count via the action bar.

```loom
script "ServerStats" {
  every 100 ticks {
    for (p in players()) {
      p.actionBar("§7Players: §f${playerCount()} §7│ §7World: §f${p.world}")
    }
  }
}
```

### Join/leave logger

Logs all join and quit events to the console with timestamps for audit trails.

```loom
script "JoinLogger" {
  on PlayerJoin(player) {
    log("[JOIN] ${player.name} from ${player.ip}")
  }
  on PlayerQuit(player) {
    log("[QUIT] ${player.name}")
  }
}
```
