package io.github.ccch1mneyyy.dsh_app.phone

/** Builds a non-recursive, read-only Root inspection command for fixed Android system roots. */
internal object RootDeviceInfoTool {
    private const val MAX_PATH_CHARS = 512
    private val roots = listOf("/system", "/system_ext", "/vendor", "/product", "/odm")

    data class Request(val operation: String, val path: String) {
        fun command(): String {
            val prefix = "target=\$(/system/bin/readlink -f " + quote(path) + ") || exit 1; [ -n \"\$target\" ] || exit 1; " +
                "allowed=0; for root in /system /system_ext /vendor /product /odm; do " +
                "canonical=\$(/system/bin/readlink -f \"\$root\" 2>/dev/null) || continue; " +
                "[ -n \"\$canonical\" ] && [ \"\$canonical\" != / ] || continue; " +
                "case \"\$target\" in \"\$canonical\"|\"\$canonical\"/*) allowed=1; break;; esac; " +
                "done; [ \"\$allowed\" -eq 1 ] || exit 77; "
            val action = if (operation == "list") "/system/bin/ls -la \"\$target\"" else "/system/bin/ls -ld \"\$target\""
            return prefix + action
        }
    }

    fun parse(args: Args): Request? {
        if (!args.onlyKeys("operation", "path")) return null
        val operation = args.strictText("operation") ?: return null
        if (operation !in setOf("list", "metadata")) return null
        val path = args.strictText("path") ?: return null
        if (!validPath(path)) return null
        return Request(operation, path)
    }

    fun validPath(path: String): Boolean {
        if (path.isEmpty() || path.length > MAX_PATH_CHARS || !path.startsWith('/') ||
            path.any { it.code < 0x20 || it.code == 0x7f || it == '\\' }) return false
        val components = path.split('/')
        if (components.drop(1).any { it.isEmpty() || it == "." || it == ".." }) return false
        return roots.any { path == it || path.startsWith("$it/") }
    }

    private fun quote(path: String): String = "'" + path.replace("'", "'\\''") + "'"
}
