package io.github.ccch1mneyyy.dsh_app.phone.shell

/** Builds the fixed privileged app_process invocation; input never becomes an extra argv element. */
internal object RootShellCommand {
    fun appProcess(classpath: String, mainClass: String): List<String>? {
        val paths = classpath.split(':')
        if (classpath.isBlank() || paths.any { it.isBlank() || !it.startsWith('/') || it.any { ch -> ch.code < 32 || ch.code == 127 } } ||
            !mainClass.matches(Regex("""[A-Za-z_][A-Za-z0-9_.]*"""))) return null
        return listOf(
            "su", "-c",
            "CLASSPATH=${quote(classpath)} exec /system/bin/app_process /system/bin $mainClass",
        )
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
