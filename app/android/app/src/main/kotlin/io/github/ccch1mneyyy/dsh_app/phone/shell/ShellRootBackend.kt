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
    @Volatile private var lastError: String? = null
    @Volatile private var lastSu: String? = null

    fun status(): Map<String, Any?> {
        val live = verifiedLink()
        return mapOf(
            "connected" to (live != null),
            "su" to lastSu,
            "error" to lastError,
        )
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
        var pendingProcess: Process? = null
        var published = false
        val errors = StringBuilder()
        var startedAny = false
        return try {
            if (!wanted()) return ShellConnection(errorCode = "cancelled")
            val app = context.applicationInfo
            val classpath = (listOf(app.sourceDir) + app.splitSourceDirs.orEmpty()).joinToString(":")
            for (su in RootShellCommand.candidates) {
                if (!wanted()) return ShellConnection(errorCode = "cancelled")
                val argv = RootShellCommand.appProcess(classpath, AndroidShellMain::class.java.name, su) ?: continue
                val process = try {
                    ProcessBuilder(argv).start()
                } catch (error: Exception) {
                    errors.append(su).append(" start:").append(error.message).append("; ")
                    continue
                }
                startedAny = true
                pendingProcess = process
                synchronized(guard) { pending = process }
                val stderr = StringBuilder()
                thread(isDaemon = true, name = "dsh-root-shell-stderr") {
                    runCatching {
                        process.errorStream.use { input ->
                            val buffer = ByteArray(1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                synchronized(stderr) {
                                    if (stderr.length < 2048) stderr.append(String(buffer, 0, read))
                                }
                            }
                        }
                    }
                }
                val link = ShellLink.root(process)
                val probed = runCatching { link.probe(ROOT_AUTH_TIMEOUT_MS) }.getOrDefault(false)
                if (probed && link.uid == 0) {
                    synchronized(guard) {
                        if (generation != ticket || !wanted()) {
                            link.close()
                            return ShellConnection(errorCode = "cancelled")
                        }
                        pending = null
                        channel = link
                        published = true
                    }
                    lastSu = su
                    lastError = null
                    return ShellConnection(link)
                }
                val detail = synchronized(stderr) { stderr.toString().trim() }
                errors.append(su).append(" uid=").append(link.uid)
                if (detail.isNotEmpty()) errors.append(" stderr=").append(detail.take(300))
                errors.append("; ")
                runCatching { link.close() }
                runCatching { process.destroyForcibly() }
                pendingProcess = null
                synchronized(guard) { if (pending === process) pending = null }
            }
            lastError = errors.toString().take(800).ifEmpty { "no su candidate started" }
            ShellConnection(errorCode = if (startedAny) "rootIdentityMismatch" else "rootStartFailed")
        } catch (error: Exception) {
            lastError = "exception:" + (error.message ?: error.javaClass.simpleName)
            ShellConnection(errorCode = "rootStartFailed")
        } finally {
            if (!published) {
                pendingProcess?.let { process -> runCatching { process.destroyForcibly() } }
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

    private companion object {
        const val ROOT_AUTH_TIMEOUT_MS = 120_000L
    }
}
