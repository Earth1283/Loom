package io.github.earth1283.loom.web

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class AuthManager {
    // pending: sessionId -> pendingCode
    private val pending = ConcurrentHashMap<String, String>()
    // authenticated sessions (sessionId -> playerName)
    private val sessions = ConcurrentHashMap<String, String>()

    /**
     * Called when a browser visits the editor. Returns a (sessionId, confirmCode) pair.
     * The confirm code is what the player types in-game.
     */
    fun createPendingSession(): Pair<String, String> {
        val sessionId = UUID.randomUUID().toString()
        val code = generateCode()
        pending[sessionId] = code
        return sessionId to code
    }

    /**
     * Called from `/loom confirm <code>` in-game.
     * Returns the sessionId that was confirmed, or null if the code was invalid.
     */
    fun confirm(code: String, playerName: String): String? {
        val entry = pending.entries.firstOrNull { it.value.equals(code, ignoreCase = true) }
            ?: return null
        val sessionId = entry.key
        pending.remove(sessionId)
        sessions[sessionId] = playerName
        return sessionId
    }

    fun isAuthenticated(sessionId: String) = sessions.containsKey(sessionId)

    fun getPlayerName(sessionId: String) = sessions[sessionId]

    fun revokeSession(sessionId: String) {
        sessions.remove(sessionId)
    }

    fun revokeAll() {
        sessions.clear()
        pending.clear()
    }

    fun pendingCount() = pending.size
    fun sessionCount() = sessions.size

    private fun generateCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..8).map { chars.random() }.joinToString("")
    }
}
