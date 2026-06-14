package io.github.earth1283.loom.lang

sealed class Expr(open val line: Int, open val col: Int) {
    data class Literal(val value: Any?, override val line: Int, override val col: Int) : Expr(line, col)
    data class StringTemplate(val parts: List<Expr>, override val line: Int, override val col: Int) : Expr(line, col)
    data class Variable(val name: String, override val line: Int, override val col: Int) : Expr(line, col)
    data class Assign(val name: String, val op: TokenType, val value: Expr, override val line: Int, override val col: Int) : Expr(line, col)
    data class Binary(val left: Expr, val op: TokenType, val right: Expr, override val line: Int, override val col: Int) : Expr(line, col)
    data class Unary(val op: TokenType, val right: Expr, override val line: Int, override val col: Int) : Expr(line, col)
    data class Call(val callee: Expr, val args: List<Expr>, override val line: Int, override val col: Int) : Expr(line, col)
    data class Get(val obj: Expr, val name: String, override val line: Int, override val col: Int) : Expr(line, col)
    data class Set(val obj: Expr, val name: String, val value: Expr, override val line: Int, override val col: Int) : Expr(line, col)
    data class Index(val obj: Expr, val index: Expr, override val line: Int, override val col: Int) : Expr(line, col)
    data class IndexSet(val obj: Expr, val index: Expr, val value: Expr, override val line: Int, override val col: Int) : Expr(line, col)
    data class ListLiteral(val elements: List<Expr>, override val line: Int, override val col: Int) : Expr(line, col)
    data class MapLiteral(val entries: List<Pair<Expr, Expr>>, override val line: Int, override val col: Int) : Expr(line, col)
    data class Lambda(val params: List<String>, val body: List<Stmt>, override val line: Int, override val col: Int) : Expr(line, col)
}

sealed class Stmt(open val line: Int, open val col: Int) {
    data class ScriptDecl(val name: String, val body: List<Stmt>, override val line: Int, override val col: Int) : Stmt(line, col)
    data class OnEvent(val event: String, val params: List<String>, val body: List<Stmt>, override val line: Int, override val col: Int) : Stmt(line, col)
    data class CommandDecl(val name: String, val params: List<String>, val body: List<Stmt>, override val line: Int, override val col: Int) : Stmt(line, col)
    data class EverySchedule(val amount: Expr, val unit: ScheduleUnit, val body: List<Stmt>, override val line: Int, override val col: Int) : Stmt(line, col)
    data class AfterSchedule(val amount: Expr, val unit: ScheduleUnit, val body: List<Stmt>, override val line: Int, override val col: Int) : Stmt(line, col)
    data class VarDecl(val name: String, val initializer: Expr?, override val line: Int, override val col: Int) : Stmt(line, col)
    data class FunDecl(val name: String, val params: List<String>, val body: List<Stmt>, override val line: Int, override val col: Int) : Stmt(line, col)
    data class Return(val value: Expr?, override val line: Int, override val col: Int) : Stmt(line, col)
    data class If(val condition: Expr, val thenBranch: List<Stmt>, val elseBranch: List<Stmt>?, override val line: Int, override val col: Int) : Stmt(line, col)
    data class While(val condition: Expr, val body: List<Stmt>, override val line: Int, override val col: Int) : Stmt(line, col)
    data class For(val variable: String, val iterable: Expr, val body: List<Stmt>, override val line: Int, override val col: Int) : Stmt(line, col)
    data class Break(override val line: Int, override val col: Int) : Stmt(line, col)
    data class Continue(override val line: Int, override val col: Int) : Stmt(line, col)
    data class Expression(val expr: Expr, override val line: Int, override val col: Int) : Stmt(line, col)
    data class Import(val path: String, override val line: Int, override val col: Int) : Stmt(line, col)
}

enum class ScheduleUnit { TICKS, SECONDS, MINUTES }
