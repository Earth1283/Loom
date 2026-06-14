package io.github.earth1283.loom.web

import io.github.earth1283.loom.api.ScriptManager
import io.github.earth1283.loom.config.LoomConfig
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import io.ktor.http.*
import io.ktor.server.request.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.bukkit.plugin.Plugin
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger
import kotlin.time.Duration.Companion.seconds

class WebServer(
    private val config: LoomConfig,
    private val scripts: ScriptManager,
    private val auth: AuthManager,
    private val plugin: Plugin,
    private val logger: Logger
) {
    private var server: EmbeddedServer<*, *>? = null
    private val activeSessions: MutableSet<DefaultWebSocketSession> =
        ConcurrentHashMap.newKeySet()

    fun start() {
        server = embeddedServer(Netty, port = config.webPort, host = config.webHost) {
            install(ContentNegotiation) {
                json(Json { prettyPrint = false; isLenient = true; ignoreUnknownKeys = true })
            }
            install(WebSockets) {
                pingPeriod = 30.seconds
                timeout = 60.seconds
            }
            install(StatusPages) {
                exception<Throwable> { call, cause ->
                    logger.warning("Web error: ${cause.message}")
                    call.respond(HttpStatusCode.InternalServerError,
                        buildJsonObject { put("error", cause.message ?: "Unknown error") })
                }
            }

            routing {
                // Auth flow
                get("/auth/init") {
                    val (sessionId, code) = auth.createPendingSession()
                    call.respond(mapOf(
                        "sessionId" to sessionId,
                        "message" to "Run /loom confirm $code in Minecraft to authenticate"
                    ))
                }
                get("/auth/status/{sessionId}") {
                    val sessionId = call.parameters["sessionId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                    call.respond(buildJsonObject {
                        put("authenticated", auth.isAuthenticated(sessionId))
                        put("player", auth.getPlayerName(sessionId) ?: "")
                    })
                }
                post("/auth/revoke/{sessionId}") {
                    val sessionId = call.parameters["sessionId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                    if (!requireAuth(call, sessionId)) return@post
                    auth.revokeSession(sessionId)
                    call.respond(mapOf("ok" to true))
                }

                // Script CRUD
                get("/scripts") {
                    val sessionId = call.request.header("X-Session-Id") ?: return@get call.respond(HttpStatusCode.Unauthorized)
                    if (!requireAuth(call, sessionId)) return@get
                    val list = scripts.listScripts().map { name ->
                        val s = scripts.get(name)
                        mapOf("name" to name, "state" to (s?.state?.name ?: "UNLOADED"))
                    }
                    call.respond(list)
                }
                get("/scripts/{name}") {
                    val sessionId = call.request.header("X-Session-Id") ?: return@get call.respond(HttpStatusCode.Unauthorized)
                    if (!requireAuth(call, sessionId)) return@get
                    val name = call.parameters["name"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val file = java.io.File(scripts.repo.let { r ->
                        // read directly from disk
                        java.io.File(plugin.dataFolder, "scripts/$name.loom")
                    }.path)
                    if (!file.exists()) return@get call.respond(HttpStatusCode.NotFound)
                    call.respond(mapOf("name" to name, "source" to file.readText()))
                }
                post("/scripts/{name}") {
                    val sessionId = call.request.header("X-Session-Id") ?: return@post call.respond(HttpStatusCode.Unauthorized)
                    if (!requireAuth(call, sessionId)) return@post
                    val name = call.parameters["name"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val body = call.receive<Map<String, String>>()
                    val source = body["source"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                    scripts.saveScript(name, source)
                    val commitMsg = body["commitMessage"] ?: "Update $name.loom"
                    val player = auth.getPlayerName(sessionId) ?: "Loom"
                    scripts.repo.commit("$name.loom", commitMsg, player)
                    call.respond(mapOf("ok" to true))
                }
                put("/scripts/{name}") {
                    val sessionId = call.request.header("X-Session-Id") ?: return@put call.respond(HttpStatusCode.Unauthorized)
                    if (!requireAuth(call, sessionId)) return@put
                    val name = call.parameters["name"] ?: return@put call.respond(HttpStatusCode.BadRequest)
                    val body = call.receive<Map<String, String>>()
                    val source = body["source"] ?: ""
                    scripts.createScript(name, source)
                    val player = auth.getPlayerName(sessionId) ?: "Loom"
                    scripts.repo.commit("$name.loom", "Create $name.loom", player)
                    call.respond(mapOf("ok" to true))
                }
                delete("/scripts/{name}") {
                    val sessionId = call.request.header("X-Session-Id") ?: return@delete call.respond(HttpStatusCode.Unauthorized)
                    if (!requireAuth(call, sessionId)) return@delete
                    val name = call.parameters["name"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
                    scripts.deleteScript(name)
                    call.respond(mapOf("ok" to true))
                }

                // Script control
                post("/scripts/{name}/load") {
                    val sessionId = call.request.header("X-Session-Id") ?: return@post call.respond(HttpStatusCode.Unauthorized)
                    if (!requireAuth(call, sessionId)) return@post
                    val name = call.parameters["name"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val diags = scripts.load(name)
                    call.respond(buildJsonObject {
                        put("state", scripts.get(name)?.state?.name ?: "ERROR")
                        put("diagnostics", diagsToJson(diags))
                    })
                }
                post("/scripts/{name}/unload") {
                    val sessionId = call.request.header("X-Session-Id") ?: return@post call.respond(HttpStatusCode.Unauthorized)
                    if (!requireAuth(call, sessionId)) return@post
                    val name = call.parameters["name"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                    scripts.unload(name)
                    call.respond(buildJsonObject { put("ok", true) })
                }
                post("/scripts/{name}/reload") {
                    val sessionId = call.request.header("X-Session-Id") ?: return@post call.respond(HttpStatusCode.Unauthorized)
                    if (!requireAuth(call, sessionId)) return@post
                    val name = call.parameters["name"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val diags = scripts.reload(name)
                    call.respond(buildJsonObject {
                        put("state", scripts.get(name)?.state?.name ?: "ERROR")
                        put("diagnostics", diagsToJson(diags))
                    })
                }

                // Validate (no disk write)
                post("/scripts/{name}/validate") {
                    val sessionId = call.request.header("X-Session-Id") ?: return@post call.respond(HttpStatusCode.Unauthorized)
                    if (!requireAuth(call, sessionId)) return@post
                    val name = call.parameters["name"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val body = call.receive<Map<String, String>>()
                    val source = body["source"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val diags = scripts.validate(name, source)
                    call.respond(buildJsonObject { put("diagnostics", diagsToJson(diags)) })
                }

                // Git routes
                get("/git/{name}/log") {
                    val sessionId = call.request.header("X-Session-Id") ?: return@get call.respond(HttpStatusCode.Unauthorized)
                    if (!requireAuth(call, sessionId)) return@get
                    val name = call.parameters["name"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val commits = scripts.repo.log("$name.loom")
                    call.respond<List<io.github.earth1283.loom.git.CommitInfo>>(commits)
                }
                get("/git/log") {
                    val sessionId = call.request.header("X-Session-Id") ?: return@get call.respond(HttpStatusCode.Unauthorized)
                    if (!requireAuth(call, sessionId)) return@get
                    val commits = scripts.repo.log()
                    call.respond<List<io.github.earth1283.loom.git.CommitInfo>>(commits)
                }
                get("/git/{name}/diff") {
                    val sessionId = call.request.header("X-Session-Id") ?: return@get call.respond(HttpStatusCode.Unauthorized)
                    if (!requireAuth(call, sessionId)) return@get
                    val name = call.parameters["name"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val diff = scripts.repo.diff("$name.loom")
                    call.respond(mapOf("patch" to diff.patch))
                }
                get("/git/status") {
                    val sessionId = call.request.header("X-Session-Id") ?: return@get call.respond(HttpStatusCode.Unauthorized)
                    if (!requireAuth(call, sessionId)) return@get
                    call.respond(scripts.repo.status())
                }
                post("/git/{name}/checkout/{hash}") {
                    val sessionId = call.request.header("X-Session-Id") ?: return@post call.respond(HttpStatusCode.Unauthorized)
                    if (!requireAuth(call, sessionId)) return@post
                    val name = call.parameters["name"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val hash = call.parameters["hash"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val ok = scripts.repo.checkout(hash, "$name.loom")
                    call.respond(buildJsonObject { put("ok", ok) })
                }
                get("/git/{name}/show/{hash}") {
                    val sessionId = call.request.header("X-Session-Id") ?: return@get call.respond(HttpStatusCode.Unauthorized)
                    if (!requireAuth(call, sessionId)) return@get
                    val name = call.parameters["name"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val hash = call.parameters["hash"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val content = scripts.repo.getFileAtCommit(hash, "$name.loom")
                        ?: return@get call.respond(HttpStatusCode.NotFound)
                    call.respond(mapOf("source" to content, "hash" to hash))
                }
                post("/git/commit") {
                    val sessionId = call.request.header("X-Session-Id") ?: return@post call.respond(HttpStatusCode.Unauthorized)
                    if (!requireAuth(call, sessionId)) return@post
                    val body = call.receive<Map<String, String>>()
                    val msg = body["message"] ?: "Manual commit"
                    val player = auth.getPlayerName(sessionId) ?: "Loom"
                    val commit = scripts.repo.commitAll(msg, player)
                    call.respond(buildJsonObject {
                        put("ok", commit != null)
                        if (commit != null) put("commit", buildJsonObject {
                            put("hash", commit.hash)
                            put("shortHash", commit.shortHash)
                            put("message", commit.message)
                            put("author", commit.author)
                            put("timestamp", commit.timestamp)
                        })
                    })
                }

                // WebSocket for live diagnostics
                webSocket("/ws/{sessionId}") {
                    val sessionId = call.parameters["sessionId"]
                    if (sessionId == null || !auth.isAuthenticated(sessionId)) {
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized"))
                        return@webSocket
                    }
                    activeSessions.add(this)
                    try {
                        LoomLanguageServer(scripts, this).handle()
                    } finally {
                        activeSessions.remove(this)
                    }
                }

                // Rename script
                post("/scripts/{name}/rename") {
                    val sessionId = call.request.header("X-Session-Id") ?: return@post call.respond(HttpStatusCode.Unauthorized)
                    if (!requireAuth(call, sessionId)) return@post
                    val name = call.parameters["name"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val body = call.receive<Map<String, String>>()
                    val newName = body["newName"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val player = auth.getPlayerName(sessionId) ?: "Loom"
                    val ok = scripts.renameScript(name, newName)
                    if (!ok) return@post call.respond(HttpStatusCode.Conflict,
                        buildJsonObject { put("error", "Rename failed: source not found or destination exists") })
                    val commit = scripts.repo.rename("$name.loom", "$newName.loom", "Rename $name → $newName", player)
                    if (commit == null) logger.warning("Git rename commit failed for $name → $newName")
                    call.respond(buildJsonObject { put("ok", true); put("newName", newName) })
                }

                // Serve the Monaco editor SPA
                get("/") {
                    val html = javaClass.getResourceAsStream("/web/index.html")?.readBytes()?.toString(Charsets.UTF_8)
                        ?: "<html><body><h1>Editor not found</h1></body></html>"
                    call.respondText(html, ContentType.Text.Html)
                }
                get("/editor.js") {
                    val js = javaClass.getResourceAsStream("/web/editor.js")?.readBytes()?.toString(Charsets.UTF_8)
                        ?: "console.error('editor.js not found');"
                    call.respondText(js, ContentType.Application.JavaScript)
                }
                get("/loom-language.js") {
                    val js = javaClass.getResourceAsStream("/web/loom-language.js")?.readBytes()?.toString(Charsets.UTF_8)
                        ?: "console.error('loom-language.js not found');"
                    call.respondText(js, ContentType.Application.JavaScript)
                }
            }
        }
        server?.start(wait = false)
        logger.info("Loom Web Editor started on http://${config.webHost}:${config.webPort}")
        logger.info("Open the editor and run /loom confirm <code> in-game to authenticate.")
    }

    fun stop() {
        // Close WebSocket sessions before stopping Netty to avoid RejectedExecutionException
        // from coroutine continuations dispatching onto a terminated event loop.
        runBlocking {
            activeSessions.toList().forEach { session ->
                runCatching { session.close(CloseReason(CloseReason.Codes.GOING_AWAY, "Server shutting down")) }
            }
        }
        activeSessions.clear()
        server?.stop(1000, 2000)
        server = null
    }

    private fun diagsToJson(diags: List<io.github.earth1283.loom.lang.Diagnostic>) = buildJsonArray {
        diags.forEach { d ->
            add(buildJsonObject {
                put("line", d.line); put("col", d.col)
                put("endLine", d.endLine); put("endCol", d.endCol)
                put("message", d.message); put("severity", d.severity.name)
            })
        }
    }

    private suspend fun requireAuth(call: ApplicationCall, sessionId: String): Boolean {
        if (!auth.isAuthenticated(sessionId)) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not authenticated"))
            return false
        }
        return true
    }
}
