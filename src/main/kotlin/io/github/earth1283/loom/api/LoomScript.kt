package io.github.earth1283.loom.api

import io.github.earth1283.loom.lang.*
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask

class LoomScript(
    val name: String,
    val source: String,
    private val plugin: Plugin
) {
    enum class State { IDLE, RUNNING, ERROR }

    var state: State = State.IDLE
        private set
    var lastError: String? = null
        private set

    private val tasks = mutableListOf<BukkitTask>()
    private var interpreter: Interpreter? = null
    private var diagnostics: List<Diagnostic> = emptyList()

    fun validate(): List<Diagnostic> {
        return try {
            val tokens = Lexer(source).tokenize()
            val ast = Parser(tokens).parse()
            StaticAnalyzer().analyze(ast)
        } catch (e: LoomError) {
            listOf(e.toDiagnostic() ?: return emptyList())
        }
    }

    fun load(): List<Diagnostic> {
        unload()
        val staticDiags: List<Diagnostic> = try {
            val tokens = Lexer(source).tokenize()
            val ast = Parser(tokens).parse()
            StaticAnalyzer().analyze(ast)
        } catch (_: LoomError) { emptyList() }

        return try {
            val tokens = Lexer(source).tokenize()
            val ast = Parser(tokens).parse()
            val interp = Interpreter()
            val env = interp.globals

            MinecraftBindings.populate(env, plugin)

            interp.interpret(ast)

            for (task in interp.getScheduledTasks()) {
                val bt = if (task.periodTicks != null) {
                    plugin.server.scheduler.runTaskTimer(plugin, task.body, task.delayTicks, task.periodTicks)
                } else {
                    plugin.server.scheduler.runTaskLater(plugin, task.body, task.delayTicks)
                }
                tasks.add(bt)
            }

            interpreter = interp
            state = State.RUNNING
            lastError = null
            staticDiags
        } catch (e: LoomError) {
            state = State.ERROR
            lastError = e.message
            val runtimeDiag = e.toDiagnostic()
            if (runtimeDiag != null) staticDiags + runtimeDiag else staticDiags
        }
    }

    fun unload() {
        tasks.forEach { it.cancel() }
        tasks.clear()
        interpreter = null
        state = State.IDLE
    }

    fun dispatchEvent(event: String, bindings: Map<String, Any?>) {
        if (state != State.RUNNING) return
        try {
            interpreter?.dispatchEvent(event, bindings)
        } catch (e: LoomError.Runtime) {
            plugin.logger.warning("[$name] Runtime error in $event: ${e.message}")
        }
    }

    fun dispatchCommand(name: String, bindings: Map<String, Any?>): Boolean {
        if (state != State.RUNNING) return false
        return try {
            interpreter?.dispatchCommand(name, bindings) ?: false
        } catch (e: LoomError.Runtime) {
            plugin.logger.warning("[${this.name}] Runtime error in command '$name': ${e.message}")
            false
        }
    }

    fun getCommandNames(): List<String> = interpreter?.getCommandHandlers()?.map { it.name } ?: emptyList()
    fun getEventNames(): List<String> = interpreter?.getEventHandlers()?.map { it.event } ?: emptyList()
}
