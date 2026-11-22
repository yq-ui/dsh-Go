package io.github.ccch1mneyyy.dsh_app.phone.shell

/**
 * Hard, non-editable protection for the Android host: the agent may read anything it can reach,
 * but a command that would delete, overwrite or remount the operating system itself is refused
 * before it runs. Deliberately not a user setting: "always allow" and the confirmation mode do
 * not lift it.
 */
internal object SystemGuard {
    private data class Rule(val name: String, val pattern: Regex)

    private val RULES = listOf(
        Rule("format", Regex("\\b(mkfs(\\.\\w+)?|mke2fs|fdisk|sgdisk|wipefs)\\b")),
        Rule("blockdev", Regex("\\bdd\\b[^|;&]*\\bof=/dev/(block|mmcblk|sda|sdb|dtbo|vbmeta)")),
        Rule("remount", Regex("\\bmount\\b[^|;&]*remount[^|;&]*\\s/(system|system_ext|vendor|product|odm|apex)\\b")),
        Rule("umount", Regex("\\bumount\\s+/(system|system_ext|vendor|product|odm|apex)\\b")),
        Rule("pm", Regex("\\bpm\\s+(uninstall|disable-user|disable)\\s+(--user\\s+\\d+\\s+)?(android|com\\.android|com\\.google)")),
        Rule("settings", Regex("\\bsettings\\s+(put|delete)\\s+(global|secure|system)\\b")),
        Rule("selinux", Regex("\\b(setenforce|supolicy)\\b")),
        Rule("install-system", Regex("\\binstall\\s+-m\\s+[^ ]+\\s+/(system|system_ext|vendor|product|odm)\\b")),
    )

    private val PROTECTED = listOf(
        "/system", "/system_ext", "/vendor", "/product", "/odm", "/apex", "/oem",
        "/boot", "/init", "/init.rc", "/fstab", "/metadata",
        "/data/adb", "/data/system", "/data/misc", "/data/vendor",
        "/dev/block", "/proc/sys", "/sys",
    )

    private val WRITE = listOf("rm", "mv", "cp", "chmod", "chown", "truncate", "tee", ">", ">>", "sed -i", "ln -s")

    /** null when the command is acceptable; otherwise the refusal code reported to the tool. */
    fun rejection(command: String): String? {
        val text = command.replace('\\', '/').lowercase()
        for (rule in RULES) if (rule.pattern.containsMatchIn(text)) return "systemProtected"
        for (path in PROTECTED) {
            if (!text.contains(path)) continue
            for (verb in WRITE) {
                val pattern = Regex(Regex.escape(verb) + "[^|;&]*" + Regex.escape(path) + "(\\b|/)")
                if (pattern.containsMatchIn(text)) return "systemProtected"
            }
        }
        return null
    }
}
