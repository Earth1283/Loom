# Loom API Reference

All functions and constants available in every Loom script.

---

## Player functions

### `players()` → List\<Player\>
Returns a list of all currently online players.
```loom
for (p in players()) {
  p.message("Hello!")
}
```

### `player(name)` → Player | null
Returns the player with the given name, or `null` if offline.
```loom
var p = player("Steve")
if (p != null) {
  p.message("Found you!")
}
```

### `playerCount()` → Number
Returns the number of online players.
```loom
broadcast("${playerCount()} players online")
```

---

## Player object properties

When you have a `Player` object (from an event parameter, `player()`, or `players()`), the following properties and methods are available.

### Read-only properties

| Property | Type | Description |
|---|---|---|
| `name` | String | Display name |
| `uuid` | String | UUID string |
| `health` | Number | Current health (0–maxHealth) |
| `maxHealth` | Number | Maximum health |
| `foodLevel` | Number | Hunger level (0–20) |
| `level` | Number | XP level |
| `exp` | Number | XP progress within current level (0.0–1.0) |
| `gameMode` | String | `SURVIVAL`, `CREATIVE`, `ADVENTURE`, `SPECTATOR` |
| `world` | String | World name |
| `x`, `y`, `z` | Number | Block position |
| `yaw`, `pitch` | Number | Look direction in degrees |
| `isOp` | Boolean | Whether the player is an operator |
| `isFlying` | Boolean | Currently flying |
| `isSneaking` | Boolean | Currently sneaking |
| `isSprinting` | Boolean | Currently sprinting |
| `ping` | Number | Connection latency in ms |
| `ip` | String | IP address string |

### Writable properties

| Property | Type | Notes |
|---|---|---|
| `health` | Number | Clamped to 0–maxHealth |
| `foodLevel` | Number | 0–20 |
| `level` | Number | XP level |
| `gameMode` | String | Must be a valid GameMode name |

```loom
player.health = 20
player.gameMode = "CREATIVE"
```

### Methods

#### `player.message(text)`
Sends a chat message to this player.
```loom
player.message("Hello!")
```

#### `player.kick(reason)`
Kicks the player with the given reason.
```loom
player.kick("Banned for cheating")
```

#### `player.teleport(world, x, y, z)`
Teleports the player.
```loom
player.teleport("world", 0, 64, 0)
player.teleport(player.world, player.x, player.y + 10, player.z)
```

#### `player.give(item, amount?)`
Adds items to the player's inventory. `item` is a Material name string.
```loom
player.give("DIAMOND", 5)
player.give(Item.GOLDEN_APPLE, 1)
player.give("DIAMOND_SWORD")   // amount defaults to 1
```

#### `player.heal(amount?)`
Heals the player. If `amount` is omitted, restores to `maxHealth`.
```loom
player.heal()        // full heal
player.heal(4)       // heal 4 hearts (2 HP per heart)
```

#### `player.feed(level?)`
Sets food level. Defaults to 20 (full).
```loom
player.feed()
player.feed(10)
```

#### `player.setGameMode(mode)`
Sets game mode by name.
```loom
player.setGameMode("CREATIVE")
```

#### `player.setFlying(bool)`
Enables or disables flight.
```loom
player.setFlying(true)
```

#### `player.effect(type, durationSeconds?, amplifier?)`
Applies a potion effect.
```loom
player.effect("SPEED", 30, 1)        // Speed II for 30 seconds
player.effect("NIGHT_VISION", 60)    // Night vision for 60 seconds
player.effect("REGENERATION")        // 10-second regen
```

#### `player.title(title, subtitle?)`
Shows a title screen. `subtitle` defaults to empty.
```loom
player.title("Welcome!", "to My Server")
player.title("You died!")
```

#### `player.actionBar(text)`
Shows text above the hotbar.
```loom
player.actionBar("Health: ${player.health}")
```

#### `player.playSound(sound)`
Plays a sound to this player at their location.
```loom
player.playSound("ENTITY_PLAYER_LEVELUP")
player.playSound("BLOCK_NOTE_BLOCK_PLING")
```

