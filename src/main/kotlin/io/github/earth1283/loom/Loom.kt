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
        saveDefaultConfig()
        loomConfig = LoomConfig.from(config)

        val scriptsDir = File(dataFolder, "scripts")
        scriptManager = ScriptManager(this, scriptsDir)
        scriptManager.init()

        authManager = AuthManager()

        if (loomConfig.webEnabled) {
            webServer = WebServer(loomConfig, scriptManager, authManager, this, logger)
            webServer?.start()
        }

        val loomCmd = LoomCommand(scriptManager, authManager, loomConfig, webServer)
        getCommand("loom")?.setExecutor(loomCmd)
        getCommand("loom")?.tabCompleter = loomCmd

        server.pluginManager.registerEvents(LoomEventListener(scriptManager), this)
        server.pluginManager.registerEvents(ScriptCommandListener(scriptManager), this)

        logger.info("Loom ${description.version} enabled. ${scriptManager.listScripts().size} script(s) on disk.")
    }

    override fun onDisable() {
        webServer?.stop()
        scriptManager.shutdown()
        logger.info("Loom disabled.")
    }
}
