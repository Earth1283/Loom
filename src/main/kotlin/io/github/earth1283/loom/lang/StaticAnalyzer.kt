package io.github.earth1283.loom.lang

class StaticAnalyzer {
    private val diagnostics = mutableListOf<Diagnostic>()

    companion object {
        private val KNOWN_EVENTS = setOf(
            "PlayerJoin", "PlayerQuit", "PlayerChat", "PlayerMove", "PlayerDeath", "PlayerRespawn",
            "PlayerInteract", "PlayerLevelChange",
            "BlockBreak", "BlockPlace",
            "EntityDamage", "EntityDamageByEntity", "EntitySpawn",
            "WeatherChange", "ServerLoad"
        )
        private val VALID_CMD_NAME = Regex("[a-zA-Z][a-zA-Z0-9_-]*")
        private const val MAX_DEPTH = 5
    }

    fun analyze(stmts: List<Stmt>): List<Diagnostic> {
        diagnostics.clear()
        if (stmts.isNotEmpty() && stmts.none { it is Stmt.ScriptDecl })
            info(stmts.first().line, stmts.first().col,
                "No 'script \"name\" { }' block found — top-level code runs immediately on load")
        analyzeBlock(stmts, inLoop = false, inCallable = false, depth = 0, outerVars = emptySet())
        return diagnostics.toList()
    }

    // Block
    private fun analyzeBlock(
        stmts: List<Stmt>,
        inLoop: Boolean,
        inCallable: Boolean,
        depth: Int,
        outerVars: Set<String>
    ) {
        if (depth >= MAX_DEPTH && stmts.isNotEmpty())
            warn(stmts.first().line, stmts.first().col,
                "Block is nested $depth levels deep — consider extracting to a 'fun' for readability")

        // Collect var declarations at this block level (not in nested blocks)
        val localDecls = mutableMapOf<String, Pair<Int, Int>>() // name → (line, col)
        for (stmt in stmts) {
            if (stmt is Stmt.VarDecl) {
                when {
                    stmt.name in outerVars ->
                        warn(stmt.line, stmt.col, "'${stmt.name}' shadows a variable from an outer scope")
                    stmt.name in localDecls ->
                        warn(stmt.line, stmt.col, "'${stmt.name}' is declared more than once in this block")
                    else ->
                        localDecls[stmt.name] = stmt.line to stmt.col
                }
            }
        }

        val innerVars = outerVars + localDecls.keys
        val seenEvents = mutableSetOf<String>()
        val seenCommands = mutableSetOf<String>()
        var terminator: Stmt? = null
        var firstUnreachable: Stmt? = null
        var lastUnreachable: Stmt? = null

        for (stmt in stmts) {
            if (terminator != null) {
                if (firstUnreachable == null) firstUnreachable = stmt
                lastUnreachable = stmt
            } else {
                analyzeStmt(stmt, inLoop, inCallable, seenEvents, seenCommands, depth, innerVars)
                if (stmt is Stmt.Return || stmt is Stmt.Break || stmt is Stmt.Continue) terminator = stmt
            }
        }

        val first = firstUnreachable
        val last  = lastUnreachable
        val term  = terminator
        if (first != null && last != null && term != null) {
            diagnostics.add(Diagnostic(
                first.line, first.col, last.line, 999,
                "Unreachable code after '${terminatorLabel(term)}'",
                Diagnostic.Severity.UNREACHABLE
            ))
        }

        // Unused local variable check
        if (localDecls.isNotEmpty()) {
            val readNames = collectReadVarNames(stmts)
            for ((name, pos) in localDecls) {
                if (name !in readNames)
                    info(pos.first, pos.second, "'$name' is declared but never read")
            }
        }
    }

