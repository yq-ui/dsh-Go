package io.github.ccch1mneyyy.dsh_app.phone.shell

/** Builds the fixed privileged app_process invocation; input never becomes an extra argv element. */
internal object RootShellCommand {
    /** Root managers disagree on where su lives; try every known location once. */
    val candidates: List<String> = listOf(
        "/system/bin/su",          // KernelSU / Magisk / most ROMs
        "/debug_ramdisk/su",       // Magisk
        "/sbin/su",                // legacy Magisk
        "/su/bin/su",              // SuperSU
        "/system/xbin/su",         // older ROMs
        "/data/adb/magisk/su",     // Magisk payload
        "/data/adb/ksu/bin/su",    // KernelSU (direct)
        "su",                      // PATH
    )

    fun appProcess(classpath: String, mainClass: String, su: String = "su"): List<String>? {
        val paths = classpath.split(':')
        if (classpath.isBlank() || paths.any { it.isBlank() || !it.startsWith('/') || it.any { ch -> ch.code < 32 || ch.code == 127 } } ||
            !mainClass.matches(Regex("""[A-Za-z_][A-Za-z0-9_.]*""")) || !validSu(su)) return null
        return listOf(
            su, "-c",
            "CLASSPATH=${quote(classpath)} exec /system/bin/app_process /system/bin $mainClass",
        )
    }

    private fun validSu(su: String): Boolean =
        su == "su" || (su.startsWith('/') && su.none { it.code < 32 || it == ' ' || it == '\'' })

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
