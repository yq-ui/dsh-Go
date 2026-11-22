package io.github.ccch1mneyyy.dsh_app.devtools

import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 开发者工具: the parts of the bundled add-on that run inside the app rather than in the engine.
 *
 * Only Git, for now: JGit gives the app repository status, the recent log and a commit — the
 * operations a person can undo, and none that rewrite history. Every call runs on one worker
 * thread; the reply is posted back to the main thread.
 */
internal class DevToolsBridge(private val roots: () -> List<File>) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "dsh-devtools") }

    fun handle(call: MethodCall, result: MethodChannel.Result) {
        worker.execute {
            val out: Map<String, Any?>
            try {
                out = when (call.method) {
                    "info" -> info()
                    "gitStatus" -> status(path(call))
                    "gitLog" -> log(path(call), (call.argument<Int>("limit") ?: 20).coerceIn(1, 200))
                    "gitCommit" -> commit(path(call), call.argument<String>("message").orEmpty())
                    "gitInit" -> init(path(call))
                    else -> {
                        main.post { result.notImplemented() }
                        return@execute
                    }
                }
            } catch (e: Exception) {
                val message = e.message ?: e.javaClass.simpleName
                main.post { result.error("devtools", message, null) }
                return@execute
            }
            main.post { result.success(out) }
        }
    }

    /** Roots the Dart side may point at, and the engine's workspace when it exists. */
    private fun info(): Map<String, Any?> {
        val all = roots().map { it.canonicalFile }
        val workspace = all.map { File(it, "root/workspace") }.firstOrNull { it.isDirectory }
        return mapOf("roots" to all.map { it.path }, "workspace" to workspace?.path)
    }

    /** A path inside one of the roots; anything else is refused. */
    private fun path(call: MethodCall): File {
        val raw = call.argument<String>("path") ?: throw IllegalArgumentException("missing path")
        val canonical = File(raw).canonicalFile
        for (root in roots()) {
            val base = root.canonicalFile
            if (canonical == base || canonical.path.startsWith(base.path + File.separator)) return canonical
        }
        throw IllegalArgumentException("path outside the workspace")
    }

    private fun status(dir: File): Map<String, Any?> = Git.open(dir).use { git ->
        val s = git.status().call()
        mapOf(
            "clean" to s.isClean,
            "branch" to (git.repository.branch ?: ""),
            "added" to s.added.toList(),
            "changed" to s.changed.toList(),
            "removed" to s.removed.toList(),
            "missing" to s.missing.toList(),
            "untracked" to s.untracked.toList(),
            "modified" to s.modified.toList(),
        )
    }

    private fun log(dir: File, limit: Int): Map<String, Any?> = Git.open(dir).use { git ->
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val entries = ArrayList<Map<String, Any?>>()
        for (rev in git.log().setMaxCount(limit).call()) {
            entries.add(
                mapOf(
                    "id" to rev.name.take(12),
                    "message" to rev.shortMessage,
                    "author" to rev.authorIdent.name,
                    "time" to format.format(Date(rev.commitTime.toLong() * 1000L)),
                ),
            )
        }
        mapOf("entries" to entries)
    }

    private fun commit(dir: File, message: String): Map<String, Any?> {
        val text = message.trim()
        if (text.isEmpty()) throw IllegalArgumentException("empty message")
        return Git.open(dir).use { git ->
            git.add().addFilepattern(".").call()
            val rev = git.commit()
                .setMessage(text)
                .setAuthor(PersonIdent("DSH Go", "dsh-go@localhost"))
                .setCommitter(PersonIdent("DSH Go", "dsh-go@localhost"))
                .setAllowEmpty(false)
                .call()
            mapOf("id" to rev.name.take(12))
        }
    }

    private fun init(dir: File): Map<String, Any?> {
        if (!dir.isDirectory) throw IllegalArgumentException("not a directory")
        Git.init().setDirectory(dir).call().use { }
        return mapOf("initialized" to true)
    }
}