---

## World and block functions

### `setBlock(world, x, y, z, material)`
Places a block.
```loom
setBlock("world", 0, 64, 0, "STONE")
setBlock("world", 10, 70, 10, Item.DIAMOND_BLOCK)
```

### `getBlock(world, x, y, z)` → String
Returns the material name of the block at that position.
```loom
var mat = getBlock("world", 0, 64, 0)
if (mat == "GRASS_BLOCK") {
  broadcast("Found grass!")
}
```

### `fill(world, x1, y1, z1, x2, y2, z2, material)`
Fills a rectangular region with a block.
```loom
fill("world", -5, 64, -5, 5, 64, 5, "GOLD_BLOCK")
```

### `summon(world, x, y, z, entityType)`
Spawns an entity.
```loom
summon("world", 0, 70, 0, "CREEPER")
summon("world", player.x, player.y, player.z, "ZOMBIE")
```

### `worlds()` → List\<String\>
Returns the names of all loaded worlds.
```loom
for (w in worlds()) {
  broadcast("World: ${w}")
}
```

---

## Server functions

### `broadcast(message)`
Sends a message to all online players.
```loom
broadcast("Server is restarting in 5 minutes!")
```

### `log(message)`
Prints to the server console.
```loom
log("Script loaded successfully")
```

### `print(message)`
Alias for `log`.

### `serverVersion()` → String
Returns the server version string.

### `maxPlayers()` → Number
Returns the configured max player count.

---

## Item constants

`Item` is a special object that maps property names to Material name strings. You can use it wherever a material string is expected:

```loom
player.give(Item.DIAMOND, 3)
setBlock("world", 0, 64, 0, Item.EMERALD_BLOCK)
```

Any valid Paper/Bukkit `Material` name works as a property on `Item`. Names are case-insensitive.

---

## Math functions

| Function | Description |
|---|---|
| `floor(n)` | Round down |
| `ceil(n)` | Round up |
| `round(n)` | Round to nearest integer |
| `abs(n)` | Absolute value |
| `min(a, b)` | Smaller of two numbers |
| `max(a, b)` | Larger of two numbers |
| `random()` | Random number in [0.0, 1.0) |
| `sqrt(n)` | Square root |
| `pow(base, exp)` | Exponentiation |

```loom
var r = floor(random() * 6) + 1   // dice roll 1–6
var dist = sqrt(pow(dx, 2) + pow(dz, 2))
```

---

## String functions

| Function | Returns | Description |
|---|---|---|
| `str(value)` | String | Convert any value to string |
| `num(string)` | Number\|null | Parse string to number |
| `len(value)` | Number | Length of string, list, or map |
| `upper(s)` | String | Uppercase |
| `lower(s)` | String | Lowercase |
| `trim(s)` | String | Strip leading/trailing whitespace |
| `replace(s, from, to)` | String | Replace all occurrences |
| `startsWith(s, prefix)` | Boolean | |
| `endsWith(s, suffix)` | Boolean | |
| `substr(s, start, end?)` | String | Substring |
| `split(s, sep)` | List | Split by separator |
| `join(list, sep?)` | String | Join with separator (default `", "`) |

```loom
var words = split("hello world foo", " ")   // ["hello", "world", "foo"]
var upper = upper("hello")                   // "HELLO"
var n = num("42")                            // 42.0
```

---

## List functions

| Function | Description |
|---|---|
| `len(list)` | Number of elements |
| `push(list, item)` | Append to end |
| `pop(list)` | Remove and return last element |
| `remove(list, index)` | Remove element at index |
| `sort(list)` | Sort in-place (lexicographic) |
| `contains(list, item)` | True if item is in list |
| `range(start, end)` | List of integers from start to end (exclusive) |

---

## Map functions

| Function | Description |
|---|---|
| `len(map)` | Number of entries |
| `keys(map)` | List of keys |
| `values(map)` | List of values |
| `contains(map, key)` | True if key exists |
