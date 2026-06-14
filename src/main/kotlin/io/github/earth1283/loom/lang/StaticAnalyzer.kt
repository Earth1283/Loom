package io.github.earth1283.loom.lang

class StaticAnalyzer {
    private val diagnostics = mutableListOf<Diagnostic>()

    fun analyze(stmts: List<Stmt>): List<Diagnostic> {
        diagnostics.clear()
        analyzeBlock(stmts, inLoop = false, inCallable = false)
        return diagnostics.toList()
    }

    // ── Block ────────────────────────────────────────────────────────

    private fun analyzeBlock(stmts: List<Stmt>, inLoop: Boolean, inCallable: Boolean) {
        val seenEvents = mutableSetOf<String>()
        val seenCommands = mutableSetOf<String>()
        var terminator: Stmt? = null
        for (stmt in stmts) {
            if (terminator != null) {
                warn(stmt.line, stmt.col, "Unreachable code after '${terminatorLabel(terminator)}'")
                break
            }
            analyzeStmt(stmt, inLoop, inCallable, seenEvents, seenCommands)
            if (stmt is Stmt.Return || stmt is Stmt.Break || stmt is Stmt.Continue) terminator = stmt
        }
    }

    // ── Statements ───────────────────────────────────────────────────

    private fun analyzeStmt(
        stmt: Stmt, inLoop: Boolean, inCallable: Boolean,
        seenEvents: MutableSet<String>, seenCommands: MutableSet<String>
    ) {
        when (stmt) {
            is Stmt.ScriptDecl ->
                analyzeBlock(stmt.body, inLoop = false, inCallable = false)

            is Stmt.OnEvent -> {
                if (!seenEvents.add(stmt.event))
                    warn(stmt.line, stmt.col, "Duplicate 'on ${stmt.event}' handler in this scope")
                if (stmt.body.isEmpty())
                    info(stmt.line, stmt.col, "Empty '${stmt.event}' handler has no effect")
                analyzeBlock(stmt.body, inLoop = false, inCallable = true)
            }

            is Stmt.CommandDecl -> {
                if (!seenCommands.add(stmt.name))
                    warn(stmt.line, stmt.col, "Duplicate command '/${stmt.name}' in this scope")
                if (stmt.body.isEmpty())
                    info(stmt.line, stmt.col, "Empty '/${stmt.name}' command body has no effect")
                analyzeBlock(stmt.body, inLoop = false, inCallable = true)
            }

            is Stmt.FunDecl ->
                analyzeBlock(stmt.body, inLoop = false, inCallable = true)

            is Stmt.EverySchedule -> {
                analyzeExpr(stmt.amount, inLoop, inCallable)
                if (isZeroLiteral(stmt.amount))
                    warn(stmt.line, stmt.col, "'every 0 ${stmt.unit.name.lowercase()}' — zero-interval schedule may not behave as expected")
                if (stmt.body.isEmpty())
                    info(stmt.line, stmt.col, "Empty 'every' block has no effect")
                analyzeBlock(stmt.body, inLoop = false, inCallable = true)
            }

            is Stmt.AfterSchedule -> {
                analyzeExpr(stmt.amount, inLoop, inCallable)
                if (isZeroLiteral(stmt.amount))
                    warn(stmt.line, stmt.col, "'after 0 ${stmt.unit.name.lowercase()}' — zero-delay schedule may not behave as expected")
                analyzeBlock(stmt.body, inLoop = false, inCallable = true)
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
                analyzeBlock(stmt.thenBranch, inLoop, inCallable)
                stmt.elseBranch?.let { analyzeBlock(it, inLoop, inCallable) }
            }

            is Stmt.While -> {
                analyzeExpr(stmt.condition, inLoop, inCallable)
                when {
                    isAlwaysFalse(stmt.condition) ->
                        warn(stmt.line, stmt.col, "'while' condition is always false — loop body is unreachable dead code")
                    isAlwaysTrue(stmt.condition) && !containsBreakShallow(stmt.body) ->
                        warn(stmt.line, stmt.col, "'while (true)' with no reachable 'break' — potential infinite loop that will freeze the server")
                }
                analyzeBlock(stmt.body, inLoop = true, inCallable)
            }

            is Stmt.For -> {
                analyzeExpr(stmt.iterable, inLoop, inCallable)
                analyzeBlock(stmt.body, inLoop = true, inCallable)
            }

            is Stmt.Break -> {
                if (!inLoop) error(stmt.line, stmt.col, "'break' used outside of a loop")
            }

            is Stmt.Continue -> {
                if (!inLoop) error(stmt.line, stmt.col, "'continue' used outside of a loop")
            }

            is Stmt.Return -> {
                if (!inCallable)
                    warn(stmt.line, stmt.col, "'return' used outside of a function, handler, or command")
                stmt.value?.let { analyzeExpr(it, inLoop, inCallable) }
            }

            is Stmt.Expression -> analyzeExpr(stmt.expr, inLoop, inCallable)
            is Stmt.Import -> {}
        }
    }