    // Statements
    private fun analyzeStmt(
        stmt: Stmt, inLoop: Boolean, inCallable: Boolean,
        seenEvents: MutableSet<String>, seenCommands: MutableSet<String>,
        depth: Int, outerVars: Set<String>
    ) {
        when (stmt) {
            is Stmt.ScriptDecl ->
                analyzeBlock(stmt.body, inLoop = false, inCallable = false, depth + 1, outerVars)

            is Stmt.OnEvent -> {
                if (!seenEvents.add(stmt.event))
                    warn(stmt.line, stmt.col, "Duplicate 'on ${stmt.event}' handler in this scope")
                if (stmt.event !in KNOWN_EVENTS) {
                    val suggestion = closestKnownEvent(stmt.event)
                    if (suggestion != null)
                        warn(stmt.line, stmt.col, "Unknown event '${stmt.event}' — did you mean '$suggestion'? This handler will never fire")
                    else
                        warn(stmt.line, stmt.col, "Unknown event '${stmt.event}' — this handler will never fire")
                }
                if (stmt.body.isEmpty())
                    info(stmt.line, stmt.col, "Empty '${stmt.event}' handler has no effect")
                analyzeBlock(stmt.body, inLoop = false, inCallable = true, depth + 1, outerVars + stmt.params)
            }

            is Stmt.CommandDecl -> {
                if (!seenCommands.add(stmt.name))
                    warn(stmt.line, stmt.col, "Duplicate command '/${stmt.name}' in this scope")
                when {
                    !VALID_CMD_NAME.matches(stmt.name) ->
                        warn(stmt.line, stmt.col,
                            "Command name '${stmt.name}' must start with a letter and contain only letters, digits, underscores, or hyphens")
                    stmt.name.length > 32 ->
                        warn(stmt.line, stmt.col,
                            "Command name '${stmt.name}' is very long (${stmt.name.length} chars) — Minecraft commands work best under 32 characters")
                }
                if (stmt.body.isEmpty())
                    info(stmt.line, stmt.col, "Empty '/${stmt.name}' command has no effect")
                analyzeBlock(stmt.body, inLoop = false, inCallable = true, depth + 1, outerVars + stmt.params)
            }

            is Stmt.FunDecl -> {
                if (stmt.params.size != stmt.params.distinct().size)
                    warn(stmt.line, stmt.col, "Function '${stmt.name}' has duplicate parameter names")
                analyzeBlock(stmt.body, inLoop = false, inCallable = true, depth + 1, outerVars + stmt.params)
            }

            is Stmt.EverySchedule -> {
                analyzeExpr(stmt.amount, inLoop, inCallable)
                checkScheduleAmount(stmt.amount, stmt.unit, stmt.line, stmt.col, repeating = true)
                if (stmt.body.isEmpty())
                    info(stmt.line, stmt.col, "Empty 'every' block has no effect")
                analyzeBlock(stmt.body, inLoop = false, inCallable = true, depth + 1, outerVars)
            }

            is Stmt.AfterSchedule -> {
                analyzeExpr(stmt.amount, inLoop, inCallable)
                checkScheduleAmount(stmt.amount, stmt.unit, stmt.line, stmt.col, repeating = false)
                analyzeBlock(stmt.body, inLoop = false, inCallable = true, depth + 1, outerVars)
            }

            is Stmt.VarDecl ->
                stmt.initializer?.let { analyzeExpr(it, inLoop, inCallable) }

            is Stmt.If -> {
                analyzeExpr(stmt.condition, inLoop, inCallable)
                when {
                    isAlwaysTrue(stmt.condition) ->
                        warn(stmt.condition.line, stmt.condition.col,
                            "'if' condition is always true — else branch (if any) is unreachable dead code")
                    isAlwaysFalse(stmt.condition) ->
                        warn(stmt.condition.line, stmt.condition.col,
                            "'if' condition is always false — then branch is unreachable dead code")
                }
                analyzeBlock(stmt.thenBranch, inLoop, inCallable, depth + 1, outerVars)
                stmt.elseBranch?.let { analyzeBlock(it, inLoop, inCallable, depth + 1, outerVars) }
            }

            is Stmt.While -> {
                analyzeExpr(stmt.condition, inLoop, inCallable)
                when {
                    isAlwaysFalse(stmt.condition) ->
                        warn(stmt.line, stmt.col,
                            "'while' condition is always false — loop body is unreachable dead code")
                    isAlwaysTrue(stmt.condition) && !containsBreakShallow(stmt.body) ->
                        warn(stmt.line, stmt.col,
                            "'while (true)' with no reachable 'break' — potential infinite loop that will freeze the server")
                }
                analyzeBlock(stmt.body, inLoop = true, inCallable, depth + 1, outerVars)
            }

            is Stmt.For -> {
                analyzeExpr(stmt.iterable, inLoop, inCallable)
                if (stmt.variable in outerVars)
                    warn(stmt.line, stmt.col,
                        "Loop variable '${stmt.variable}' shadows a variable from an outer scope")
                analyzeBlock(stmt.body, inLoop = true, inCallable, depth + 1, outerVars + stmt.variable)
            }

            is Stmt.Break    -> { if (!inLoop) error(stmt.line, stmt.col, "'break' used outside of a loop") }
            is Stmt.Continue -> { if (!inLoop) error(stmt.line, stmt.col, "'continue' used outside of a loop") }

            is Stmt.Return -> {
                if (!inCallable)
                    warn(stmt.line, stmt.col, "'return' used outside of a function, handler, or command")
                stmt.value?.let { analyzeExpr(it, inLoop, inCallable) }
            }

            is Stmt.Expression -> analyzeExpr(stmt.expr, inLoop, inCallable)
            is Stmt.Import -> {}
        }
    }

