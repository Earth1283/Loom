package io.github.earth1283.loom.listener

import io.github.earth1283.loom.api.MinecraftBindings
import io.github.earth1283.loom.api.ScriptManager
import org.bukkit.event.*
import org.bukkit.event.block.*
import org.bukkit.event.entity.*
import org.bukkit.event.player.*
import org.bukkit.event.server.*
import org.bukkit.event.weather.*
import org.bukkit.event.world.*

class LoomEventListener(private val scripts: ScriptManager) : Listener {

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
    fun onPlayerJoin(e: PlayerJoinEvent) {
        scripts.dispatchEvent("PlayerJoin", mapOf("player" to wrap(e.player)))
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
    fun onPlayerQuit(e: PlayerQuitEvent) {
        scripts.dispatchEvent("PlayerQuit", mapOf("player" to wrap(e.player)))
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
    fun onPlayerChat(e: AsyncPlayerChatEvent) {
        scripts.dispatchEvent("PlayerChat", mapOf(
            "player" to wrap(e.player),
            "message" to e.message
        ))
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onPlayerMove(e: PlayerMoveEvent) {
        if (e.from.blockX == e.to?.blockX && e.from.blockY == e.to?.blockY && e.from.blockZ == e.to?.blockZ) return
        scripts.dispatchEvent("PlayerMove", mapOf(
            "player" to wrap(e.player),
            "fromX" to e.from.blockX.toDouble(),
            "fromY" to e.from.blockY.toDouble(),
            "fromZ" to e.from.blockZ.toDouble(),
            "toX" to (e.to?.blockX?.toDouble() ?: 0.0),
            "toY" to (e.to?.blockY?.toDouble() ?: 0.0),
            "toZ" to (e.to?.blockZ?.toDouble() ?: 0.0),
        ))
    }

    @EventHandler
    fun onPlayerDeath(e: PlayerDeathEvent) {
        scripts.dispatchEvent("PlayerDeath", mapOf(
            "player" to wrap(e.entity),
            "message" to (e.deathMessage() ?: "")
        ))
    }

    @EventHandler
    fun onPlayerRespawn(e: PlayerRespawnEvent) {
        scripts.dispatchEvent("PlayerRespawn", mapOf("player" to wrap(e.player)))
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockBreak(e: BlockBreakEvent) {
        scripts.dispatchEvent("BlockBreak", mapOf(
            "player" to wrap(e.player),
            "block" to e.block.type.name,
            "world" to e.block.world.name,
            "x" to e.block.x.toDouble(),
            "y" to e.block.y.toDouble(),
            "z" to e.block.z.toDouble(),
        ))
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockPlace(e: BlockPlaceEvent) {
        scripts.dispatchEvent("BlockPlace", mapOf(
            "player" to wrap(e.player),
            "block" to e.block.type.name,
            "world" to e.block.world.name,
            "x" to e.block.x.toDouble(),
            "y" to e.block.y.toDouble(),
            "z" to e.block.z.toDouble(),
        ))
    }

    @EventHandler(ignoreCancelled = true)
    fun onEntityDamage(e: EntityDamageEvent) {
        val bindings = mutableMapOf<String, Any?>(
            "damage" to e.damage,
            "cause" to e.cause.name,
            "entityType" to e.entity.type.name,
        )
        if (e.entity is org.bukkit.entity.Player) bindings["player"] = wrap(e.entity as org.bukkit.entity.Player)
        scripts.dispatchEvent("EntityDamage", bindings)
    }

    @EventHandler(ignoreCancelled = true)
    fun onEntityDamageByEntity(e: EntityDamageByEntityEvent) {
        val bindings = mutableMapOf<String, Any?>(
            "damage" to e.damage,
            "cause" to e.cause.name,
            "entityType" to e.entity.type.name,
            "damagerType" to e.damager.type.name,
        )
        if (e.entity is org.bukkit.entity.Player) bindings["player"] = wrap(e.entity as org.bukkit.entity.Player)
        if (e.damager is org.bukkit.entity.Player) bindings["attacker"] = wrap(e.damager as org.bukkit.entity.Player)
        scripts.dispatchEvent("EntityDamageByEntity", bindings)
    }

    @EventHandler
    fun onEntitySpawn(e: EntitySpawnEvent) {
        scripts.dispatchEvent("EntitySpawn", mapOf(
            "entityType" to e.entity.type.name,
            "world" to e.entity.world.name,
            "x" to e.location.x,
            "y" to e.location.y,
            "z" to e.location.z,
        ))
    }

    @EventHandler
    fun onWeatherChange(e: WeatherChangeEvent) {
        scripts.dispatchEvent("WeatherChange", mapOf(
            "world" to e.world.name,
            "raining" to e.toWeatherState()
        ))
    }

    @EventHandler
    fun onPlayerLevelChange(e: PlayerLevelChangeEvent) {
        scripts.dispatchEvent("PlayerLevelChange", mapOf(
            "player" to wrap(e.player),
            "oldLevel" to e.oldLevel.toDouble(),
            "newLevel" to e.newLevel.toDouble(),
        ))
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlayerInteract(e: PlayerInteractEvent) {
        scripts.dispatchEvent("PlayerInteract", mapOf(
            "player" to wrap(e.player),
            "action" to e.action.name,
            "block" to (e.clickedBlock?.type?.name ?: "AIR"),
        ))
    }

    @EventHandler
    fun onServerLoad(e: ServerLoadEvent) {
        scripts.dispatchEvent("ServerLoad", mapOf("type" to e.type.name))
    }

    private fun wrap(player: org.bukkit.entity.Player) =
        MinecraftBindings.PlayerObject(player)
}
