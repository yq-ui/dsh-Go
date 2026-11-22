package io.github.ccch1mneyyy.dsh_app.runtime

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream

/**
 * Content baked into the APK and written into the engine rootfs before DSH starts: every skill,
 * this App's native plugins and the portable Cordis plugins. Runs once per content version; a
 * user's own files always win over the baked ones.
 */
internal object BuiltinContent {
    private const val ASSET = "flutter_assets/assets/builtin/builtin.zip"
    private const val VERSION = 9
    private const val MAX_TOTAL = 1024L * 1024 * 1024
    private const val MAX_ENTRIES = 40_000
    // Entry names are not restricted to ASCII: skills ship Chinese file names (拆文库、类型卡…).
    // Safety comes from the canonical-path check below, not from a character whitelist.
    private val UNSAFE = Regex("(^/)|(^[A-Za-z]:)|(\\.\\.)")

    /** Returns a one-line summary when something was written, null when already current. */
    fun install(context: Context, rootfs: File): String? {
        // A fresh rootfs has no $DSH_HOME yet; DSH creates it on first boot. Create it here instead of
        // bailing out, otherwise the first launch installs nothing and the next one never retries.
        val home = File(rootfs, "root/.dsh")
        if (!home.isDirectory && !home.mkdirs() && !home.isDirectory) {
            throw IOException("cannot create the DSH home")
        }
        val skills = File(home, "skills")
        val stamp = File(home, ".dsh-go-builtin.json")
        if (current(stamp) && (skills.list()?.size ?: 0) >= 100) return null

        val staging = File(home, ".dsh-go-builtin-staging")
        staging.deleteRecursively()
        if (!staging.mkdirs() && !staging.isDirectory) throw IOException("cannot stage builtin content")
        try {
            extract(context, staging)
            val manifest = JSONObject(File(staging, "manifest.json").readText())
            var skillCount = 0
            for (name in strings(manifest.optJSONArray("skills"))) {
                if (applyDir(File(staging, "skills/$name"), File(skills, name), managed = false)) skillCount++
            }
            val pluginRoot = File(rootfs, "opt/dsh-app/builtin/plugins")
            val profile = File(home, "profiles/web")
            val modules = File(profile, "node_modules")
            val entries = ArrayList<Pair<String, String>>()
            var nativeCount = 0
            for (name in strings(manifest.optJSONArray("native"))) {
                val source = File(staging, "native/$name")
                if (applyDir(source, File(home, "dsh-go/plugins/$name"), managed = true)) nativeCount++
                // A bundle resolves from the profile node_modules, like a real dsh plugin add.
                if (profile.isDirectory) applyDir(source, File(modules, name), managed = true)
                entries += name to "/root/.dsh/dsh-go/plugins/" + name
            }
            var docCount = 0
            val docs = File(home, "dsh-app/builtin-docs")
            for (name in strings(manifest.optJSONArray("docs"))) {
                val source = File(staging, "docs/$name")
                if (!source.isFile) continue
                if (!docs.isDirectory && !docs.mkdirs() && !docs.isDirectory) continue
                Files.copy(source.toPath(), File(docs, name).toPath(), StandardCopyOption.REPLACE_EXISTING)
                docCount++
            }
            var treeCount = 0
            val trees = manifest.optJSONArray("trees") ?: JSONArray()
            for (i in 0 until trees.length()) {
                val entry = trees.optJSONObject(i) ?: continue
                val from = entry.optString("from").takeIf { it.isNotEmpty() } ?: continue
                val to = entry.optString("to").takeIf { it.isNotEmpty() } ?: continue
                if (to.contains("..")) continue
                val source = File(staging, from)
                if (!source.isDirectory) continue
                applyDir(source, File(rootfs, to), managed = true)
                treeCount++
            }
            // Command wrappers land on the guest PATH; the profile script exports the tools' home.
            val bins = manifest.optJSONArray("bins") ?: JSONArray()
            for (i in 0 until bins.length()) {
                val entry = bins.optJSONObject(i) ?: continue
                val from = entry.optString("from").takeIf { it.isNotEmpty() } ?: continue
                val to = entry.optString("to").takeIf { it.isNotEmpty() } ?: continue
                if (from.contains("..") || to.contains("..")) continue
                val source = File(rootfs, from)
                if (!source.isFile) continue
                val target = File(rootfs, to)
                target.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
                Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                target.setExecutable(true, false)
            }
            val env = manifest.optJSONObject("env")
            if (env != null) {
                val script = StringBuilder("# dsh-go builtin tools; rewritten on install.\n")
                for (key in env.keys()) {
                    val value = env.optString(key)
                    if (key.isEmpty() || value.contains("\n")) continue
                    script.append("export ").append(key).append("=").append(value).append('\n')
                }
                val profileDir = File(rootfs, "etc/profile.d")
                if (profileDir.isDirectory || profileDir.mkdirs()) {
                    File(profileDir, "50-dsh-go-tools.sh").writeText(script.toString())
                }
            }
            // Zip entries carry no mode bits, so native helpers and CLIs are made runnable explicitly.
            for (path in strings(manifest.optJSONArray("exec"))) {
                if (path.contains("..") || path.startsWith("/")) continue
                runCatching { File(rootfs, path).setExecutable(true, false) }
            }
            val cordis = manifest.optJSONArray("cordis") ?: JSONArray()
            val packages = ArrayList<String>()
            for (i in 0 until cordis.length()) {
                val entry = cordis.optJSONObject(i) ?: continue
                val name = entry.optString("name").takeIf { it.isNotEmpty() } ?: continue
                val dir = entry.optString("dir").takeIf { it.isNotEmpty() } ?: continue
                val source = File(staging, "cordis/$dir")
                if (!source.isDirectory) continue
                applyDir(source, File(pluginRoot, dir), managed = true)
                if (profile.isDirectory) applyDir(source, File(modules, name), managed = true)
                packages += name
                entries += name to "/opt/dsh-app/builtin/plugins/" + dir
            }
            var registered = 0
            if (entries.isNotEmpty()) registered = register(profile, entries)
            // The profile package.json only exists after DSH has booted once; keep the stamp "pending"
            // so the next launch registers the plugins instead of trusting a half-finished install.
            val pending = entries.isNotEmpty() && !File(profile, "package.json").isFile
            File(home, ".dsh-go-builtin.json").writeText(
                JSONObject().put("v", VERSION).put("skills", manifest.optJSONArray("skills")?.length() ?: 0)
                    .put("native", nativeCount).put("docs", docCount).put("trees", treeCount)
                    .put("cordis", packages.size).put("bundles", registered)
                    .put("profile", if (pending) "pending" else "done").toString(),
            )
            runCatching {
                val report = File(home, "dsh-app/builtin-report.txt")
                report.parentFile?.mkdirs()
                report.writeText(
                    "v=" + VERSION +
                        "\nskills=" + (manifest.optJSONArray("skills")?.length() ?: 0) +
                        "\nskillsWritten=" + skillCount +
                        "\nnative=" + nativeNames.size +
                        "\nnativeWritten=" + nativeCount +
                        "\ncordis=" + packages.size +
                        "\nbundles=" + registered +
                        "\nprofile=" + (if (pending) "pending" else "done") +
                        "\nprofileJson=" + File(profile, "package.json").isFile + "\n",
                )
            }
            return "skills " + skillCount + ", native " + nativeCount + ", docs " + docCount +
                ", trees " + treeCount + ", cordis " + packages.size + ", bundles " + registered
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun current(stamp: File): Boolean = try {
        val state = JSONObject(stamp.readText())
        state.optInt("v") >= VERSION && state.optString("profile") != "pending"
    } catch (_: Exception) {
        false
    }

    private fun strings(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val out = ArrayList<String>(array.length())
        for (i in 0 until array.length()) array.optString(i).takeIf { it.isNotEmpty() }?.let(out::add)
        return out
    }

    private fun extract(context: Context, staging: File) {
        val root = staging.canonicalPath
        var total = 0L
        var entries = 0
        context.assets.open(ASSET).use { stream ->
            ZipInputStream(stream.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (++entries > MAX_ENTRIES) throw IOException("builtin content has too many entries")
                    val name = entry.name.replace('\\', '/')
                    val target = File(staging, name)
                    val canonical = target.canonicalPath
                    val inside = canonical == root || canonical.startsWith(root + File.separator)
                    if (UNSAFE.containsMatchIn(name) || !inside) throw IOException("unsafe builtin entry")
                    if (entry.isDirectory) {
                        if (!target.isDirectory && !target.mkdirs()) throw IOException("cannot create builtin directory")
                        continue
                    }
                    val parent = target.parentFile
                    if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
                        throw IOException("cannot create builtin directory")
                    }
                    FileOutputStream(target).use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_TOTAL) throw IOException("builtin content is too large")
                            out.write(buffer, 0, read)
                        }
                        out.fd.sync()
                    }
                    zip.closeEntry()
                }
            }
        }
    }

    /**
     * Copies [source] over [target]. Skills are installed once and then left to the user; the
     * App's plugins are replaced whenever this version did not write them.
     */
    private fun applyDir(source: File, target: File, managed: Boolean): Boolean {
        if (!source.isDirectory) return false
        val marker = File(target, ".dsh-go-builtin-" + VERSION)
        if (target.isDirectory) {
            if (!managed && (target.list()?.isNotEmpty() == true)) return false
            if (managed && marker.isFile) return false
        }
        target.deleteRecursively()
        if (!target.isDirectory && !target.mkdirs() && !target.isDirectory) {
            throw IOException("cannot create " + target.name)
        }
        copy(source, target)
        runCatching { marker.writeText(VERSION.toString() + "\n") }
        return true
    }

    private fun copy(source: File, target: File) {
        val children = source.listFiles() ?: return
        for (child in children) {
            val next = File(target, child.name)
            if (child.isDirectory) {
                if (!next.isDirectory && !next.mkdirs() && !next.isDirectory) {
                    throw IOException("cannot create directory")
                }
                copy(child, next)
            } else {
                Files.copy(child.toPath(), next.toPath(), StandardCopyOption.REPLACE_EXISTING)
                // Zip extraction and Files.copy both drop the mode bit; a bundled CLI must stay runnable.
                if (child.canExecute()) next.setExecutable(true, false)
            }
        }
    }

    /**
     * A plugin shows up under Settings > Plugins because the profile DEPENDS on it: the bundle list
     * only decides what loads. Both are written here, with a link: dependency pointing at the
     * baked directory, which is what a local plugin install looks like.
     */
    private fun register(profile: File, entries: List<Pair<String, String>>): Int {
        val file = File(profile, "package.json")
        if (!file.isFile) return 0
        return try {
            val root = JSONObject(file.readText())
            val dependencies = root.optJSONObject("dependencies") ?: JSONObject().also { root.put("dependencies", it) }
            val dsh = root.optJSONObject("dsh") ?: JSONObject().also { root.put("dsh", it) }
            val section = dsh.optJSONObject("profile") ?: JSONObject().also { dsh.put("profile", it) }
            val bundles = section.optJSONArray("bundles") ?: JSONArray().also { section.put("bundles", it) }
            val have = HashSet<String>()
            for (i in 0 until bundles.length()) bundles.optString(i).takeIf { it.isNotEmpty() }?.let(have::add)
            var added = 0
            for ((name, guestPath) in entries) {
                if (!dependencies.has(name)) {
                    dependencies.put(name, "link:" + guestPath)
                    added++
                }
                if (!have.contains(name)) {
                    bundles.put(name)
                    have += name
                    added++
                }
            }
            if (added == 0) return 0
            val tmp = File(profile, ".package.json.dsh-go-builtin")
            tmp.writeText(root.toString(1))
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            added
        } catch (_: Exception) {
            0
        }
    }
}