    // Schedule checks
    private fun checkScheduleAmount(amount: Expr, unit: ScheduleUnit, line: Int, col: Int, repeating: Boolean) {
        if (amount !is Expr.Literal) return
        val n = amount.value as? Double ?: return
        val keyword = if (repeating) "every" else "after"
        when {
            n < 0 ->
                warn(line, col, "Negative schedule amount (${n.toLong()}) — schedule will not fire")
            n == 0.0 ->
                warn(line, col, "'$keyword 0 ${unit.name.lowercase()}' — zero-interval schedule may not behave as expected")
            unit == ScheduleUnit.TICKS && n < 2 && repeating ->
                warn(line, col, "'every ${n.toLong()} tick' fires every ${n.toLong() * 50}ms — this frequency may severely impact server performance")
            unit == ScheduleUnit.TICKS && n < 4 && repeating ->
                info(line, col, "'every ${n.toLong()} ticks' fires ~${(20 / n).toInt()}x per second — consider whether this frequency is necessary")
        }
    }

    // Expressions
    private fun analyzeExpr(expr: Expr, inLoop: Boolean, inCallable: Boolean) {
        when (expr) {
            is Expr.Binary -> {
                analyzeExpr(expr.left, inLoop, inCallable)
                analyzeExpr(expr.right, inLoop, inCallable)
                when (expr.op) {
                    TokenType.SLASH, TokenType.PERCENT ->
                        if (isZeroLiteral(expr.right))
                            error(expr.line, expr.col, "Division by zero")

                    TokenType.EQ -> when {
                        sameExpr(expr.left, expr.right) ->
                            warn(expr.line, expr.col,
                                "Comparison is always true — both operands are identical")
                        isBoolLiteral(expr.right) || isBoolLiteral(expr.left) ->
                            info(expr.line, expr.col,
                                "Redundant comparison to boolean literal — use the expression directly")
                        isNullLiteral(expr.left) && isNullLiteral(expr.right) ->
                            warn(expr.line, expr.col, "'null == null' is always true")
                    }

                    TokenType.NEQ ->
                        if (sameExpr(expr.left, expr.right))
                            warn(expr.line, expr.col,
                                "Comparison is always false — both operands are identical")

                    TokenType.PLUS ->
                        if (isNullLiteral(expr.left) || isNullLiteral(expr.right))
                            info(expr.line, expr.col,
                                "Concatenating with 'null' — the string \"null\" will be used")

                    else -> {}
                }
            }

            is Expr.Unary -> {
                analyzeExpr(expr.right, inLoop, inCallable)
                val isNot = expr.op == TokenType.NOT || expr.op == TokenType.BANG
                if (isNot && expr.right is Expr.Unary) {
                    val innerIsNot = expr.right.op == TokenType.NOT || expr.right.op == TokenType.BANG
                    if (innerIsNot)
                        info(expr.line, expr.col, "Double negation 'not not x' — simplify to just 'x'")
                }
                if (isNot && expr.right is Expr.Binary && expr.right.op == TokenType.EQ)
                    info(expr.line, expr.col, "'not (a == b)' can be written as 'a != b'")
                if (isNot && expr.right is Expr.Binary && expr.right.op == TokenType.NEQ)
                    info(expr.line, expr.col, "'not (a != b)' can be written as 'a == b'")
            }

            is Expr.Assign -> {
                analyzeExpr(expr.value, inLoop, inCallable)
                val v = expr.value
                when {
                    expr.op == TokenType.ASSIGN && v is Expr.Variable && v.name == expr.name ->
                        warn(expr.line, expr.col, "Self-assignment '${expr.name} = ${expr.name}' has no effect")
                    expr.op == TokenType.PLUS_ASSIGN && isZeroLiteral(expr.value) ->
                        info(expr.line, expr.col, "'${expr.name} += 0' has no effect")
                    expr.op == TokenType.MINUS_ASSIGN && isZeroLiteral(expr.value) ->
                        info(expr.line, expr.col, "'${expr.name} -= 0' has no effect")
                    expr.op == TokenType.STAR_ASSIGN && isOneLiteral(expr.value) ->
                        info(expr.line, expr.col, "'${expr.name} *= 1' has no effect")
                }
            }

            is Expr.Call -> {
                analyzeExpr(expr.callee, inLoop, inCallable)
                expr.args.forEach { analyzeExpr(it, inLoop, inCallable) }
            }
            is Expr.Get           -> analyzeExpr(expr.obj, inLoop, inCallable)
            is Expr.Set           -> { analyzeExpr(expr.obj, inLoop, inCallable); analyzeExpr(expr.value, inLoop, inCallable) }
            is Expr.Index         -> { analyzeExpr(expr.obj, inLoop, inCallable); analyzeExpr(expr.index, inLoop, inCallable) }
            is Expr.IndexSet      -> { analyzeExpr(expr.obj, inLoop, inCallable); analyzeExpr(expr.index, inLoop, inCallable); analyzeExpr(expr.value, inLoop, inCallable) }
            is Expr.ListLiteral   -> expr.elements.forEach { analyzeExpr(it, inLoop, inCallable) }
            is Expr.MapLiteral    -> expr.entries.forEach { (k, v) -> analyzeExpr(k, inLoop, inCallable); analyzeExpr(v, inLoop, inCallable) }
            is Expr.StringTemplate-> expr.parts.forEach { analyzeExpr(it, inLoop, inCallable) }
            is Expr.Lambda        -> analyzeBlock(expr.body, inLoop = false, inCallable = true, depth = 0, outerVars = emptySet())
            is Expr.Literal, is Expr.Variable -> {}
        }
    }

