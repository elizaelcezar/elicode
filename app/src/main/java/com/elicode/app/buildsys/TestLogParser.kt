package com.elicode.app.buildsys

/**
 * Parses Gradle test output into a short summary the user can paste
 * back to the assistant when something fails. Pure logic — unit-tested.
 */
object TestLogParser {

    data class Summary(
        val total: Int,
        val failed: Int,
        val buildOk: Boolean,
        val failedTasks: List<String>
    ) {
        fun headline(): String = when {
            total == 0 && !buildOk -> "No tests ran — build failed."
            failed == 0 && buildOk -> "$total tests passed ✓"
            else -> "$total tests, $failed failed."
        }
    }

    private val testsLine = Regex("""(\d+)\s+tests?\s+completed(?:,\s*(\d+)\s+failed)?""")
    private val failedTask = Regex("""^>\s*Task\s+(\S+)\s+FAILED""", RegexOption.MULTILINE)

    fun summarize(output: String): Summary {
        var total = 0
        var failed = 0
        for (m in testsLine.findAll(output)) {
            total += m.groupValues[1].toIntOrNull() ?: 0
            failed += m.groupValues[2].toIntOrNull() ?: 0
        }
        // Fallback: lone "N failed" lines without the "completed" prefix.
        if (total == 0) {
            val lone = Regex("""(\d+)\s+failed""").find(output)
            failed = lone?.groupValues?.get(1)?.toIntOrNull() ?: 0
        }
        val tasks = failedTask.findAll(output).map { it.groupValues[1] }.distinct().toList()
        return Summary(total, failed, "BUILD SUCCESSFUL" in output, tasks)
    }

    /** Full text placed on the clipboard for bug reports. */
    fun report(projectName: String, summary: Summary, log: String): String = buildString {
        appendLine("EliCode test run — $projectName")
        appendLine("Result: ${summary.headline()}")
        if (summary.failedTasks.isNotEmpty()) {
            appendLine("Failed tasks:")
            summary.failedTasks.forEach { appendLine("  - $it") }
        }
        appendLine("----- full log -----")
        append(log.takeLast(20000))
    }
}
