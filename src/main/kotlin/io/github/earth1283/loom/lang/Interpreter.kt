package io.github.earth1283.loom.lang

typealias LoomCallable = (args: List<Any?>) -> Any?

class Interpreter(val globals: Environment = Environment()) {
    private var scheduledTasks = mutableListOf<ScheduledTask>()
    private var registeredEvents = mutableListOf<EventHandler>()
    private var registeredCommands = mutableListOf<CommandHandler>()

    data class ScheduledTask(val periodTicks: Long?, val delayTicks: Long, val body: () -> Unit)
    data class EventHandler(val event: String, val params: List<String>, val body: List<Stmt>, val env: Environment)
    data class CommandHandler(val name: String, val params: List<String>, val body: List<Stmt>, val env: Environment)

    fun interpret(stmts: List<Stmt>): List<Any?> {
        scheduledTasks.clear(); registeredEvents.clear(); registeredCommands.clear()
        return stmts.map { exec(it, globals) }
    }

    fun getScheduledTasks() = scheduledTasks.toList()
    fun getEventHandlers() = registeredEvents.toList()
    fun getCommandHandlers() = registeredCommands.toList()

    fun dispatchEvent(event: String, bindings: Map<String, Any?>) {
        for (handler in registeredEvents.filter { it.event == event }) {
            val env = handler.env.child()
            for ((k, v) in bindings) env.define(k, v)
            try { handler.body.forEach { exec(it, env) } }
            catch (_: LoomError.Return) {}
        }
    }

    fun dispatchCommand(name: String, bindings: Map<String, Any?>): Boolean {
        val handler = registeredCommands.firstOrNull { it.name == name } ?: return false
        val env = handler.env.child()
        for ((k, v) in bindings) env.define(k, v)
        try { handler.body.forEach { exec(it, env) } }
        catch (_: LoomError.Return) {}
        return true
    }

    private fun exec(stmt: Stmt, env: Environment): Any? = when (stmt) {
        is Stmt.ScriptDecl -> {
            val scriptEnv = env.child()
            stmt.body.forEach { exec(it, scriptEnv) }
            null
        }
        is Stmt.OnEvent -> {
            registeredEvents.add(EventHandler(stmt.event, stmt.params, stmt.body, env))
            null
        }
        is Stmt.CommandDecl -> {
            registeredCommands.add(CommandHandler(stmt.name, stmt.params, stmt.body, env))
            null
        }
        is Stmt.EverySchedule -> {
            val ticks = toTicks(eval(stmt.amount, env), stmt.unit)
            scheduledTasks.add(ScheduledTask(ticks, ticks) {
                val taskEnv = env.child()
                stmt.body.forEach { exec(it, taskEnv) }
            })
            null
        }
        is Stmt.AfterSchedule -> {
            val ticks = toTicks(eval(stmt.amount, env), stmt.unit)
            scheduledTasks.add(ScheduledTask(null, ticks) {
                val taskEnv = env.child()
                stmt.body.forEach { exec(it, taskEnv) }
            })
            null
        }
        is Stmt.VarDecl -> {
            env.define(stmt.name, stmt.initializer?.let { eval(it, env) })
            null
        }
        is Stmt.FunDecl -> {
            val fn: LoomCallable = { args ->
                val fnEnv = env.child()
                stmt.params.forEachIndexed { i, p -> fnEnv.define(p, args.getOrNull(i)) }
                try { stmt.body.forEach { exec(it, fnEnv) }; null }
                catch (r: LoomError.Return) { r.value }
            }
            env.define(stmt.name, fn)
            null
        }
        is Stmt.Return -> throw LoomError.Return(stmt.value?.let { eval(it, env) })
        is Stmt.Break -> throw LoomError.Break()
        is Stmt.Continue -> throw LoomError.Continue()
        is Stmt.If -> {
            if (isTruthy(eval(stmt.condition, env))) {
                val b = env.child(); stmt.thenBranch.forEach { exec(it, b) }
            } else stmt.elseBranch?.let { branch ->
                val b = env.child(); branch.forEach { exec(it, b) }
            }
            null
        }
        is Stmt.While -> {
            while (isTruthy(eval(stmt.condition, env))) {
                try {
                    val b = env.child(); stmt.body.forEach { exec(it, b) }
                } catch (_: LoomError.Break) { break }
                catch (_: LoomError.Continue) { continue }
            }
            null
        }
        is Stmt.For -> {
            val iter = eval(stmt.iterable, env)
            val list = when (iter) {
                is List<*> -> iter
                is Map<*, *> -> iter.keys.toList()
                is String -> iter.toList().map { it.toString() }
                else -> throw LoomError.Runtime(stmt.line, stmt.col, "Cannot iterate over ${typeName(iter)}")
            }
            for (item in list) {
                val b = env.child(); b.define(stmt.variable, item)
                try { stmt.body.forEach { exec(it, b) } }
                catch (_: LoomError.Break) { break }
                catch (_: LoomError.Continue) { continue }
            }
            null
        }
        is Stmt.Expression -> eval(stmt.expr, env)
        is Stmt.Import -> null // handled at script load time
    }