    // Unused variable tracking
    // Collects all variable names READ in any expression in the subtree.
    // Compound assignments (x += ...) count as reads; plain x = ... does not.
    private fun collectReadVarNames(stmts: List<Stmt>): Set<String> {
        val names = mutableSetOf<String>()
        stmts.forEach { collectReadVarNamesStmt(it, names) }
        return names
    }

    private fun collectReadVarNamesStmt(stmt: Stmt, names: MutableSet<String>) {
        when (stmt) {
            is Stmt.ScriptDecl    -> stmt.body.forEach { collectReadVarNamesStmt(it, names) }
            is Stmt.OnEvent       -> stmt.body.forEach { collectReadVarNamesStmt(it, names) }
            is Stmt.CommandDecl   -> stmt.body.forEach { collectReadVarNamesStmt(it, names) }
            is Stmt.FunDecl       -> stmt.body.forEach { collectReadVarNamesStmt(it, names) }
            is Stmt.EverySchedule -> { collectReadVarNamesExpr(stmt.amount, names); stmt.body.forEach { collectReadVarNamesStmt(it, names) } }
            is Stmt.AfterSchedule -> { collectReadVarNamesExpr(stmt.amount, names); stmt.body.forEach { collectReadVarNamesStmt(it, names) } }
            is Stmt.VarDecl       -> stmt.initializer?.let { collectReadVarNamesExpr(it, names) }
            is Stmt.If            -> { collectReadVarNamesExpr(stmt.condition, names); stmt.thenBranch.forEach { collectReadVarNamesStmt(it, names) }; stmt.elseBranch?.forEach { collectReadVarNamesStmt(it, names) } }
            is Stmt.While         -> { collectReadVarNamesExpr(stmt.condition, names); stmt.body.forEach { collectReadVarNamesStmt(it, names) } }
            is Stmt.For           -> { collectReadVarNamesExpr(stmt.iterable, names); stmt.body.forEach { collectReadVarNamesStmt(it, names) } }
            is Stmt.Return        -> stmt.value?.let { collectReadVarNamesExpr(it, names) }
            is Stmt.Expression    -> collectReadVarNamesExpr(stmt.expr, names)
            is Stmt.Break, is Stmt.Continue, is Stmt.Import -> {}
        }
    }

    private fun collectReadVarNamesExpr(expr: Expr, names: MutableSet<String>) {
        when (expr) {
            is Expr.Variable    -> names.add(expr.name)
            is Expr.Assign      -> { if (expr.op != TokenType.ASSIGN) names.add(expr.name); collectReadVarNamesExpr(expr.value, names) }
            is Expr.Binary      -> { collectReadVarNamesExpr(expr.left, names); collectReadVarNamesExpr(expr.right, names) }
            is Expr.Unary       -> collectReadVarNamesExpr(expr.right, names)
            is Expr.Call        -> { collectReadVarNamesExpr(expr.callee, names); expr.args.forEach { collectReadVarNamesExpr(it, names) } }
            is Expr.Get         -> collectReadVarNamesExpr(expr.obj, names)
            is Expr.Set         -> { collectReadVarNamesExpr(expr.obj, names); collectReadVarNamesExpr(expr.value, names) }
            is Expr.Index       -> { collectReadVarNamesExpr(expr.obj, names); collectReadVarNamesExpr(expr.index, names) }
            is Expr.IndexSet    -> { collectReadVarNamesExpr(expr.obj, names); collectReadVarNamesExpr(expr.index, names); collectReadVarNamesExpr(expr.value, names) }
            is Expr.ListLiteral -> expr.elements.forEach { collectReadVarNamesExpr(it, names) }
            is Expr.MapLiteral  -> expr.entries.forEach { (k, v) -> collectReadVarNamesExpr(k, names); collectReadVarNamesExpr(v, names) }
            is Expr.StringTemplate -> expr.parts.forEach { collectReadVarNamesExpr(it, names) }
            is Expr.Lambda      -> expr.body.forEach { collectReadVarNamesStmt(it, names) }
            is Expr.Literal     -> {}
        }
    }

