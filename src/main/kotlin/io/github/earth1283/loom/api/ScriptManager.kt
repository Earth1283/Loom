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
        repo.init()
        loadAll()
    }

    fun shutdown() {
        scripts.values.forEach { it.unload() }
        scripts.clear()
    }

    fun loadAll() {
        scriptsDir.listFiles { f -> f.extension == "loom" }?.forEach { f ->
            load(f.nameWithoutExtension)
        }
    }

    fun load(name: String): List<Diagnostic> {
        val file = scriptFile(name)
        if (!file.exists()) return listOf()
        val script = LoomScript(name, file.readText(), plugin)
        scripts[name] = script
        return script.load()
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
