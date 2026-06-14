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
import kotlinx.serialization.json.*
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

    private fun editorHtml(cfg: LoomConfig) = """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Loom Editor</title>
  <link rel="preconnect" href="https://fonts.googleapis.com">
  <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
  <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&family=JetBrains+Mono:wght@400;500&display=swap" rel="stylesheet">
  <style>
    *, *::before, *::after { margin: 0; padding: 0; box-sizing: border-box; }

    :root {
      --bg:          #13141e;
      --surface-1:   #1a1c2b;
      --surface-2:   #20223a;
      --surface-3:   #272a42;
      --border:      #2c3050;
      --border-hi:   #3a3f62;
      --accent:      #5b8af0;
      --accent-dim:  rgba(91,138,240,0.15);
      --accent-glow: rgba(91,138,240,0.3);
      --teal:        #3dd6c0;
      --error:       #ff4d6a;
      --warn:        #f5a623;
      --success:     #3dcc8f;
      --text-1:      #e8eaf4;
      --text-2:      #9298ba;
      --text-3:      #5a6080;
      --font-ui:     'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', system-ui, sans-serif;
      --font-mono:   'JetBrains Mono', 'Cascadia Code', 'Fira Code', 'Consolas', monospace;
      --r:           6px;
      --r-sm:        4px;
      --t:           0.14s ease;
    }

    html, body { height: 100%; }
    body {
      background: var(--bg);
      color: var(--text-1);
      font-family: var(--font-ui);
      font-size: 13px;
      display: flex;
      height: 100vh;
      overflow: hidden;
      -webkit-font-smoothing: antialiased;
    }

    ::-webkit-scrollbar { width: 5px; height: 5px; }
    ::-webkit-scrollbar-track { background: transparent; }
    ::-webkit-scrollbar-thumb { background: var(--border-hi); border-radius: 3px; }
    ::-webkit-scrollbar-thumb:hover { background: var(--text-3); }

    /* ── Sidebar ───────────────────────────────────────────────── */
    #sidebar {
      width: 224px;
      background: var(--surface-1);
      border-right: 1px solid var(--border);
      display: flex;
      flex-direction: column;
      flex-shrink: 0;
    }

    #sidebar-header {
      height: 46px;
      padding: 0 14px;
      display: flex;
      align-items: center;
      border-bottom: 1px solid var(--border);
      gap: 9px;
    }

    .sidebar-logo-mark {
      width: 22px; height: 22px;
      background: linear-gradient(135deg, var(--accent) 0%, var(--teal) 100%);
      border-radius: 5px;
      display: flex; align-items: center; justify-content: center;
      font-size: 12px; font-weight: 800; color: #fff;
      flex-shrink: 0;
      box-shadow: 0 2px 8px var(--accent-glow);
    }

    .sidebar-logo-name {
      font-size: 13px; font-weight: 700;
      letter-spacing: 1.2px;
      text-transform: uppercase;
      color: var(--text-1);
    }

    .sidebar-section {
      padding: 14px 14px 6px;
      font-size: 10px; font-weight: 600;
      letter-spacing: 1px;
      text-transform: uppercase;
      color: var(--text-3);
    }

    #script-list { flex: 1; overflow-y: auto; padding: 2px 6px 6px; }

    .script-item {
      padding: 7px 10px;
      cursor: pointer;
      font-size: 12.5px;
      font-family: var(--font-mono);
      display: flex; align-items: center; gap: 9px;
      border-radius: var(--r);
      color: var(--text-2);
      border: 1px solid transparent;
      margin-bottom: 1px;
      transition: background var(--t), color var(--t), border-color var(--t);
      user-select: none;
    }
    .script-item:hover  { background: var(--surface-3); color: var(--text-1); }
    .script-item.active {
      background: var(--accent-dim);
      border-color: rgba(91,138,240,0.28);
      color: var(--text-1);
    }

    .script-status {
      width: 6px; height: 6px;
      border-radius: 50%;
      flex-shrink: 0;
    }
    .status-RUNNING  { background: var(--teal);    box-shadow: 0 0 6px var(--teal); }
    .status-ERROR    { background: var(--error);   box-shadow: 0 0 6px var(--error); }
    .status-IDLE     { background: var(--warn); }
    .status-UNLOADED { background: var(--text-3); }

    #sidebar-actions {
      padding: 10px 8px;
      border-top: 1px solid var(--border);
      display: flex; flex-direction: column; gap: 5px;
    }

    /* ── Main ──────────────────────────────────────────────────── */
    #main { flex: 1; display: flex; flex-direction: column; overflow: hidden; min-width: 0; }

    /* ── Toolbar ───────────────────────────────────────────────── */
    #toolbar {
      height: 46px;
      background: var(--surface-1);
      border-bottom: 1px solid var(--border);
      padding: 0 10px;
      display: flex; align-items: center; gap: 3px;
      flex-shrink: 0;
    }

    #filename-display {
      flex: 1;
      font-family: var(--font-mono);
      font-size: 12px;
      color: var(--text-3);
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
      padding: 0 6px;
    }

    .tb-sep {
      width: 1px; height: 18px;
      background: var(--border-hi);
      margin: 0 4px; flex-shrink: 0;
    }

    /* ── Buttons ───────────────────────────────────────────────── */
    .btn {
      display: inline-flex; align-items: center; gap: 5px;
      padding: 5px 10px;
      border: 1px solid transparent;
      border-radius: var(--r-sm);
      cursor: pointer;
      font-size: 12px; font-family: var(--font-ui); font-weight: 500;
      white-space: nowrap; line-height: 1;
      transition: background var(--t), opacity var(--t), transform 60ms ease;
      -webkit-font-smoothing: antialiased;
    }
    .btn:active { transform: translateY(1px); }

    .btn-primary   { background: var(--accent); color: #fff; border-color: rgba(255,255,255,0.08); }
    .btn-primary:hover { background: #6b97f5; }

    .btn-secondary { background: var(--surface-3); color: var(--text-2); border-color: var(--border-hi); }
    .btn-secondary:hover { color: var(--text-1); background: #252740; }

    .btn-danger {
      background: rgba(255,77,106,0.1);
      color: var(--error);
      border-color: rgba(255,77,106,0.22);
    }
    .btn-danger:hover { background: rgba(255,77,106,0.2); }

    .btn-success {
      background: rgba(61,204,143,0.1);
      color: var(--success);
      border-color: rgba(61,204,143,0.22);
    }
    .btn-success:hover { background: rgba(61,204,143,0.2); }

    .btn svg { flex-shrink: 0; }

    /* full-width sidebar btn */
    .btn-block { width: 100%; justify-content: center; }

    /* ── Editor ────────────────────────────────────────────────── */
    #editor-container { flex: 1; min-height: 0; }

    /* ── Bottom panel ──────────────────────────────────────────── */
    #bottom-panel {
      height: 162px;
      background: var(--surface-1);
      border-top: 1px solid var(--border);
      display: flex; flex-direction: column; flex-shrink: 0;
    }

    #bottom-tabs {
      display: flex;
      height: 32px;
      border-bottom: 1px solid var(--border);
      padding: 0 8px;
      gap: 0;
    }

    .tab-btn {
      padding: 0 13px;
      font-size: 11.5px; font-weight: 500;
      cursor: pointer;
      border: none; background: transparent;
      color: var(--text-3);
      border-bottom: 2px solid transparent;
      letter-spacing: 0.2px;
      transition: color var(--t), border-color var(--t);
    }
    .tab-btn:hover  { color: var(--text-2); }
    .tab-btn.active { color: var(--text-1); border-bottom-color: var(--accent); }

    #bottom-content {
      flex: 1; overflow-y: auto;
      padding: 8px 14px;
      font-size: 12px; font-family: var(--font-mono); line-height: 1.7;
    }

    .diag-error   { color: var(--error); }
    .diag-warning { color: var(--warn); }
    .diag-info    { color: var(--text-2); }

    /* ── Git log ───────────────────────────────────────────────── */
    .commit-item {
      padding: 6px 0;
      border-bottom: 1px solid var(--border);
      display: flex; align-items: baseline; gap: 8px; flex-wrap: wrap;
    }
    .commit-hash  { color: var(--accent); font-family: var(--font-mono); font-size: 11px; cursor: pointer; }
    .commit-hash:hover { text-decoration: underline; }
    .commit-msg   { color: var(--text-1); font-family: var(--font-ui); }
    .commit-meta  { color: var(--text-3); font-size: 11px; font-family: var(--font-ui); }

    /* ── Auth overlay ──────────────────────────────────────────── */
    #auth-overlay {
      position: fixed; inset: 0;
      background: rgba(7,8,12,0.8);
      backdrop-filter: blur(10px);
      display: flex; align-items: center; justify-content: center;
      z-index: 100;
    }

    #auth-box {
      background: var(--surface-2);
      border: 1px solid var(--border-hi);
      border-radius: 14px;
      padding: 40px 44px;
      max-width: 440px; width: 90%;
      text-align: center;
      box-shadow: 0 32px 80px rgba(0,0,0,0.55), 0 0 0 1px rgba(255,255,255,0.04) inset;
    }

    .auth-mark {
      width: 48px; height: 48px;
      background: linear-gradient(135deg, var(--accent), var(--teal));
      border-radius: 12px;
      display: flex; align-items: center; justify-content: center;
      font-size: 22px; font-weight: 800; color: #fff;
      margin: 0 auto 18px;
      box-shadow: 0 6px 24px var(--accent-glow);
    }

    .auth-title    { font-size: 19px; font-weight: 700; color: var(--text-1); margin-bottom: 4px; }
    .auth-subtitle { font-size: 12px; color: var(--text-3); margin-bottom: 22px; }

    #auth-code {
      font-size: 26px; font-weight: 700;
      letter-spacing: 4px;
      color: var(--teal);
      font-family: var(--font-mono);
      margin: 14px 0;
      padding: 14px 18px;
      background: rgba(61,214,192,0.07);
      border-radius: var(--r);
      border: 1px solid rgba(61,214,192,0.18);
    }

    #auth-instruction { color: var(--text-2); font-size: 13px; margin-bottom: 10px; line-height: 1.65; }
    #auth-instruction code { color: var(--teal); font-family: var(--font-mono); font-size: 13px; }
    #auth-waiting { color: var(--text-3); font-size: 11.5px; margin-top: 10px; }

    /* ── Modal ─────────────────────────────────────────────────── */
    #modal-overlay {
      position: fixed; inset: 0;
      background: rgba(0,0,0,0.55);
      backdrop-filter: blur(6px);
      display: flex; align-items: center; justify-content: center;
      z-index: 200;
      opacity: 0; pointer-events: none;
      transition: opacity 0.14s ease;
    }
    #modal-overlay.open { opacity: 1; pointer-events: all; }

    #modal-box {
      background: var(--surface-2);
      border: 1px solid var(--border-hi);
      border-radius: 10px;
      padding: 26px 28px;
      min-width: 340px; max-width: 480px; width: 90%;
      display: flex; flex-direction: column; gap: 16px;
      box-shadow: 0 20px 60px rgba(0,0,0,0.5);
      transform: translateY(-8px) scale(0.98);
      opacity: 0;
      transition: transform 0.14s ease, opacity 0.14s ease;
    }
    #modal-overlay.open #modal-box { transform: translateY(0) scale(1); opacity: 1; }

    #modal-message { font-size: 13.5px; color: var(--text-1); line-height: 1.6; font-weight: 500; }

    #modal-input {
      width: 100%;
      background: var(--surface-3);
      border: 1px solid var(--border-hi);
      color: var(--text-1);
      padding: 8px 12px;
      border-radius: var(--r-sm);
      font-size: 13px; font-family: var(--font-mono);
      outline: none;
      transition: border-color 0.14s;
    }
    #modal-input:focus { border-color: var(--accent); }

    #modal-actions { display: flex; gap: 8px; justify-content: flex-end; }

    input[type=text] {
      background: var(--surface-3);
      border: 1px solid var(--border-hi);
      color: var(--text-1);
      padding: 5px 10px;
      border-radius: var(--r-sm);
      font-size: 12px; font-family: var(--font-ui);
      outline: none;
    }
    input[type=text]:focus { border-color: var(--accent); }
  </style>
</head>
<body>

  <!-- Auth overlay -->
  <div id="auth-overlay">
    <div id="auth-box">
      <div class="auth-mark">L</div>
      <div class="auth-title">Loom Editor</div>
      <div class="auth-subtitle">Minecraft Script Engine</div>
      <div id="auth-instruction">Connecting to server&hellip;</div>
      <div id="auth-code"></div>
      <div id="auth-waiting"></div>
    </div>
  </div>

  <!-- Modal dialog -->
  <div id="modal-overlay">
    <div id="modal-box">
      <div id="modal-message"></div>
      <input id="modal-input" type="text" autocomplete="off">
      <div id="modal-actions">
        <button id="modal-cancel" class="btn btn-secondary">Cancel</button>
        <button id="modal-ok"     class="btn btn-primary">OK</button>
      </div>
    </div>
  </div>

  <!-- Sidebar -->
  <div id="sidebar">
    <div id="sidebar-header">
      <div class="sidebar-logo-mark">L</div>
      <span class="sidebar-logo-name">Loom</span>
    </div>
    <div class="sidebar-section">Scripts</div>
    <div id="script-list"></div>
    <div id="sidebar-actions">
      <button class="btn btn-primary btn-block" onclick="newScript()">
        <svg width="11" height="11" viewBox="0 0 11 11" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><line x1="5.5" y1="1" x2="5.5" y2="10"/><line x1="1" y1="5.5" x2="10" y2="5.5"/></svg>
        New Script
      </button>
      <button class="btn btn-secondary btn-block" onclick="refreshScripts()">
        <svg width="11" height="11" viewBox="0 0 11 11" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M9.5 5.5A4 4 0 115.5 1.5"/><polyline points="9.5,1 9.5,5.5 5,5.5"/></svg>
        Refresh
      </button>
    </div>
  </div>

  <!-- Main -->
  <div id="main">
    <div id="toolbar">
      <span id="filename-display">No script open</span>
      <div class="tb-sep"></div>
      <button class="btn btn-success" onclick="runScript()" title="Load script">
        <svg width="11" height="11" viewBox="0 0 11 11" fill="currentColor"><polygon points="1.5,0.5 10.5,5.5 1.5,10.5"/></svg>
        Load
      </button>
      <button class="btn btn-secondary" onclick="stopScript()" title="Unload script">
        <svg width="9" height="9" viewBox="0 0 9 9" fill="currentColor"><rect x="0.5" y="0.5" width="8" height="8" rx="1"/></svg>
        Unload
      </button>
      <div class="tb-sep"></div>
      <button class="btn btn-primary" onclick="saveScript()" title="Save &amp; commit">
        <svg width="12" height="12" viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M6 1v8M3 6l3 3 3-3"/><line x1="1.5" y1="11" x2="10.5" y2="11"/></svg>
        Save
      </button>
      <button class="btn btn-secondary" onclick="validateScript()" title="Validate syntax">
        <svg width="12" height="12" viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><polyline points="1.5,6.5 4.5,9.5 10.5,2.5"/></svg>
        Validate
      </button>
      <div class="tb-sep"></div>
      <button class="btn btn-secondary" onclick="showGit()" title="Git history">
        <svg width="12" height="12" viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"><circle cx="3" cy="3" r="1.4"/><circle cx="9" cy="3" r="1.4"/><circle cx="3" cy="9" r="1.4"/><path d="M3 4.4v3.2M4.4 3h1.6a1.4 1.4 0 011.4 1.4v.8A1.4 1.4 0 009 6.6V9"/></svg>
        History
      </button>
      <button class="btn btn-danger" onclick="deleteScript()" title="Delete script">
        <svg width="12" height="12" viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="1,3 11,3"/><path d="M4 3V2a.8.8 0 01.8-.8h2.4A.8.8 0 018 2v1"/><path d="M2 3l.8 8h6.4L10 3"/></svg>
        Delete
      </button>
    </div>
    <div id="editor-container"></div>
    <div id="bottom-panel">
      <div id="bottom-tabs">
        <button class="tab-btn active" onclick="showTab('diagnostics')">Problems</button>
        <button class="tab-btn"        onclick="showTab('git')">Git Log</button>
        <button class="tab-btn"        onclick="showTab('output')">Output</button>
      </div>
      <div id="bottom-content">
        <div id="tab-diagnostics"><span style="color:var(--text-3)">No problems detected.</span></div>
        <div id="tab-git"    style="display:none"></div>
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
