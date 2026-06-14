package io.github.earth1283.loom.listener

import io.github.earth1283.loom.api.MinecraftBindings
import io.github.earth1283.loom.api.ScriptManager
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent

/**
 * Routes unknown /commands to Loom script command handlers.
 * Scripts register commands via `command "name" { ... }`.
 */
class ScriptCommandListener(private val scripts: ScriptManager) : Listener {

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onPlayerCommand(e: PlayerCommandPreprocessEvent) {
        val msg = e.message
        if (!msg.startsWith("/")) return
        val parts = msg.removePrefix("/").split(" ")
        val cmdName = parts[0].lowercase()

        if (cmdName !in scripts.getAllCommandNames()) return

        e.isCancelled = true
        val args = parts.drop(1)
        val bindings = mapOf(
            "sender" to MinecraftBindings.PlayerObject(e.player),
            "player" to MinecraftBindings.PlayerObject(e.player),
            "args" to args.toMutableList(),
            "argCount" to args.size.toDouble(),
        )
        scripts.dispatchCommand(cmdName, bindings)
    }
}
