package io.github.ccch1mneyyy.dsh_app.runtime

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream

/**
 * Reverse-engineering toolchain installed on first launch: the App downloads one archive and
 * unpacks it into the engine rootfs, so the library and its wrappers exist without any manual
 * step. Absent network the library simply stays missing; a later launch retries.
 */
internal object ExtrasToolchain {
    private const val URL_XZ = "https://github.com/yq-ui/dsh-Go/releases/download/re-toolchain-v1/re-toolchain-v1.zip"
    private const val ROOT = "opt/dsh-re-tools"
    private const val MARKER = "root/.dsh/.dsh-go-re-tools.json"
    private const val MAX_BYTES = 3L * 1024 * 1024 * 1024
    private val SAFE = Regex("^[A-Za-z0-9@._+-][A-Za-z0-9@._+/-]*$")

    private const val JAVA_HOME = "/opt/dsh-re-tools/runtime/jdk21"
    private const val GHIDRA = "/opt/dsh-re-tools/ghidra/ghidra_12.1.4_PUBLIC"

    private val wrappers = mapOf(
        "frida" to "#!/bin/sh\nexport PYTHONPATH=/opt/dsh-re-tools/android/frida-py\nexec /opt/python/bin/python3.12 -c \"from frida_tools.repl import main; main()\" \"\$@\"\n",
        "jadx" to "#!/bin/sh\nexport JAVA_HOME=$JAVA_HOME\nexec /opt/dsh-re-tools/android/jadx/bin/jadx \"\$@\"\n",
        "r2" to "#!/bin/sh\nexport LD_LIBRARY_PATH=/opt/dsh-re-tools/native/radare2/usr/lib/aarch64-linux-gnu:/opt/dsh-re-tools/native/radare2/usr/lib\nexec /opt/dsh-re-tools/native/radare2/usr/bin/r2 \"\$@\"\n",
        "radare2" to "#!/bin/sh\nexec /opt/dsh-re-tools/bin/r2 \"\$@\"\n",
        "java21" to "#!/bin/sh\nexec $JAVA_HOME/bin/java \"\$@\"\n",
        "analyzeHeadless" to "#!/bin/sh\nexport JAVA_HOME=$JAVA_HOME\nexport GHIDRA_INSTALL_DIR=$GHIDRA\nexec \"$GHIDRA/support/analyzeHeadless\" \"\$@\"\n",
    )