    // ── Expressions ──────────────────────────────────────────────────

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
                            warn(expr.line, expr.col, "Comparison is always true — both operands are identical")
                        isBoolLiteral(expr.right) || isBoolLiteral(expr.left) ->
                            info(expr.line, expr.col, "Redundant comparison to boolean literal — use the expression directly")
                    }

                    TokenType.NEQ ->
                        if (sameExpr(expr.left, expr.right))
                            warn(expr.line, expr.col, "Comparison is always false — both operands are identical")

                    else -> {}
                }
            }

            is Expr.Assign -> {
                analyzeExpr(expr.value, inLoop, inCallable)
                val v = expr.value
                if (expr.op == TokenType.ASSIGN && v is Expr.Variable && v.name == expr.name)
                    warn(expr.line, expr.col, "Self-assignment '${expr.name} = ${expr.name}' has no effect")
            }

            is Expr.Unary    -> analyzeExpr(expr.right, inLoop, inCallable)
            is Expr.Call     -> { analyzeExpr(expr.callee, inLoop, inCallable); expr.args.forEach { analyzeExpr(it, inLoop, inCallable) } }
            is Expr.Get      -> analyzeExpr(expr.obj, inLoop, inCallable)
            is Expr.Set      -> { analyzeExpr(expr.obj, inLoop, inCallable); analyzeExpr(expr.value, inLoop, inCallable) }
            is Expr.Index    -> { analyzeExpr(expr.obj, inLoop, inCallable); analyzeExpr(expr.index, inLoop, inCallable) }
            is Expr.IndexSet -> { analyzeExpr(expr.obj, inLoop, inCallable); analyzeExpr(expr.index, inLoop, inCallable); analyzeExpr(expr.value, inLoop, inCallable) }
            is Expr.ListLiteral    -> expr.elements.forEach { analyzeExpr(it, inLoop, inCallable) }
            is Expr.MapLiteral     -> expr.entries.forEach { (k, v) -> analyzeExpr(k, inLoop, inCallable); analyzeExpr(v, inLoop, inCallable) }
            is Expr.StringTemplate -> expr.parts.forEach { analyzeExpr(it, inLoop, inCallable) }
            is Expr.Lambda   -> analyzeBlock(expr.body, inLoop = false, inCallable = true)
            is Expr.Literal, is Expr.Variable -> {}
        }
    }

    // ── Constant-value helpers ────────────────────────────────────────

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

    private fun isZeroLiteral(expr: Expr)  = expr is Expr.Literal && expr.value == 0.0
    private fun isBoolLiteral(expr: Expr)  = expr is Expr.Literal && expr.value is Boolean

    private fun sameExpr(a: Expr, b: Expr) = when {
        a is Expr.Variable && b is Expr.Variable -> a.name == b.name
        a is Expr.Literal  && b is Expr.Literal  -> a.value == b.value
        else -> false
    }

    // Checks for break at this loop level only — does not descend into nested while/for.
    private fun containsBreakShallow(stmts: List<Stmt>): Boolean {
        for (stmt in stmts) {
            if (stmt is Stmt.Break) return true
            when (stmt) {
                is Stmt.If -> {
                    if (containsBreakShallow(stmt.thenBranch)) return true
                    if (stmt.elseBranch != null && containsBreakShallow(stmt.elseBranch)) return true
                }
                // A break inside a nested loop belongs to that loop, not this one
                is Stmt.While, is Stmt.For -> {}
                else -> {}
            }
        }
        return false
    }

    // ── Diagnostic helpers ────────────────────────────────────────────

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
