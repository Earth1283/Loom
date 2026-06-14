package io.github.earth1283.loom.config

import org.bukkit.configuration.file.FileConfiguration

data class LoomConfig(
    val webEnabled: Boolean,
    val webHost: String,
    val webPort: Int,
    val autoLoadScripts: Boolean,
    val scriptTimeoutMs: Long,
    val maxScripts: Int,
    val gitAutoCommit: Boolean,
    val gitAutoCommitMessage: String,
    val allowedOpsOnly: Boolean,
    val debugMode: Boolean,
) {
    companion object {
        fun from(cfg: FileConfiguration) = LoomConfig(
            webEnabled = cfg.getBoolean("web.enabled", true),
            webHost = cfg.getString("web.host", "0.0.0.0") ?: "0.0.0.0",
            webPort = cfg.getInt("web.port", 7070),
            autoLoadScripts = cfg.getBoolean("scripts.auto-load", true),
            scriptTimeoutMs = cfg.getLong("scripts.timeout-ms", 5000L),
            maxScripts = cfg.getInt("scripts.max-scripts", 100),
            gitAutoCommit = cfg.getBoolean("git.auto-commit", true),
            gitAutoCommitMessage = cfg.getString("git.auto-commit-message", "Auto-save {script}") ?: "Auto-save {script}",
            allowedOpsOnly = cfg.getBoolean("security.ops-only", true),
            debugMode = cfg.getBoolean("debug", false),
        )
    }
}