    /** Runs on a worker: never on the UI thread, never blocking the launch. */
    fun install(context: Context, rootfs: File, log: (String) -> Unit) {
        installAddon(context, rootfs, log)
        val root = File(rootfs, ROOT)
        val marker = File(rootfs, MARKER)
        if (File(root, "catalog.json").isFile && marker.isFile) return
        val archive = File(context.cacheDir, "re-toolchain-v1.zip")
        try {
            if (!archive.isFile || archive.length() < 1024) {
                log("[dsh-app] reverse toolchain: downloading (one time, ~430 MB)")
                download(URL_XZ, archive)
            }
            log("[dsh-app] reverse toolchain: extracting")
            extract(archive, rootfs)
            writeWrappers(rootfs)
            marker.parentFile?.mkdirs()
            marker.writeText("{\"v\":1}\n")
            archive.delete()
            log("[dsh-app] reverse toolchain: ready at /opt/dsh-re-tools")
        } catch (e: Exception) {
            log("[dsh-app] reverse toolchain not installed: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    // ── 内置补充包 ──────────────────────────────────────────────────────────────
    // 缺少的几项（rizin / LLDB / LIEF / Unicorn / tree-sitter / Semgrep / JADX headless MCP
    // 以及四个 JVM 库的 jar）由 CI 从上游取回，打进 APK 的 assets；这里只做解包与拷贝，
    // 不在运行时下载、不跑 pip、不跑 dpkg，失败只会少这些工具，不影响引擎启动。

    private const val ADDON_ASSET = "flutter_assets/assets/builtin/re-toolchain-addon.zip"
    private const val ADDON_MARKER = "root/.dsh/.dsh-go-re-addon.json"
    private const val ADDON_VERSION = 1
    private const val ADDON_MAX_ENTRIES = 60_000
    private const val ADDON_MAX_TOTAL = 3L * 1024 * 1024 * 1024

    /** Installs the bundled add-on; safe to call on every start (a marker skips a repeat). */
    fun installAddon(context: Context, rootfs: File, log: (String) -> Unit) {
        val marker = File(rootfs, ADDON_MARKER)
        val done = try {
            marker.isFile && org.json.JSONObject(marker.readText()).optInt("v") >= ADDON_VERSION
        } catch (_: Exception) {
            false
        }
        if (done) return
        val staging = File(context.cacheDir, "re-addon-staging")
        try {
            staging.deleteRecursively()
            if (!staging.isDirectory && !staging.mkdirs()) throw IOException("cannot stage add-on")
            var entries = 0
            var total = 0L
            context.assets.open(ADDON_ASSET).use { stream ->
                ZipInputStream(stream.buffered()).use { zip ->
                    val root = staging.canonicalPath
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (++entries > ADDON_MAX_ENTRIES) throw IOException("add-on has too many entries")
                        val name = entry.name.replace('\\', '/')
                        if (!SAFE.matches(name) || name.contains("..")) throw IOException("unsafe add-on entry")
                        val target = File(staging, name)
                        val canonical = target.canonicalPath
                        if (canonical != root && !canonical.startsWith(root + File.separator)) {
                            throw IOException("unsafe add-on entry")
                        }
                        if (entry.isDirectory) {
                            if (!target.isDirectory && !target.mkdirs()) throw IOException("cannot create add-on directory")
                            zip.closeEntry()
                            continue
                        }
                        val parent = target.parentFile
                        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
                            throw IOException("cannot create add-on directory")
                        }
                        FileOutputStream(target).use { out ->
                            val buffer = ByteArray(128 * 1024)
                            while (true) {
                                val read = zip.read(buffer)
                                if (read < 0) break
                                total += read
                                if (total > ADDON_MAX_TOTAL) throw IOException("add-on is too large")
                                out.write(buffer, 0, read)
                            }
                            out.fd.sync()
                        }
                        zip.closeEntry()
                    }
                }
            }
            val tree = File(staging, "tree")
            if (!tree.isDirectory) throw IOException("add-on has no tree")
            val copied = copyTree(tree, rootfs)
            for (path in execList(staging)) {
                runCatching { File(rootfs, path).setExecutable(true, false) }
            }
            marker.parentFile?.mkdirs()
            marker.writeText(
                org.json.JSONObject().put("v", ADDON_VERSION).put("files", copied).put("bytes", total).toString(),
            )
            log("[dsh-app] reverse toolchain add-on: ready (" + copied + " files)")
        } catch (e: Exception) {
            log("[dsh-app] reverse toolchain add-on not installed: " + (e.message ?: e.javaClass.simpleName))
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Copies [from] into [into], returning the number of files written. */
    private fun copyTree(from: File, into: File): Int {
        val root = into.canonicalPath
        var count = 0
        from.walkTopDown().forEach { source ->
            if (source == from) return@forEach
            val rel = source.relativeTo(from).path.replace(File.separatorChar, '/')
            if (rel.contains("..")) return@forEach
            val target = File(into, rel)
            val canonical = target.canonicalPath
            if (canonical != root && !canonical.startsWith(root + File.separator)) return@forEach
            if (source.isDirectory) {
                if (!target.isDirectory) target.mkdirs()
            } else {
                target.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
                Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                count++
            }
        }
        return count
    }

    /** Paths (relative to the guest root) the add-on wants executable; CI writes the list. */
    private fun execList(staging: File): List<String> {
        val file = File(staging, "exec.txt")
        if (!file.isFile) return emptyList()
        return runCatching {
            file.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.contains("..") }
        }.getOrDefault(emptyList())
    }

    private fun download(url: String, target: File) {
        var current = URL(url)
        var redirects = 0
        while (redirects++ < 6) {
            val connection = current.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 30_000
            connection.readTimeout = 120_000
            connection.setRequestProperty("User-Agent", "dsh-go")
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location") ?: throw IOException("redirect without location")
                    val next = URL(current, location)
                    if (!next.protocol.equals("https", ignoreCase = true)) throw IOException("insecure redirect")
                    current = next
                    continue
                }
                if (code != 200) throw IOException("http " + code)
                val declared = connection.contentLengthLong
                if (declared > MAX_BYTES) throw IOException("archive too large")
                val tmp = File(target.parentFile, target.name + ".part")
                connection.inputStream.use { input ->
                    FileOutputStream(tmp).use { out ->
                        val buffer = ByteArray(256 * 1024)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_BYTES) throw IOException("archive too large")
                            out.write(buffer, 0, read)
                        }
                        out.fd.sync()
                    }
                }
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                return
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("too many redirects")
    }