    @Suppress("UNCHECKED_CAST")
    fun eval(expr: Expr, env: Environment): Any? = when (expr) {
        is Expr.Literal -> expr.value
        is Expr.StringTemplate -> expr.parts.joinToString("") { loomStr(eval(it, env)) }
        is Expr.Variable -> env.get(expr.name, expr.line, expr.col)
        is Expr.Assign -> {
            val value = eval(expr.value, env)
            val current = if (env.has(expr.name)) env.get(expr.name, expr.line, expr.col) else null
            val result = applyOp(expr.op, current, value, expr.line, expr.col)
            env.set(expr.name, result, expr.line, expr.col)
            result
        }
        is Expr.Binary -> evalBinary(expr, env)
        is Expr.Unary -> when (expr.op) {
            TokenType.MINUS -> -(eval(expr.right, env) as? Double ?: throw LoomError.Runtime(expr.line, expr.col, "Expected number"))
            TokenType.BANG, TokenType.NOT -> !isTruthy(eval(expr.right, env))
            else -> null
        }
        is Expr.Call -> {
            val callee = eval(expr.callee, env)
            val args = expr.args.map { eval(it, env) }
            @Suppress("UNCHECKED_CAST")
            when (callee) {
                is Function1<*, *> -> (callee as LoomCallable)(args)
                else -> throw LoomError.Runtime(expr.line, expr.col, "${loomStr(callee)} is not callable")
            }
        }
        is Expr.Get -> {
            val obj = eval(expr.obj, env)
            getProperty(obj, expr.name, expr.line, expr.col)
        }
        is Expr.Set -> {
            val obj = eval(expr.obj, env)
            val value = eval(expr.value, env)
            setProperty(obj, expr.name, value, expr.line, expr.col)
            value
        }
        is Expr.Index -> {
            val obj = eval(expr.obj, env)
            val idx = eval(expr.index, env)
            when (obj) {
                is List<*> -> obj[(idx as Double).toInt()]
                is Map<*, *> -> obj[idx]
                is String -> obj[(idx as Double).toInt()].toString()
                else -> throw LoomError.Runtime(expr.line, expr.col, "Cannot index ${typeName(obj)}")
            }
        }
        is Expr.IndexSet -> {
            val obj = eval(expr.obj, env)
            val idx = eval(expr.index, env)
            val value = eval(expr.value, env)
            when (obj) {
                is MutableList<*> -> (obj as MutableList<Any?>)[(idx as Double).toInt()] = value
                is MutableMap<*, *> -> (obj as MutableMap<Any?, Any?>)[idx] = value
                else -> throw LoomError.Runtime(expr.line, expr.col, "Cannot index-assign ${typeName(obj)}")
            }
            value
        }
        is Expr.ListLiteral -> expr.elements.map { eval(it, env) }.toMutableList()
        is Expr.MapLiteral -> expr.entries.associate { (k, v) -> eval(k, env) to eval(v, env) }.toMutableMap()
        is Expr.Lambda -> {
            val fn: LoomCallable = { args ->
                val fnEnv = env.child()
                expr.params.forEachIndexed { i, p -> fnEnv.define(p, args.getOrNull(i)) }
                try { expr.body.forEach { exec(it, fnEnv) }; null }
                catch (r: LoomError.Return) { r.value }
            }
            fn
        }
    }

