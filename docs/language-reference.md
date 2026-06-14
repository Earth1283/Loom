# Loom Language Reference

Loom is a dynamically-typed scripting language with a clean, readable syntax designed for Minecraft server automation. It compiles to an in-memory AST and runs through a tree-walk interpreter inside the Paper plugin.

## Table of contents

- [Values and types](#values-and-types)
- [Variables](#variables)
- [Operators](#operators)
- [Strings and interpolation](#strings-and-interpolation)
- [Control flow](#control-flow)
- [Functions](#functions)
- [Lists](#lists)
- [Maps](#maps)
- [Script blocks](#script-blocks)
- [Event handlers](#event-handlers)
- [Commands](#commands)
- [Scheduling](#scheduling)
- [Imports](#imports)
- [Comments](#comments)
- [Error handling](#error-handling)

---

## Values and types

| Type | Examples |
|---|---|
| Number | `42`, `3.14`, `-7` |
| String | `"hello"`, `"it's ${player.name}"` |
| Boolean | `true`, `false` |
| Null | `null` |
| List | `[1, 2, 3]`, `["a", "b"]` |
| Map | `{"key": "value", "count": 10}` |
| Function | `fun(x) { return x * 2 }` |
| Player | returned by `player()`, `players()`, event params |

All numbers are 64-bit floats internally. Integer display is automatic: `10.0` prints as `10`.

---

## Variables

```loom
var x = 10
var name = "Steve"
var items = [1, 2, 3]
var empty = null
```

Variables are function-scoped. Re-assignment uses `=`:

```loom
x = x + 1
x += 5       // shorthand: += -= *= /=
```

---

## Operators

### Arithmetic
```loom
10 + 3     // 13
10 - 3     // 7
10 * 3     // 30
10 / 3     // 3.3333...
10 % 3     // 1
```

### Comparison
```loom
a == b    a != b
a < b     a <= b
a > b     a >= b
```

### Logic
```loom
a and b
a or b
not a
!a          // same as not a
```

### String concatenation
```loom
"Hello " + "world"   // "Hello world"
"count: " + 42       // "count: 42" (auto-coercion)
```

---

## Strings and interpolation

Strings use double quotes. Use `${...}` to embed any expression:

```loom
var name = "Alex"
var msg = "Hello, ${name}!"         // "Hello, Alex!"
var calc = "2 + 2 = ${2 + 2}"      // "2 + 2 = 4"
var prop = "Health: ${player.health}"
```

Escape sequences: `\n` `\t` `\r` `\"` `\\`

---

## Control flow

### if / else

```loom
if (player.health < 5) {
  player.message("You're almost dead!")
} else {
  player.message("You're fine.")
}
```

### while

```loom
var i = 0
while (i < 10) {
  broadcast("Tick ${i}")
  i += 1
}
```

### for ... in

Iterates over lists, map keys, or strings:

```loom
for (p in players()) {
  p.message("Hello!")
}

for (i in range(0, 5)) {
  broadcast("Count: ${i}")
}

var scores = {"Alice": 10, "Bob": 7}
for (name in keys(scores)) {
  broadcast("${name}: ${scores[name]}")
}
```

### break / continue

```loom
for (i in range(0, 100)) {
  if (i == 50) { break }
  if (i % 2 == 0) { continue }
  broadcast("Odd: ${i}")
}
```

---

## Functions

```loom
fun greet(name, greeting) {
  return "${greeting}, ${name}!"
}

var msg = greet("Steve", "Hello")
broadcast(msg)
```

Functions are first-class values. Assign them to variables or pass them as arguments:

```loom
fun apply(list, fn) {
  for (item in list) {
    fn(item)
  }
}

apply(players(), fun(p) {
  p.message("Yo!")
})
```

Lambdas (anonymous functions):

```loom
var double = fun(x) { return x * 2 }
broadcast(str(double(5)))   // "10"
```

Closures capture their enclosing scope:

```loom
fun makeCounter() {
  var n = 0
  return fun() {
    n += 1
    return n
  }
}

var counter = makeCounter()
counter()   // 1
counter()   // 2
```

---

## Lists

```loom
var fruits = ["apple", "banana", "cherry"]

fruits[0]               // "apple"
fruits[2] = "grape"    // modify
len(fruits)            // 3

push(fruits, "mango")  // append
pop(fruits)            // remove last, returns it
remove(fruits, 1)      // remove at index

for (f in fruits) {
  broadcast(f)
}
```

---

## Maps

```loom
var data = {"score": 100, "name": "Alex"}

data["score"]          // 100
data["score"] = 200    // modify
data["newKey"] = true  // add

keys(data)             // ["score", "name", "newKey"]
values(data)           // [200, "Alex", true]
contains(data, "name") // true
```

Property-style access works when iterating:

```loom
for (key in keys(data)) {
  broadcast("${key} = ${data[key]}")
}
```

---

## Script blocks

Every script file should have one top-level `script` declaration. The name is used for identification in `/loom list` and the editor:

```loom
script "MyScript" {
  // everything inside runs once at load time to register handlers
}
```

Top-level code outside a `script` block is valid but not idiomatic — use it only for shared helper functions:

```loom
fun formatTime(ticks) {
  return str(floor(ticks / 20)) + "s"
}

script "Timer" {
  on PlayerJoin(player) {
    player.message("Server uptime: ${formatTime(server.uptime)}")
  }
}
```

---

## Event handlers

Register a handler with `on EventName(params) { }`:

```loom
on PlayerJoin(player) {
  broadcast("${player.name} joined!")
}

on PlayerQuit(player) {
  broadcast("${player.name} left.")
}

on BlockBreak(player, block, world, x, y, z) {
  if (block == "DIAMOND_ORE") {
    player.message("Nice find!")
  }
}
```

Multiple handlers for the same event are allowed — all run in registration order.

### Available events

| Event | Parameters |
|---|---|
| `PlayerJoin` | `player` |
| `PlayerQuit` | `player` |
| `PlayerChat` | `player`, `message` |
| `PlayerMove` | `player`, `fromX`, `fromY`, `fromZ`, `toX`, `toY`, `toZ` |
| `PlayerDeath` | `player`, `message` |
| `PlayerRespawn` | `player` |
| `PlayerInteract` | `player`, `action`, `block` |
| `PlayerLevelChange` | `player`, `oldLevel`, `newLevel` |
| `BlockBreak` | `player`, `block`, `world`, `x`, `y`, `z` |
| `BlockPlace` | `player`, `block`, `world`, `x`, `y`, `z` |
| `EntityDamage` | `damage`, `cause`, `entityType`, `player`? |
| `EntityDamageByEntity` | `damage`, `cause`, `entityType`, `damagerType`, `player`?, `attacker`? |
| `EntitySpawn` | `entityType`, `world`, `x`, `y`, `z` |
| `WeatherChange` | `world`, `raining` |
| `ServerLoad` | `type` |

Parameters marked with `?` are only present when the entity involved is a player.

---

## Commands

Register a custom command with `command "name"(params) { }`:

```loom
command "heal" {
  sender.heal()
  sender.message("Healed!")
}

command "give" {
  var item = args[0]
  var amount = num(args[1])
  sender.give(item, amount)
  sender.message("Given ${amount} ${item}")
}
```

Inside a command handler, these variables are always available:

| Variable | Type | Description |
|---|---|---|
| `sender` | Player | The player who ran the command |
| `player` | Player | Same as `sender` |
| `args` | List | Command arguments as strings (e.g. `args[0]`) |
| `argCount` | Number | Number of arguments provided |

Loom commands run when a player types `/commandname`. If the command name collides with another plugin's command, that plugin wins.

---

## Scheduling

### Repeating — `every`

```loom
every 20 ticks {
  // runs every second (20 ticks = 1 second)
  broadcast("Tick!")
}

every 5 seconds {
  broadcast("5 seconds!")
}

every 1 minutes {
  broadcast("1 minute passed!")
}
```

### Delayed — `after`

```loom
after 100 ticks {
  broadcast("5 seconds after load")
}

after 30 seconds {
  broadcast("30 seconds later")
}
```

Scheduling can be combined with event handlers:

```loom
on PlayerDeath(player) {
  after 3 seconds {
    player.message("You respawned! Here's some food.")
    player.feed()
  }
}
```

All scheduled tasks are automatically cancelled when the script is unloaded.

---

## Imports

Import another `.loom` file to share functions:

```loom
import "utils"    // loads plugins/Loom/scripts/utils.loom

script "Main" {
  on PlayerJoin(player) {
    player.message(formatWelcome(player.name))
  }
}
```

The imported file is expected to define top-level functions. Import paths are relative to the scripts directory and do not include the `.loom` extension.

---

## Comments

```loom
// Single-line comment

/* Multi-line
   comment */
```

---

## Error handling

Loom does not have try/catch. Errors in event handlers and commands are caught by the plugin and logged to the server console with the script name and line number. The script continues running for subsequent events.

Syntax and parse errors are shown as red underlines in the web editor and appear in the Problems panel. They prevent the script from loading.
