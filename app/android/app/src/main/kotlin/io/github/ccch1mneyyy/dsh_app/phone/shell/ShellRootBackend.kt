package io.github.ccch1mneyyy.dsh_app.phone.shell

import android.content.Context
import kotlin.concurrent.thread

/** Explicit su channel; authorization is requested only after the person chooses Connect. */
internal class ShellRootBackend(private val context: Context, private val onChanged: () -> Unit = {}) {
    private val guard = Any()
    private var generation = 0L
    private var busy = false
    @Volatile private var channel: ShellLink? = null
    @Volatile private var pending: Process? = null

    fun status(): Map<String, Any?> {
        val live = verifiedLink()
        return mapOf("connected" to (live != null))
    }

    fun verifiedLink(): ShellLink? {
        val live = channel?.takeIf { it.alive() && it.uid == 0 }
        if (channel != null && live == null) disconnect()
        return live
    }

    fun connect(wanted: () -> Boolean): ShellConnection {
        channel?.let { current ->
            if (current.alive() && current.uid == 0 && wanted()) return ShellConnection(current)
            if (!current.alive()) current.close()
            synchronized(guard) { if (channel === current) channel = null }
        }
        val ticket = synchronized(guard) {
            if (busy) return ShellConnection(errorCode = "busy")
            busy = true
            ++generation
        }
        var candidate: ShellLink? = null
        var pendingProcess: Process? = null
        var published = false
        return try {
            if (!wanted()) return ShellConnection(errorCode = "cancelled")
            val app = context.applicationInfo
            val classpath = (listOf(app.sourceDir) + app.splitSourceDirs.orEmpty()).joinToString(":")
            val argv = RootShellCommand.appProcess(classpath, AndroidShellMain::class.java.name)
                ?: return ShellConnection(errorCode = "invalidArguments")
            val process = ProcessBuilder(argv).start()
            pendingProcess = process
            val admitted = synchronized(guard) {
                if (generation == ticket && wanted()) { pending = process; true } else false
            }
            if (!admitted) {
                process.destroyForcibly()
                return ShellConnection(errorCode = "cancelled")
            }
            if (!process.isAlive) return ShellConnection(errorCode = "rootStartFailed")
            thread(isDaemon = true, name = "dsh-root-shell-stderr") {
                runCatching { process.errorStream.use { input -> val buffer = ByteArray(4096); while (input.read(buffer) >= 0) Unit } }
            }
            val link = ShellLink.root(process)
            candidate = link
            if (!link.probe(ROOT_AUTH_TIMEOUT_MS) || link.uid != 0) return ShellConnection(errorCode = "rootIdentityMismatch")
            synchronized(guard) {
                if (generation != ticket || !wanted()) return ShellConnection(errorCode = "cancelled")
                pending = null
                channel = link
                published = true
            }
            ShellConnection(link)
        } catch (_: Exception) {
            ShellConnection(errorCode = "rootStartFailed")
        } finally {
            if (!published) {
                candidate?.close() ?: pendingProcess?.let { process -> runCatching { process.destroyForcibly() } }
            }
            synchronized(guard) {
                if (pending === pendingProcess) pending = null
                busy = false
            }
            onChanged()
        }
    }

    fun disconnect() {
        val closing = synchronized(guard) {
            ++generation
            val old = channel
            channel = null
            val starting = pending
            pending = null
            old to starting
        }
        closing.first?.close()
        closing.second?.let { process -> runCatching { process.destroyForcibly() } }
        onChanged()
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\''") + "'"

    private companion object {
        const val ROOT_AUTH_TIMEOUT_MS = 120_000L
    }
}