    // Event name suggestion
    private fun closestKnownEvent(name: String): String? {
        val lower = name.lowercase()
        return KNOWN_EVENTS.firstOrNull { it.lowercase() == lower }
            ?: KNOWN_EVENTS.filter { levenshtein(it.lowercase(), lower) <= 2 }
                .minByOrNull { levenshtein(it.lowercase(), lower) }
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length)
            dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1]
                       else 1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
        return dp[a.length][b.length]
    }

    // Constant-value helpers
    private fun isAlwaysTrue(expr: Expr): Boolean = when (expr) {
        is Expr.Literal -> expr.value == true || (expr.value is Double && expr.value != 0.0)
        is Expr.Unary   -> (expr.op == TokenType.NOT || expr.op == TokenType.BANG) && isAlwaysFalse(expr.right)
        is Expr.Binary  -> when (expr.op) {
            TokenType.AND -> isAlwaysTrue(expr.left) && isAlwaysTrue(expr.right)
            TokenType.OR  -> isAlwaysTrue(expr.left) || isAlwaysTrue(expr.right)
            TokenType.EQ  -> sameExpr(expr.left, expr.right)
            else          -> false
        }
        else -> false
    }

    private fun isAlwaysFalse(expr: Expr): Boolean = when (expr) {
        is Expr.Literal -> expr.value == false || expr.value == null || expr.value == 0.0
        is Expr.Unary   -> (expr.op == TokenType.NOT || expr.op == TokenType.BANG) && isAlwaysTrue(expr.right)
        is Expr.Binary  -> when (expr.op) {
            TokenType.AND -> isAlwaysFalse(expr.left) || isAlwaysFalse(expr.right)
            TokenType.OR  -> isAlwaysFalse(expr.left) && isAlwaysFalse(expr.right)
            TokenType.NEQ -> sameExpr(expr.left, expr.right)
            else          -> false
        }
        else -> false
    }

    private fun isZeroLiteral(expr: Expr) = expr is Expr.Literal && expr.value == 0.0
    private fun isOneLiteral(expr: Expr)  = expr is Expr.Literal && expr.value == 1.0
    private fun isBoolLiteral(expr: Expr) = expr is Expr.Literal && expr.value is Boolean
    private fun isNullLiteral(expr: Expr) = expr is Expr.Literal && expr.value == null

    private fun sameExpr(a: Expr, b: Expr) = when {
        a is Expr.Variable && b is Expr.Variable -> a.name == b.name
        a is Expr.Literal  && b is Expr.Literal  -> a.value == b.value
        else -> false
    }

    // Only checks for break at this loop level — does not descend into nested while/for.
    private fun containsBreakShallow(stmts: List<Stmt>): Boolean {
        for (stmt in stmts) {
            if (stmt is Stmt.Break) return true
            when (stmt) {
                is Stmt.If -> {
                    if (containsBreakShallow(stmt.thenBranch)) return true
                    if (stmt.elseBranch != null && containsBreakShallow(stmt.elseBranch)) return true
                }
                is Stmt.While, is Stmt.For -> {}
                else -> {}
            }
        }
        return false
    }

    // Diagnostic helpers
    private fun terminatorLabel(stmt: Stmt) = when (stmt) {
        is Stmt.Return   -> "return"
        is Stmt.Break    -> "break"
        is Stmt.Continue -> "continue"
        else             -> "statement"
    }

    private fun error(line: Int, col: Int, msg: String) =
        diagnostics.add(Diagnostic(line, col, line, col + 1, msg, Diagnostic.Severity.ERROR))

    private fun warn(line: Int, col: Int, msg: String) =
        diagnostics.add(Diagnostic(line, col, line, col + 1, msg, Diagnostic.Severity.WARNING))

    private fun info(line: Int, col: Int, msg: String) =
        diagnostics.add(Diagnostic(line, col, line, col + 1, msg, Diagnostic.Severity.INFO))
}
