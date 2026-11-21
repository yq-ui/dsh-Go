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
    private const val VERSION = 1
    private const val MAX_TOTAL = 64L * 1024 * 1024
    private const val MAX_ENTRIES = 40_000
    private val SAFE = Regex("^[A-Za-z0-9@._+-][A-Za-z0-9@._+/-]*$")

    /** Returns a one-line summary when something was written, null when already current. */
    fun install(context: Context, rootfs: File): String? {
        val home = File(rootfs, "root/.dsh")
        if (!home.isDirectory) return null
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
            var nativeCount = 0
            for (name in strings(manifest.optJSONArray("native"))) {
                if (applyDir(File(staging, "native/$name"), File(home, "dsh-go/plugins/$name"), managed = true)) nativeCount++
            }
            val cordis = manifest.optJSONArray("cordis") ?: JSONArray()
            val profile = File(home, "profiles/web")
            val modules = File(profile, "node_modules")
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
            }
            var registered = 0
            if (packages.isNotEmpty()) registered = register(profile, packages)
            File(home, ".dsh-go-builtin.json").writeText(
                JSONObject().put("v", VERSION).put("skills", manifest.optJSONArray("skills")?.length() ?: 0)
                    .put("native", nativeCount).put("cordis", packages.size).put("bundles", registered).toString(),
            )
            return "skills " + skillCount + ", native " + nativeCount + ", cordis " + packages.size +
                ", bundles " + registered
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun current(stamp: File): Boolean = try {
        stamp.isFile && JSONObject(stamp.readText()).optInt("v") >= VERSION
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
                    val name = entry.name
                    val target = File(staging, name)
                    if (!SAFE.matches(name) || name.contains("..") || !target.canonicalPath.startsWith(root)) {
                        throw IOException("unsafe builtin entry")
                    }
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
            }
        }
    }

    /** Adds the baked bundles to the web profile bundle list, keeping the user's order. */
    private fun register(profile: File, packages: List<String>): Int {
        val file = File(profile, "package.json")
        if (!file.isFile) return 0
        return try {
            val root = JSONObject(file.readText())
            val dsh = root.optJSONObject("dsh") ?: JSONObject().also { root.put("dsh", it) }
            val section = dsh.optJSONObject("profile") ?: JSONObject().also { dsh.put("profile", it) }
            val bundles = section.optJSONArray("bundles") ?: JSONArray().also { section.put("bundles", it) }
            val have = HashSet<String>()
            for (i in 0 until bundles.length()) bundles.optString(i).takeIf { it.isNotEmpty() }?.let(have::add)
            var added = 0
            for (name in packages) {
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
