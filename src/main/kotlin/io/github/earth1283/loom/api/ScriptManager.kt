package io.github.earth1283.loom.api

import io.github.earth1283.loom.git.ScriptRepository
import io.github.earth1283.loom.lang.Diagnostic
import org.bukkit.plugin.Plugin
import java.io.File

class ScriptManager(private val plugin: Plugin, private val scriptsDir: File) {
    private val scripts = mutableMapOf<String, LoomScript>()
    val repo: ScriptRepository by lazy { ScriptRepository(scriptsDir, plugin.logger) }

    fun init() {
        scriptsDir.mkdirs()
        plugin.logger.info("Script directory: ${scriptsDir.canonicalPath}")
        repo.init()
        loadAll()
    }

    fun shutdown() {
        plugin.logger.info("Unloading ${scripts.size} script(s)…")
        scripts.values.forEach { it.unload() }
        scripts.clear()
    }

    fun loadAll() {
        val files = scriptsDir.listFiles { f -> f.extension == "loom" } ?: return
        if (files.isEmpty()) {
            plugin.logger.info("No .loom scripts found in ${scriptsDir.name}/")
            return
        }
        plugin.logger.info("Found ${files.size} script(s) — loading…")
        files.sortedBy { it.name }.forEach { f -> load(f.nameWithoutExtension) }
    }

    fun load(name: String): List<Diagnostic> {
        val file = scriptFile(name)
        if (!file.exists()) return listOf()
        val script = LoomScript(name, file.readText(), plugin)
        scripts[name] = script
        val diags = script.load()
        val errCount  = diags.count { it.severity == Diagnostic.Severity.ERROR }
        val warnCount = diags.count { it.severity == Diagnostic.Severity.WARNING }
        val events = script.getEventNames()
        val cmds   = script.getCommandNames()
        if (script.state == LoomScript.State.RUNNING) {
            val detail = buildList {
                if (events.isNotEmpty()) add("${events.size} event(s)")
                if (cmds.isNotEmpty())   add("${cmds.size} command(s)")
            }.ifEmpty { listOf("no handlers") }.joinToString(", ")
            if (warnCount > 0)
                plugin.logger.warning("  [WARN ]  $name.loom — $detail  ($warnCount warning(s))")
            else
                plugin.logger.info("  [OK   ]  $name.loom — $detail")
        } else {
            val errMsg = script.lastError ?: "unknown error"
            plugin.logger.warning("  [ERROR]  $name.loom — $errMsg")
        }
        return diags
    }

    fun unload(name: String) {
        scripts[name]?.unload()
        scripts.remove(name)
    }

    fun reload(name: String): List<Diagnostic> {
        unload(name)
        return load(name)
    }

    fun reloadAll() {
        val names = scripts.keys.toList()
        scripts.values.forEach { it.unload() }
        scripts.clear()
        names.forEach { load(it) }
    }

    fun get(name: String): LoomScript? = scripts[name]
    fun all(): Map<String, LoomScript> = scripts.toMap()

    fun createScript(name: String, source: String = defaultSource(name)): File {
        val file = scriptFile(name)
        file.writeText(source)
        return file
    }

    fun saveScript(name: String, source: String) {
        scriptFile(name).writeText(source)
    }

    fun deleteScript(name: String) {
        unload(name)
        scriptFile(name).delete()
    }

    fun listScripts(): List<String> =
        scriptsDir.listFiles { f -> f.extension == "loom" }
            ?.map { it.nameWithoutExtension } ?: emptyList()

    fun validate(name: String, source: String): List<Diagnostic> {
        val script = LoomScript(name, source, plugin)
        return script.validate()
    }

    fun dispatchEvent(event: String, bindings: Map<String, Any?>) {
        scripts.values.forEach { it.dispatchEvent(event, bindings) }
    }

    fun dispatchCommand(name: String, bindings: Map<String, Any?>): Boolean =
        scripts.values.any { it.dispatchCommand(name, bindings) }

    fun getAllCommandNames(): Set<String> =
        scripts.values.flatMap { it.getCommandNames() }.toSet()

    private fun scriptFile(name: String) = File(scriptsDir, "$name.loom")

    private fun defaultSource(name: String) = """
script "$name" {
  // Write your Loom script here
  on PlayerJoin(player) {
    player.message("Hello, ${'$'}{player.name}!")
  }
}
""".trimIndent()
}