    private fun evalBinary(expr: Expr.Binary, env: Environment): Any? {
        if (expr.op == TokenType.AND) return if (!isTruthy(eval(expr.left, env))) false else isTruthy(eval(expr.right, env))
        if (expr.op == TokenType.OR) return if (isTruthy(eval(expr.left, env))) true else isTruthy(eval(expr.right, env))
        val l = eval(expr.left, env)
        val r = eval(expr.right, env)
        return when (expr.op) {
            TokenType.PLUS -> when {
                l is Double && r is Double -> l + r
                l is String || r is String -> loomStr(l) + loomStr(r)
                else -> throw LoomError.Runtime(expr.line, expr.col, "Cannot add ${typeName(l)} and ${typeName(r)}")
            }
            TokenType.MINUS -> numOp(l, r, expr) { a, b -> a - b }
            TokenType.STAR -> numOp(l, r, expr) { a, b -> a * b }
            TokenType.SLASH -> numOp(l, r, expr) { a, b ->
                if (b == 0.0) throw LoomError.Runtime(expr.line, expr.col, "Division by zero")
                a / b
            }
            TokenType.PERCENT -> numOp(l, r, expr) { a, b -> a % b }
            TokenType.EQ -> loomEquals(l, r)
            TokenType.NEQ -> !loomEquals(l, r)
            TokenType.LT -> numOp(l, r, expr) { a, b -> a < b }
            TokenType.LTE -> numOp(l, r, expr) { a, b -> a <= b }
            TokenType.GT -> numOp(l, r, expr) { a, b -> a > b }
            TokenType.GTE -> numOp(l, r, expr) { a, b -> a >= b }
            else -> null
        }
    }

    private fun applyOp(op: TokenType, current: Any?, value: Any?, line: Int, col: Int): Any? = when (op) {
        TokenType.ASSIGN -> value
        TokenType.PLUS_ASSIGN -> when {
            current is Double && value is Double -> current + value
            else -> loomStr(current) + loomStr(value)
        }
        TokenType.MINUS_ASSIGN -> (current as Double) - (value as Double)
        TokenType.STAR_ASSIGN -> (current as Double) * (value as Double)
        TokenType.SLASH_ASSIGN -> (current as Double) / (value as Double)
        else -> value
    }

    private fun getProperty(obj: Any?, name: String, line: Int, col: Int): Any? {
        if (obj is Map<*, *>) return obj[name]
        // Delegate to LoomObject interface if implemented
        if (obj is LoomObject) return obj.getProperty(name) ?: throw LoomError.Runtime(line, col, "No property '$name' on ${typeName(obj)}")
        throw LoomError.Runtime(line, col, "Cannot get property '$name' on ${typeName(obj)}")
    }

    @Suppress("UNCHECKED_CAST")
    private fun setProperty(obj: Any?, name: String, value: Any?, line: Int, col: Int) {
        if (obj is MutableMap<*, *>) { (obj as MutableMap<Any?, Any?>)[name] = value; return }
        if (obj is LoomObject) { obj.setProperty(name, value); return }
        throw LoomError.Runtime(line, col, "Cannot set property '$name' on ${typeName(obj)}")
    }

    private fun isTruthy(v: Any?) = when (v) {
        null -> false
        is Boolean -> v
        is Double -> v != 0.0
        is String -> v.isNotEmpty()
        else -> true
    }

    private fun loomEquals(a: Any?, b: Any?) = when {
        a == null && b == null -> true
        a == null || b == null -> false
        else -> a == b
    }

    private fun numOp(l: Any?, r: Any?, expr: Expr.Binary, op: (Double, Double) -> Any?): Any? {
        val a = l as? Double ?: throw LoomError.Runtime(expr.line, expr.col, "Expected number, got ${typeName(l)}")
        val b = r as? Double ?: throw LoomError.Runtime(expr.line, expr.col, "Expected number, got ${typeName(r)}")
        return op(a, b)
    }

    private fun toTicks(v: Any?, unit: ScheduleUnit): Long {
        val n = (v as? Double)?.toLong() ?: 1L
        return when (unit) {
            ScheduleUnit.TICKS -> n
            ScheduleUnit.SECONDS -> n * 20L
            ScheduleUnit.MINUTES -> n * 1200L
        }
    }

    fun loomStr(v: Any?): String = when (v) {
        null -> "null"
        is Boolean -> v.toString()
        is Double -> if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
        is LoomObject -> v.toLoomString()
        else -> v.toString()
    }

    private fun typeName(v: Any?) = when (v) {
        null -> "null"
        is Boolean -> "bool"
        is Double -> "number"
        is String -> "string"
        is List<*> -> "list"
        is Map<*, *> -> "map"
        is Function1<*, *> -> "function"
        is LoomObject -> v.typeName()
        else -> v.javaClass.simpleName
    }
}

interface LoomObject {
    fun getProperty(name: String): Any?
    fun setProperty(name: String, value: Any?) {}
    fun typeName(): String
    fun toLoomString(): String = "<${typeName()}>"
}
