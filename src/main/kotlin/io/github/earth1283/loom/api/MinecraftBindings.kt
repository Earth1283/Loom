package io.github.earth1283.loom.api

import io.github.earth1283.loom.lang.Environment
import io.github.earth1283.loom.lang.LoomCallable
import io.github.earth1283.loom.lang.LoomObject
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

object MinecraftBindings {

    fun populate(env: Environment, plugin: Plugin) {
        // Output
        env.define("broadcast", callable { args -> Bukkit.broadcastMessage(args.str(0)) })
        env.define("log", callable { args -> plugin.logger.info(args.str(0)) })
        env.define("print", callable { args -> plugin.logger.info(args.str(0)) })

        // Players
        env.define("players", callable { _ ->
            Bukkit.getOnlinePlayers().map { PlayerObject(it) }.toMutableList()
        })
        env.define("player", callable { args ->
            Bukkit.getPlayer(args.str(0))?.let { PlayerObject(it) }
        })
        env.define("playerCount", callable { _ -> Bukkit.getOnlinePlayers().size.toDouble() })

        // World / blocks
        env.define("setBlock", callable { args ->
            val world = Bukkit.getWorld(args.str(0))
                ?: return@callable null
            val x = args.num(1).toInt(); val y = args.num(2).toInt(); val z = args.num(3).toInt()
            val mat = Material.matchMaterial(args.str(4).uppercase())
                ?: return@callable null
            world.getBlockAt(x, y, z).type = mat
            null
        })
        env.define("getBlock", callable { args ->
            val world = Bukkit.getWorld(args.str(0)) ?: return@callable null
            val x = args.num(1).toInt(); val y = args.num(2).toInt(); val z = args.num(3).toInt()
            world.getBlockAt(x, y, z).type.name
        })
        env.define("fill", callable { args ->
            val world = Bukkit.getWorld(args.str(0)) ?: return@callable null
            val mat = Material.matchMaterial(args.str(7).uppercase()) ?: return@callable null
            val x1 = args.num(1).toInt(); val y1 = args.num(2).toInt(); val z1 = args.num(3).toInt()
            val x2 = args.num(4).toInt(); val y2 = args.num(5).toInt(); val z2 = args.num(6).toInt()
            for (x in minOf(x1, x2)..maxOf(x1, x2))
                for (y in minOf(y1, y2)..maxOf(y1, y2))
                    for (z in minOf(z1, z2)..maxOf(z1, z2))
                        world.getBlockAt(x, y, z).type = mat
            null
        })

        // Entities
        env.define("summon", callable { args ->
            val world = Bukkit.getWorld(args.str(0)) ?: return@callable null
            val type = try { EntityType.valueOf(args.str(4).uppercase()) } catch (_: Exception) { return@callable null }
            val loc = Location(world, args.num(1), args.num(2), args.num(3))
            world.spawnEntity(loc, type)
            null
        })

        // Server info
        env.define("serverVersion", callable { _ -> Bukkit.getVersion() })
        env.define("maxPlayers", callable { _ -> Bukkit.getMaxPlayers().toDouble() })
        env.define("worlds", callable { _ -> Bukkit.getWorlds().map { it.name }.toMutableList() })

        // Math helpers
        env.define("floor", callable { args -> Math.floor(args.num(0)) })
        env.define("ceil", callable { args -> Math.ceil(args.num(0)) })
        env.define("round", callable { args -> Math.round(args.num(0)).toDouble() })
        env.define("abs", callable { args -> Math.abs(args.num(0)) })
        env.define("min", callable { args -> minOf(args.num(0), args.num(1)) })
        env.define("max", callable { args -> maxOf(args.num(0), args.num(1)) })
        env.define("random", callable { _ -> Math.random() })
        env.define("sqrt", callable { args -> Math.sqrt(args.num(0)) })
        env.define("pow", callable { args -> Math.pow(args.num(0), args.num(1)) })

        // String helpers
        env.define("str", callable { args -> args.str(0) })
        env.define("num", callable { args -> args.str(0).toDoubleOrNull() })
        env.define("len", callable { args ->
            when (val v = args[0]) {
                is String -> v.length.toDouble()
                is List<*> -> v.size.toDouble()
                is Map<*, *> -> v.size.toDouble()
                else -> null
            }
        })
        env.define("range", callable { args ->
            val start = args.num(0).toInt(); val end = args.num(1).toInt()
            (start until end).map { it.toDouble() }.toMutableList()
        })
        env.define("keys", callable { args -> (args[0] as? Map<*, *>)?.keys?.toMutableList() })
        env.define("values", callable { args -> (args[0] as? Map<*, *>)?.values?.toMutableList() })
        env.define("contains", callable { args ->
            when (val col = args[0]) {
                is List<*> -> col.contains(args[1])
                is Map<*, *> -> col.containsKey(args[1])
                is String -> col.contains(args.str(1))
                else -> false
            }
        })
        env.define("join", callable { args ->
            val list = args[0] as? List<*> ?: return@callable null
            val sep = if (args.size > 1) args.str(1) else ", "
            list.joinToString(sep)
        })
        env.define("split", callable { args -> args.str(0).split(args.str(1)).toMutableList() })
        env.define("upper", callable { args -> args.str(0).uppercase() })
        env.define("lower", callable { args -> args.str(0).lowercase() })
        env.define("trim", callable { args -> args.str(0).trim() })
        env.define("replace", callable { args -> args.str(0).replace(args.str(1), args.str(2)) })
        env.define("startsWith", callable { args -> args.str(0).startsWith(args.str(1)) })
        env.define("endsWith", callable { args -> args.str(0).endsWith(args.str(1)) })
        env.define("substr", callable { args ->
            val s = args.str(0); val from = args.num(1).toInt()
            if (args.size > 2) s.substring(from, args.num(2).toInt()) else s.substring(from)
        })

        // List helpers
        env.define("push", callable { args ->
            (args[0] as? MutableList<Any?>)?.add(args[1]); null
        })
        env.define("pop", callable { args ->
            (args[0] as? MutableList<*>)?.removeLastOrNull()
        })
        env.define("remove", callable { args ->
            (args[0] as? MutableList<*>)?.removeAt(args.num(1).toInt()); null
        })
        env.define("sort", callable { args ->
            @Suppress("UNCHECKED_CAST")
            (args[0] as? MutableList<Any?>)?.sortWith(compareBy { it.toString() }); null
        })

        // Item constants
        env.define("Item", ItemConstants)
    }

