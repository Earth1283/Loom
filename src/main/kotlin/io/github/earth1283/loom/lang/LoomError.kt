package io.github.earth1283.loom.lang

import kotlinx.serialization.Serializable

sealed class LoomError(message: String) : Exception(message) {
    class Lexer(val line: Int, val col: Int, msg: String) :
        LoomError("[$line:$col] Lexer error: $msg")

    class Parser(val line: Int, val col: Int, msg: String) :
        LoomError("[$line:$col] Parse error: $msg")

    class Runtime(val line: Int, val col: Int, msg: String) :
        LoomError("[$line:$col] Runtime error: $msg")

    class Return(val value: Any?) : LoomError("return")
    class Break : LoomError("break")
    class Continue : LoomError("continue")
}

@Serializable
data class Diagnostic(
    val line: Int,
    val col: Int,
    val endLine: Int,
    val endCol: Int,
    val message: String,
    val severity: Severity
) {
    @Serializable
    enum class Severity { ERROR, WARNING, INFO, UNREACHABLE }
}

fun LoomError.toDiagnostic(): Diagnostic? = when (this) {
    is LoomError.Lexer -> Diagnostic(line, col, line, col + 1, message ?: "Lexer error", Diagnostic.Severity.ERROR)
    is LoomError.Parser -> Diagnostic(line, col, line, col + 1, message ?: "Parse error", Diagnostic.Severity.ERROR)
    is LoomError.Runtime -> Diagnostic(line, col, line, col + 1, message ?: "Runtime error", Diagnostic.Severity.ERROR)
    else -> null
}
