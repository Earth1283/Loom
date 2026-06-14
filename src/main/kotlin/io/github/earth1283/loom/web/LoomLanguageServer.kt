package io.github.earth1283.loom.web

import io.github.earth1283.loom.api.ScriptManager
import io.github.earth1283.loom.lang.Diagnostic
import io.github.earth1283.loom.lang.Lexer
import io.github.earth1283.loom.lang.LoomError
import io.github.earth1283.loom.lang.Parser
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

class LoomLanguageServer(
    private val scripts: ScriptManager,
    private val session: DefaultWebSocketServerSession
) {
    @Serializable
    data class WsMessage(val type: String, val payload: JsonElement = JsonNull)

    suspend fun handle() {
        for (frame in session.incoming) {
            if (frame !is Frame.Text) continue
            val raw = frame.readText()
            try {
                val msg = Json.decodeFromString<WsMessage>(raw)
                when (msg.type) {
                    "validate" -> handleValidate(msg.payload)
                    "complete" -> handleComplete(msg.payload)
                    "hover" -> handleHover(msg.payload)
                    "ping" -> session.send(Frame.Text(Json.encodeToString(WsMessage("pong"))))
                }
            } catch (_: Exception) {}
        }
    }

    private suspend fun handleValidate(payload: JsonElement) {
        val obj = payload.jsonObject
        val name = obj["name"]?.jsonPrimitive?.content ?: return
        val source = obj["source"]?.jsonPrimitive?.content ?: return
        val diags = scripts.validate(name, source)
        val response = buildJsonObject {
            put("type", "diagnostics")
            put("diagnostics", buildJsonArray {
                diags.forEach { d ->
                    add(buildJsonObject {
                        put("line", d.line); put("col", d.col)
                        put("endLine", d.endLine); put("endCol", d.endCol)
                        put("message", d.message); put("severity", d.severity.name)
                    })
                }
            })
        }
        session.send(Frame.Text(response.toString()))
    }

    private suspend fun handleComplete(payload: JsonElement) {
        val obj = payload.jsonObject
        val source = obj["source"]?.jsonPrimitive?.content ?: ""
        val line = obj["line"]?.jsonPrimitive?.int ?: 0
        val col = obj["col"]?.jsonPrimitive?.int ?: 0
        val completions = computeCompletions(source, line, col)
        val response = buildJsonObject {
            put("type", "completions")
            put("items", buildJsonArray {
                completions.forEach { item ->
                    add(buildJsonObject {
                        item.forEach { (k, v) -> put(k, v) }
                    })
                }
            })
        }
        session.send(Frame.Text(response.toString()))
    }

    private suspend fun handleHover(payload: JsonElement) {
        val obj = payload.jsonObject
        val word = obj["word"]?.jsonPrimitive?.content ?: return
        val doc = BUILTIN_DOCS[word]
        val response = buildJsonObject {
            put("type", "hover")
            put("word", word)
            put("doc", doc ?: "")
        }
        session.send(Frame.Text(response.toString()))
    }

    private fun computeCompletions(source: String, line: Int, col: Int): List<Map<String, String>> {
        // Extract the word being typed
        val lines = source.lines()
        val currentLine = lines.getOrNull(line - 1) ?: ""
        val prefix = currentLine.take(col).trimEnd().split(Regex("[^\\w.]")).last()

        val completions = mutableListOf<Map<String, String>>()

        // Keywords
        KEYWORDS.filter { it.startsWith(prefix) }.forEach { kw ->
            completions.add(mapOf("label" to kw, "kind" to "keyword", "detail" to "keyword"))
        }

        // Built-in functions
        BUILTINS.filter { it.startsWith(prefix) }.forEach { fn ->
            completions.add(mapOf("label" to fn, "kind" to "function", "detail" to "built-in", "doc" to (BUILTIN_DOCS[fn] ?: "")))
        }

        // Variable completion from parsed AST
        try {
            val tokens = Lexer(source).tokenize()
            val ast = Parser(tokens).parse()
            collectIdentifiers(ast).filter { it.startsWith(prefix) && it !in BUILTINS }.forEach { id ->
                completions.add(mapOf("label" to id, "kind" to "variable", "detail" to "variable"))
            }
        } catch (_: LoomError) {}

        // Player property completions if prefix ends with "player."
        if (prefix.endsWith("player.") || currentLine.takeLast(7) == "player.") {
            PLAYER_PROPS.forEach { prop ->
                completions.add(mapOf("label" to prop, "kind" to "property", "detail" to "Player property"))
            }
        }

        return completions.distinctBy { it["label"] }.take(50)
    }

    private fun collectIdentifiers(stmts: List<io.github.earth1283.loom.lang.Stmt>): Set<String> {
        val ids = mutableSetOf<String>()
        fun walkExpr(e: io.github.earth1283.loom.lang.Expr) {
            when (e) {
                is io.github.earth1283.loom.lang.Expr.Variable -> ids.add(e.name)
                is io.github.earth1283.loom.lang.Expr.Call -> { walkExpr(e.callee); e.args.forEach { walkExpr(it) } }
                is io.github.earth1283.loom.lang.Expr.Get -> { walkExpr(e.obj) }
                is io.github.earth1283.loom.lang.Expr.Binary -> { walkExpr(e.left); walkExpr(e.right) }
                else -> {}
            }
        }
        fun walkStmt(s: io.github.earth1283.loom.lang.Stmt) {
            when (s) {
                is io.github.earth1283.loom.lang.Stmt.VarDecl -> { ids.add(s.name); s.initializer?.let { walkExpr(it) } }
                is io.github.earth1283.loom.lang.Stmt.FunDecl -> { ids.add(s.name); ids.addAll(s.params); s.body.forEach { walkStmt(it) } }
                is io.github.earth1283.loom.lang.Stmt.ScriptDecl -> s.body.forEach { walkStmt(it) }
                is io.github.earth1283.loom.lang.Stmt.OnEvent -> { ids.addAll(s.params); s.body.forEach { walkStmt(it) } }
                is io.github.earth1283.loom.lang.Stmt.CommandDecl -> { ids.addAll(s.params); s.body.forEach { walkStmt(it) } }
                is io.github.earth1283.loom.lang.Stmt.If -> { s.thenBranch.forEach { walkStmt(it) }; s.elseBranch?.forEach { walkStmt(it) } }
                is io.github.earth1283.loom.lang.Stmt.While -> s.body.forEach { walkStmt(it) }
                is io.github.earth1283.loom.lang.Stmt.For -> { ids.add(s.variable); s.body.forEach { walkStmt(it) } }
                is io.github.earth1283.loom.lang.Stmt.Expression -> walkExpr(s.expr)
                else -> {}
            }
        }
        stmts.forEach { walkStmt(it) }
        return ids
    }

    companion object {
        val KEYWORDS = listOf(
            "script", "on", "command", "every", "after", "ticks", "seconds", "minutes",
            "var", "fun", "return", "if", "else", "while", "for", "in", "break", "continue",
            "and", "or", "not", "true", "false", "null", "import"
        )

        val BUILTINS = listOf(
            "broadcast", "log", "print",
            "players", "player", "playerCount",
            "setBlock", "getBlock", "fill", "summon",
            "serverVersion", "maxPlayers", "worlds",
            "floor", "ceil", "round", "abs", "min", "max", "random", "sqrt", "pow",
            "str", "num", "len", "range", "keys", "values", "contains", "join", "split",
            "upper", "lower", "trim", "replace", "startsWith", "endsWith", "substr",
            "push", "pop", "remove", "sort", "Item"
        )

        val PLAYER_PROPS = listOf(
            "name", "uuid", "health", "maxHealth", "foodLevel", "level", "exp",
            "gameMode", "world", "x", "y", "z", "yaw", "pitch",
            "isOp", "isFlying", "isSneaking", "isSprinting", "ping", "ip",
            "message", "kick", "teleport", "give", "heal", "feed",
            "setGameMode", "setFlying", "effect", "title", "actionBar", "playSound"
        )

        val BUILTIN_DOCS = mapOf(
            "broadcast" to "broadcast(msg) — Send a message to all online players",
            "log" to "log(msg) — Print to server console",
            "players" to "players() → List<Player> — All online players",
            "player" to "player(name) → Player? — Get player by name",
            "setBlock" to "setBlock(world, x, y, z, material) — Place a block",
            "getBlock" to "getBlock(world, x, y, z) → String — Get block material name",
            "fill" to "fill(world, x1, y1, z1, x2, y2, z2, material) — Fill a region",
            "summon" to "summon(world, x, y, z, entityType) — Spawn an entity",
            "floor" to "floor(n) → Number",
            "ceil" to "ceil(n) → Number",
            "round" to "round(n) → Number",
            "abs" to "abs(n) → Number",
            "min" to "min(a, b) → Number",
            "max" to "max(a, b) → Number",
            "random" to "random() → Number — Random between 0.0 and 1.0",
            "sqrt" to "sqrt(n) → Number",
            "pow" to "pow(base, exp) → Number",
            "len" to "len(value) → Number — Length of string/list/map",
            "range" to "range(start, end) → List — Integer range [start, end)",
            "push" to "push(list, item) — Append item to list",
            "pop" to "pop(list) — Remove and return last item",
            "join" to "join(list, sep?) → String — Join list elements",
            "split" to "split(str, sep) → List — Split string by separator",
            "upper" to "upper(str) → String",
            "lower" to "lower(str) → String",
            "trim" to "trim(str) → String",
            "replace" to "replace(str, from, to) → String",
            "contains" to "contains(collection, item) → Bool",
            "keys" to "keys(map) → List",
            "values" to "values(map) → List",
            "Item" to "Item.MATERIAL_NAME — Get material name constant",
            "playerCount" to "playerCount() → Number — Number of online players",
            "worlds" to "worlds() → List<String> — All world names",
            "serverVersion" to "serverVersion() → String",
        )
    }
}