    // ---- Player wrapper ----

    class PlayerObject(val player: Player) : LoomObject {
        override fun typeName() = "Player"
        override fun toLoomString() = player.name

        override fun getProperty(name: String): Any? = when (name) {
            "name" -> player.name
            "uuid" -> player.uniqueId.toString()
            "health" -> player.health
            "maxHealth" -> player.maxHealth
            "foodLevel" -> player.foodLevel.toDouble()
            "level" -> player.level.toDouble()
            "exp" -> player.exp.toDouble()
            "gameMode" -> player.gameMode.name
            "world" -> player.world.name
            "x" -> player.location.x
            "y" -> player.location.y
            "z" -> player.location.z
            "yaw" -> player.location.yaw.toDouble()
            "pitch" -> player.location.pitch.toDouble()
            "isOp" -> player.isOp
            "isFlying" -> player.isFlying
            "isSneaking" -> player.isSneaking
            "isSprinting" -> player.isSprinting
            "ping" -> player.ping.toDouble()
            "ip" -> player.address?.hostString
            "message" -> callable { args -> player.sendMessage(args.str(0)) }
            "kick" -> callable { args -> player.kickPlayer(args.str(0)) }
            "teleport" -> callable { args ->
                val world = Bukkit.getWorld(args.str(0)) ?: player.world
                player.teleport(Location(world, args.num(1), args.num(2), args.num(3)))
                null
            }
            "give" -> callable { args ->
                val mat = Material.matchMaterial(args.str(0).uppercase()) ?: return@callable null
                val amount = if (args.size > 1) args.num(1).toInt() else 1
                val stack = org.bukkit.inventory.ItemStack(mat, amount)
                player.inventory.addItem(stack)
                null
            }
            "heal" -> callable { args ->
                val amount = if (args.isEmpty()) player.maxHealth else args.num(0)
                player.health = minOf(player.maxHealth, player.health + amount)
                null
            }
            "feed" -> callable { args ->
                player.foodLevel = if (args.isEmpty()) 20 else args.num(0).toInt()
                null
            }
            "setGameMode" -> callable { args ->
                try { player.gameMode = org.bukkit.GameMode.valueOf(args.str(0).uppercase()) } catch (_: Exception) {}
                null
            }
            "setFlying" -> callable { args -> player.allowFlight = args[0] as Boolean; player.isFlying = args[0] as Boolean; null }
            "effect" -> callable { args ->
                val type = org.bukkit.potion.PotionEffectType.getByName(args.str(0).uppercase()) ?: return@callable null
                val duration = if (args.size > 1) args.num(1).toInt() * 20 else 200
                val amplifier = if (args.size > 2) args.num(2).toInt() else 0
                player.addPotionEffect(org.bukkit.potion.PotionEffect(type, duration, amplifier))
                null
            }
            "title" -> callable { args ->
                val title = args.str(0)
                val subtitle = if (args.size > 1) args.str(1) else ""
                @Suppress("DEPRECATION")
                player.sendTitle(title, subtitle, 10, 70, 20)
                null
            }
            "actionBar" -> callable { args ->
                val bc = net.md_5.bungee.api.ChatMessageType.ACTION_BAR
                player.spigot().sendMessage(bc, net.md_5.bungee.api.chat.TextComponent(args.str(0)))
                null
            }
            "playSound" -> callable { args ->
                val sound = try { org.bukkit.Sound.valueOf(args.str(0).uppercase()) } catch (_: Exception) { return@callable null }
                player.playSound(player.location, sound, 1f, 1f)
                null
            }
            else -> null
        }

        override fun setProperty(name: String, value: Any?) {
            when (name) {
                "health" -> player.health = (value as? Double) ?: player.health
                "foodLevel" -> player.foodLevel = (value as? Double)?.toInt() ?: player.foodLevel
                "level" -> player.level = (value as? Double)?.toInt() ?: player.level
                "gameMode" -> try { player.gameMode = org.bukkit.GameMode.valueOf(value.toString().uppercase()) } catch (_: Exception) {}
            }
        }
    }

    // ---- Item name constants ----

    object ItemConstants : LoomObject {
        override fun typeName() = "Item"
        override fun getProperty(name: String): Any? =
            Material.matchMaterial(name)?.name ?: Material.matchMaterial(name.uppercase())?.name
    }

    // ---- Helpers ----

    private fun callable(fn: (List<Any?>) -> Any?): LoomCallable = fn

    private fun List<Any?>.str(i: Int) = getOrNull(i)?.toString() ?: ""
    private fun List<Any?>.num(i: Int) = (getOrNull(i) as? Double) ?: 0.0
}
