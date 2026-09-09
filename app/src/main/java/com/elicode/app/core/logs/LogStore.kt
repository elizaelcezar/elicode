package com.elicode.app.core.logs

import android.content.Context
import com.elicode.app.core.LogEntry
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory ring buffer of log entries, persisted to a JSONL file so
 * Diagnostics can show history across restarts.
 */
class LogStore(context: Context) {

    private val file = File(context.filesDir, "elicode/logs/elic.log").apply {
        parentFile?.mkdirs()
    }
    private val gson = Gson()
    private val buffer = CopyOnWriteArrayList<LogEntry>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    var maxEntries = 500
        private set

    init {
        runCatching {
            if (file.isFile) {
                file.readLines().takeLast(300).forEach { line ->
                    runCatching {
                        gson.fromJson(line, LogEntry::class.java)?.let { buffer += it }
                    }
                }
            }
        }
    }

    fun add(category: String, tag: String, message: String) {
        val e = LogEntry(category = category, tag = tag, message = message.take(4000))
        buffer += e
        while (buffer.size > maxEntries) buffer.removeAt(0)
        runCatching { file.appendText(gson.toJson(e) + "\n") }
        listeners.forEach { runCatching { it() } }
    }

    fun snapshot(category: String? = null): List<LogEntry> =
        if (category == null) buffer.toList() else buffer.filter { it.category == category }

    fun categories(): List<String> = buffer.map { it.category }.distinct().sorted()

    fun clear() {
        buffer.clear()
        runCatching { file.writeText("") }
        listeners.forEach { runCatching { it() } }
    }

    fun export(): File = file

    fun onChange(listener: () -> Unit) {
        listeners += listener
    }

    companion object {
        const val RUNTIME = "Runtime"
        const val TERMINAL = "Terminal"
        const val AGENT = "OpenCode"
        const val GIT = "Git"
        const val BUILD = "Build"
        const val PREVIEW = "Preview"
        const val INSTALLER = "Installer"
        const val GITHUB = "GitHub"
    }
}
