package io.github.earth1283.loom

import io.github.earth1283.loom.api.ScriptManager
import io.github.earth1283.loom.command.LoomCommand
import io.github.earth1283.loom.config.LoomConfig
import io.github.earth1283.loom.listener.LoomEventListener
import io.github.earth1283.loom.listener.ScriptCommandListener
import io.github.earth1283.loom.web.AuthManager
import io.github.earth1283.loom.web.WebServer
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

class Loom : JavaPlugin() {
    lateinit var loomConfig: LoomConfig
        private set
    lateinit var scriptManager: ScriptManager
        private set
    lateinit var authManager: AuthManager
        private set
    private var webServer: WebServer? = null

    override fun onEnable() {
        val bar = "─".repeat(48)
        logger.info(bar)
        logger.info("Loom v${description.version}  starting up…")
        logger.info(bar)

        // Config
        logger.info("Loading configuration…")
        saveDefaultConfig()
        loomConfig = LoomConfig.from(config)
        logger.info("  Web editor : ${if (loomConfig.webEnabled) "enabled  (${loomConfig.webHost}:${loomConfig.webPort})" else "disabled"}")
        logger.info("  Auto-load  : ${loomConfig.autoLoadScripts}")
        logger.info("  Timeout    : ${loomConfig.scriptTimeoutMs} ms")
        logger.info("  Max scripts: ${loomConfig.maxScripts}")
        logger.info("  Git commits: ${loomConfig.gitAutoCommit}")
        logger.info("  Ops only   : ${loomConfig.allowedOpsOnly}")
        if (loomConfig.debugMode) logger.info("  Debug mode : ON")

        // Script engine
        logger.info("Initialising script engine…")
        val scriptsDir = File(dataFolder, "scripts")
        scriptManager = ScriptManager(this, scriptsDir)
        scriptManager.init()

        // Auth
        authManager = AuthManager()

        // Web server
        if (loomConfig.webEnabled) {
            logger.info("Starting web server on ${loomConfig.webHost}:${loomConfig.webPort}…")
            webServer = WebServer(loomConfig, scriptManager, authManager, this, logger)
            webServer?.start()
        } else {
            logger.info("Web server is disabled — skipping.")
        }

        // Commands & listeners
        logger.info("Registering commands and event listeners…")
        val loomCmd = LoomCommand(scriptManager, authManager, loomConfig, webServer)
        getCommand("loom")?.setExecutor(loomCmd)
        getCommand("loom")?.tabCompleter = loomCmd
        server.pluginManager.registerEvents(LoomEventListener(scriptManager), this)
        server.pluginManager.registerEvents(ScriptCommandListener(scriptManager), this)

        // Summary
        val all = scriptManager.all()
        val running = all.values.count { it.state == io.github.earth1283.loom.api.LoomScript.State.RUNNING }
        val errors  = all.values.count { it.state == io.github.earth1283.loom.api.LoomScript.State.ERROR  }
        logger.info(bar)
        logger.info("Ready — ${all.size} script(s) loaded: $running RUNNING, $errors ERROR")
        if (loomConfig.webEnabled)
            logger.info("Editor → http://${if (loomConfig.webHost == "0.0.0.0") "localhost" else loomConfig.webHost}:${loomConfig.webPort}/")
        logger.info(bar)
    }

    override fun onDisable() {
        val bar = "─".repeat(48)
        logger.info(bar)
        logger.info("Loom shutting down…")
        webServer?.stop()
        logger.info("Web server stopped.")
        scriptManager.shutdown()
        logger.info("Script engine stopped.")
        logger.info(bar)
    }
}
