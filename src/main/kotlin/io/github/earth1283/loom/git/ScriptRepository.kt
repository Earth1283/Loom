package io.github.earth1283.loom.git

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.diff.RawTextComparator
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.logging.Logger

@Serializable
data class CommitInfo(
    val hash: String,
    val shortHash: String,
    val message: String,
    val author: String,
    val timestamp: Long
)

data class DiffResult(val patch: String)

class ScriptRepository(private val dir: File, private val logger: Logger) {
    private var git: Git? = null

    fun init() {
        try {
            if (File(dir, ".git").exists()) {
                git = Git.open(dir)
                val commitCount = try { git!!.log().call().count() } catch (_: Exception) { 0 }
                logger.info("Opened existing git repository (${dir.name}/, $commitCount commit(s))")
            } else {
                logger.info("Initialising new git repository in ${dir.name}/…")
                val g = Git.init().setDirectory(dir).call()
                val gitignore = File(dir, ".gitignore")
                gitignore.writeText("# Loom script repository\n*.tmp\n")
                g.add().addFilepattern(".gitignore").call()
                g.commit()
                    .setMessage("Init Loom script repository")
                    .setAuthor(ident("Loom"))
                    .call()
                git = g
                logger.info("Git repository created with initial commit.")
            }
        } catch (e: Exception) {
            logger.warning("Failed to init git repo: ${e.message}")
        }
    }

    fun commit(filename: String, message: String, author: String = "Loom"): CommitInfo? {
        val g = git ?: return null
        return try {
            g.add().addFilepattern(filename).call()
            val commit = g.commit()
                .setMessage(message)
                .setAuthor(ident(author))
                .call()
            commit.toInfo()
        } catch (e: Exception) {
            logger.warning("Git commit failed: ${e.message}")
            null
        }
    }

    fun commitAll(message: String, author: String = "Loom"): CommitInfo? {
        val g = git ?: return null
        return try {
            g.add().addFilepattern(".").call()
            val status = g.status().call()
            if (status.isClean) return null
            val commit = g.commit()
                .setMessage(message)
                .setAuthor(ident(author))
                .call()
            commit.toInfo()
        } catch (e: Exception) {
            logger.warning("Git commit-all failed: ${e.message}")
            null
        }
    }

    fun log(filename: String? = null, maxCount: Int = 50): List<CommitInfo> {
        val g = git ?: return emptyList()
        return try {
            val cmd = g.log().setMaxCount(maxCount)
            if (filename != null) cmd.addPath(filename)
            cmd.call().map { it.toInfo() }
        } catch (e: Exception) {
            logger.warning("Git log failed: ${e.message}")
            emptyList()
        }
    }

    fun diff(filename: String? = null): DiffResult {
        val g = git ?: return DiffResult("")
        return try {
            val out = ByteArrayOutputStream()
            DiffFormatter(out).use { fmt ->
                fmt.setRepository(g.repository)
                fmt.setDiffComparator(RawTextComparator.DEFAULT)
                fmt.isDetectRenames = true
                val diffs = if (filename != null)
                    g.diff().setPathFilter(org.eclipse.jgit.treewalk.filter.PathFilter.create(filename)).call()
                else
                    g.diff().call()
                diffs.forEach { fmt.format(it) }
                fmt.flush()
            }
            DiffResult(out.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            DiffResult("Error: ${e.message}")
        }
    }

    fun diffCommits(from: String, to: String = "HEAD"): DiffResult {
        val g = git ?: return DiffResult("")
        return try {
            val out = ByteArrayOutputStream()
            val repo = g.repository
            val fromId = repo.resolve(from) ?: return DiffResult("Unknown ref: $from")
            val toId = repo.resolve(to) ?: return DiffResult("Unknown ref: $to")
            DiffFormatter(out).use { fmt ->
                fmt.setRepository(repo)
                fmt.setDiffComparator(RawTextComparator.DEFAULT)
                val fromTree = org.eclipse.jgit.revwalk.RevWalk(repo).use { it.parseCommit(fromId).tree }
                val toTree = org.eclipse.jgit.revwalk.RevWalk(repo).use { it.parseCommit(toId).tree }
                val diffs = fmt.scan(fromTree, toTree)
                diffs.forEach { fmt.format(it) }
                fmt.flush()
            }
            DiffResult(out.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            DiffResult("Error: ${e.message}")
        }
    }

    fun checkout(hash: String, filename: String): Boolean {
        val g = git ?: return false
        return try {
            g.checkout().setStartPoint(hash).addPath(filename).call()
            true
        } catch (e: Exception) {
            logger.warning("Git checkout failed: ${e.message}")
            false
        }
    }

    fun resetFile(filename: String): Boolean {
        val g = git ?: return false
        return try {
            g.checkout().addPath(filename).call()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun getFileAtCommit(hash: String, filename: String): String? {
        val g = git ?: return null
        return try {
            val repo = g.repository
            val commitId = repo.resolve(hash) ?: return null
            val commit = org.eclipse.jgit.revwalk.RevWalk(repo).use { it.parseCommit(commitId) }
            org.eclipse.jgit.treewalk.TreeWalk.forPath(repo, filename, commit.tree)?.use { tw ->
                val loader = repo.open(tw.getObjectId(0))
                loader.bytes.toString(Charsets.UTF_8)
            }
        } catch (e: Exception) {
            null
        }
    }

    fun status(): Map<String, String> {
        val g = git ?: return emptyMap()
        return try {
            val s = g.status().call()
            val result = mutableMapOf<String, String>()
            s.added.forEach { result[it] = "added" }
            s.modified.forEach { result[it] = "modified" }
            s.removed.forEach { result[it] = "deleted" }
            s.untracked.forEach { result[it] = "untracked" }
            s.conflicting.forEach { result[it] = "conflict" }
            result
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun close() { git?.close() }

    private fun ident(name: String) = PersonIdent(name, "loom@minecraft.local")

    private fun RevCommit.toInfo() = CommitInfo(
        hash = name,
        shortHash = name.take(7),
        message = shortMessage,
        author = authorIdent.name,
        timestamp = authorIdent.`when`.time
    )
}
