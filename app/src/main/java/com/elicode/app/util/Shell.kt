package com.elicode.app.util

/** Shell helpers: quoting, destructive-command detection, port parsing. */
object Shell {

    /** Quote one argv element for POSIX sh. */
    fun quote(arg: String): String =
        "'" + arg.replace("'", "'\"'\"'") + "'"

    fun join(cmd: List<String>): String = cmd.joinToString(" ") { quote(it) }

    private val destructive = listOf(
        // rm with a recursive flag targeting filesystem root: `rm -rf /`
        // (trailing args like --no-preserve-root don't make it safe).
        Regex("""^\s*rm\b(?=.*\s-[a-zA-Z]*r)(?=.*(?:^|\s)/(?:\s|$)).*"""),
        Regex("""(^|[;&|])\s*mkfs\b"""),
        Regex("""(^|[;&|])\s*:\(\)\s*\{\s*:\|\:&\s*\}\s*;?\s*:.*"""), // fork bomb
        Regex("""(^|[;&|])\s*dd\s+.*of=/dev/"""),
        Regex("""(^|[;&|])\s*chmod\s+-R\s+777\s+/\s*$""")
    )

    /** Heuristic guard: commands that would wipe the device/rootfs need confirmation. */
    fun isDestructive(command: String): Boolean = destructive.any { it.containsMatchIn(command) }
}

/** Parses ports from server output (e.g. "Listening on :3000", "port 5173"). */
object Ports {
    private val patterns = listOf(
        Regex("""(?i)\blocalhost:(\d{2,5})"""),
        Regex("""(?i)0\.0\.0\.0:(\d{2,5})"""),
        Regex("""(?i)127\.0\.0\.1:(\d{2,5})"""),
        Regex("""(?i)\bport\s*[:=]?\s*(\d{2,5})"""),
        Regex("""(?i)listening on[^\d]*(\d{2,5})"""),
        Regex("""(?i)http://[^\s:]*:(\d{2,5})""")
    )

    fun extractPorts(text: String): List<Int> {
        val out = linkedSetOf<Int>()
        patterns.forEach { re ->
            re.findAll(text).forEach { m ->
                m.groupValues.getOrNull(1)?.toIntOrNull()?.let { p ->
                    if (p in 1..65535) out += p
                }
            }
        }
        return out.toList()
    }

    fun firstPort(text: String): Int? = extractPorts(text).firstOrNull()
}
