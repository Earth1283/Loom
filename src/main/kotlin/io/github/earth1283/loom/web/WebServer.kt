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
import kotlinx.serialization.json.Json
import org.bukkit.plugin.Plugin
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
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (cause.message ?: "Unknown error")))
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
                    call.respond(mapOf(
                        "authenticated" to auth.isAuthenticated(sessionId),
                        "player" to (auth.getPlayerName(sessionId) ?: "")
                    ))
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
                    call.respond(mapOf("diagnostics" to diags, "state" to (scripts.get(name)?.state?.name ?: "ERROR")))
                }
                post("/scripts/{name}/unload") {
                    val sessionId = call.request.header("X-Session-Id") ?: return@post call.respond(HttpStatusCode.Unauthorized)
                    if (!requireAuth(call, sessionId)) return@post
                    val name = call.parameters["name"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                    scripts.unload(name)
                    call.respond(mapOf("ok" to true))
                }
                post("/scripts/{name}/reload") {
                    val sessionId = call.request.header("X-Session-Id") ?: return@post call.respond(HttpStatusCode.Unauthorized)
                    if (!requireAuth(call, sessionId)) return@post
                    val name = call.parameters["name"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val diags = scripts.reload(name)
                    call.respond(mapOf("diagnostics" to diags, "state" to (scripts.get(name)?.state?.name ?: "ERROR")))
                }

                // Validate (no disk write)
                post("/scripts/{name}/validate") {
                    val sessionId = call.request.header("X-Session-Id") ?: return@post call.respond(HttpStatusCode.Unauthorized)
                    if (!requireAuth(call, sessionId)) return@post
                    val name = call.parameters["name"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val body = call.receive<Map<String, String>>()
                    val source = body["source"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val diags = scripts.validate(name, source)
                    call.respond(mapOf("diagnostics" to diags))
                }

                // Git routes
                get("/git/{name}/log") {
                    val sessionId = call.request.header("X-Session-Id") ?: return@get call.respond(HttpStatusCode.Unauthorized)
                    if (!requireAuth(call, sessionId)) return@get
                    val name = call.parameters["name"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val commits = scripts.repo.log("$name.loom")
                    call.respond(commits)
                }
                get("/git/log") {
                    val sessionId = call.request.header("X-Session-Id") ?: return@get call.respond(HttpStatusCode.Unauthorized)
                    if (!requireAuth(call, sessionId)) return@get
                    val commits = scripts.repo.log()
                    call.respond(commits)
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
                    call.respond(mapOf("ok" to ok))
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
                    call.respond(mapOf("ok" to (commit != null), "commit" to commit))
                }

                // WebSocket for live diagnostics
                webSocket("/ws/{sessionId}") {
                    val sessionId = call.parameters["sessionId"]
                    if (sessionId == null || !auth.isAuthenticated(sessionId)) {
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized"))
                        return@webSocket
                    }
                    LoomLanguageServer(scripts, this).handle()
                }

                // Serve the Monaco editor SPA
                get("/") { call.respondText(editorHtml(config), ContentType.Text.Html) }
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
        server?.stop(1000, 2000)
        server = null
    }

    private suspend fun requireAuth(call: ApplicationCall, sessionId: String): Boolean {
        if (!auth.isAuthenticated(sessionId)) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not authenticated"))
            return false
        }
        return true
    }

    private fun editorHtml(cfg: LoomConfig) = """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Loom Editor</title>
  <style>
    * { margin: 0; padding: 0; box-sizing: border-box; }
    body { background: #1e1e1e; color: #d4d4d4; font-family: 'Segoe UI', sans-serif; display: flex; height: 100vh; overflow: hidden; }
    #sidebar { width: 220px; background: #252526; border-right: 1px solid #3c3c3c; display: flex; flex-direction: column; }
    #sidebar-header { padding: 12px 16px; font-size: 11px; font-weight: 700; letter-spacing: 1px; text-transform: uppercase; color: #bbb; border-bottom: 1px solid #3c3c3c; }
    #script-list { flex: 1; overflow-y: auto; }
    .script-item { padding: 8px 16px; cursor: pointer; font-size: 13px; display: flex; align-items: center; gap: 8px; border-left: 2px solid transparent; }
    .script-item:hover { background: #2a2d2e; }
    .script-item.active { background: #37373d; border-left-color: #007acc; }
    .script-status { width: 8px; height: 8px; border-radius: 50%; }
    .status-RUNNING { background: #4ec9b0; }
    .status-ERROR { background: #f14c4c; }
    .status-IDLE { background: #888; }
    .status-UNLOADED { background: #555; }
    #sidebar-actions { padding: 10px; border-top: 1px solid #3c3c3c; display: flex; flex-direction: column; gap: 6px; }
    #main { flex: 1; display: flex; flex-direction: column; overflow: hidden; }
    #toolbar { background: #2d2d30; border-bottom: 1px solid #3c3c3c; padding: 6px 12px; display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
    #editor-container { flex: 1; }
    #bottom-panel { height: 140px; background: #1e1e1e; border-top: 1px solid #3c3c3c; display: flex; flex-direction: column; }
    #bottom-tabs { display: flex; background: #252526; border-bottom: 1px solid #3c3c3c; }
    .tab-btn { padding: 4px 16px; font-size: 12px; cursor: pointer; border: none; background: transparent; color: #999; border-bottom: 2px solid transparent; }
    .tab-btn.active { color: #fff; border-bottom-color: #007acc; }
    #bottom-content { flex: 1; overflow-y: auto; padding: 8px 12px; font-size: 12px; font-family: monospace; }
    .diag-error { color: #f14c4c; }
    .diag-warn { color: #cca700; }
    .btn { padding: 5px 12px; border: none; border-radius: 3px; cursor: pointer; font-size: 12px; }
    .btn-primary { background: #007acc; color: #fff; }
    .btn-secondary { background: #3c3c3c; color: #d4d4d4; }
    .btn-danger { background: #c72e2e; color: #fff; }
    .btn-success { background: #388a34; color: #fff; }
    #auth-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.85); display: flex; align-items: center; justify-content: center; z-index: 100; }
    #auth-box { background: #252526; border: 1px solid #3c3c3c; border-radius: 8px; padding: 32px; max-width: 440px; width: 90%; text-align: center; }
    #auth-code { font-size: 28px; font-weight: 700; letter-spacing: 4px; color: #4ec9b0; font-family: monospace; margin: 16px 0; }
    #auth-instruction { color: #aaa; font-size: 13px; margin-bottom: 16px; }
    #git-panel { display: none; }
    .commit-item { padding: 6px 0; border-bottom: 1px solid #3c3c3c; }
    .commit-hash { color: #569cd6; font-family: monospace; }
    .commit-msg { color: #d4d4d4; }
    .commit-meta { color: #888; font-size: 11px; }
    #filename-display { color: #d4d4d4; font-weight: 600; flex: 1; }
    input[type=text] { background: #3c3c3c; border: 1px solid #555; color: #d4d4d4; padding: 4px 8px; border-radius: 3px; font-size: 12px; }
  </style>
</head>
<body>
  <div id="auth-overlay">
    <div id="auth-box">
      <div style="font-size: 20px; font-weight: 700; margin-bottom: 8px;">Loom Editor</div>
      <div id="auth-instruction">Connecting to server...</div>
      <div id="auth-code"></div>
      <div id="auth-waiting" style="color:#888; font-size:12px;"></div>
    </div>
  </div>

  <div id="sidebar">
    <div id="sidebar-header">Scripts</div>
    <div id="script-list"></div>
    <div id="sidebar-actions">
      <button class="btn btn-primary" onclick="newScript()">+ New Script</button>
      <button class="btn btn-secondary" onclick="refreshScripts()">&#8635; Refresh</button>
    </div>
  </div>

  <div id="main">
    <div id="toolbar">
      <span id="filename-display">No script open</span>
      <button class="btn btn-success" onclick="runScript()">&#9654; Load</button>
      <button class="btn btn-secondary" onclick="stopScript()">&#9632; Unload</button>
      <button class="btn btn-primary" onclick="saveScript()">&#128190; Save</button>
      <button class="btn btn-secondary" onclick="validateScript()">&#10003; Validate</button>
      <button class="btn btn-danger" onclick="deleteScript()">&#128465; Delete</button>
      <button class="btn btn-secondary" onclick="showGit()">&#128268; Git</button>
    </div>
    <div id="editor-container"></div>
    <div id="bottom-panel">
      <div id="bottom-tabs">
        <button class="tab-btn active" onclick="showTab('diagnostics')">Problems</button>
        <button class="tab-btn" onclick="showTab('git')">Git Log</button>
        <button class="tab-btn" onclick="showTab('output')">Output</button>
      </div>
      <div id="bottom-content">
        <div id="tab-diagnostics"><span style="color:#888">No problems detected.</span></div>
        <div id="tab-git" style="display:none"></div>
        <div id="tab-output" style="display:none"></div>
      </div>
    </div>
  </div>

  <script src="https://cdn.jsdelivr.net/npm/monaco-editor@0.52.2/min/vs/loader.js"></script>
  <script src="/loom-language.js"></script>
  <script src="/editor.js"></script>
</body>
</html>
""".trimIndent()
}