    private fun extract(archive: File, rootfs: File) {
        val root = rootfs.canonicalFile.toPath()
        var total = 0L
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (!SAFE.matches(name) || name.contains("..")) throw IOException("unsafe entry")
                val target = File(rootfs, name)
                val resolved = target.canonicalFile.toPath()
                if (resolved == root || !resolved.startsWith(root)) throw IOException("unsafe entry")
                if (entry.isDirectory) {
                    if (!target.isDirectory && !target.mkdirs()) throw IOException("cannot create directory")
                    zip.closeEntry()
                    continue
                }
                val parent = target.parentFile
                if (parent != null && !parent.isDirectory && !parent.mkdirs()) throw IOException("cannot create directory")
                FileOutputStream(target).use { out ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_BYTES) throw IOException("archive too large")
                        out.write(buffer, 0, read)
                    }
                    out.fd.sync()
                }
                zip.closeEntry()
            }
        }
    }

    /** Zip carries no mode bits: mark the executables this library actually needs. */
    private fun writeWrappers(rootfs: File) {
        val tools = File(rootfs, ROOT)
        val bin = File(tools, "bin")
        if (!bin.isDirectory && !bin.mkdirs()) return
        for ((name, body) in wrappers) {
            val file = File(bin, name)
            file.writeText(body)
            file.setExecutable(true, false)
        }
        val local = File(rootfs, "usr/local/bin")
        if (local.isDirectory || local.mkdirs()) {
            for (name in wrappers.keys) {
                val source = File(bin, name)
                if (!source.isFile) continue
                val target = File(local, name)
                try {
                    Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    target.setExecutable(true, false)
                } catch (_: Exception) {
                }
            }
        }
        for (path in listOf(
            "opt/dsh-re-tools/native/radare2/usr/bin",
            "opt/dsh-re-tools/ghidra/ghidra_12.1.4_PUBLIC/Ghidra/Features/Decompiler/os/linux_arm_64",
            "opt/dsh-re-tools/android/jadx/bin",
            "opt/dsh-re-tools/runtime/jdk21/bin",
            "opt/dsh-re-tools/ghidra/ghidra_12.1.4_PUBLIC/support",
        )) {
            val dir = File(rootfs, path)
            dir.listFiles()?.forEach { runCatching { it.setExecutable(true, false) } }
        }
        runCatching {
            File(rootfs, "opt/dsh-re-tools/ghidra/ghidra_12.1.4_PUBLIC/Ghidra/Features/Decompiler/os/linux_arm_64/decompile")
                .setExecutable(true, false)
        }
        val profile = File(rootfs, "etc/profile.d")
        if (profile.isDirectory || profile.mkdirs()) {
            File(profile, "60-ghidra.sh").writeText(
                "export GHIDRA_INSTALL_DIR=$GHIDRA\n" +
                    "export JAVA_HOME=$JAVA_HOME\n" +
                    "export PATH=\"/opt/dsh-re-tools/bin:\$JAVA_HOME/bin:\$GHIDRA_INSTALL_DIR/support:\$PATH\"\n",
            )
        }
    }
}
