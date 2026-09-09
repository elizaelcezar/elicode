package com.elicode.app

import android.app.Application
import com.elicode.app.agent.OpenCodeEngine
import com.elicode.app.buildsys.BuildEngine
import com.elicode.app.core.ProjectManager
import com.elicode.app.core.logs.LogStore
import com.elicode.app.core.prefs.AppPrefs
import com.elicode.app.core.security.KeystoreStore
import com.elicode.app.git.GitEngine
import com.elicode.app.github.GitHubApi
import com.elicode.app.preview.PreviewEngine
import com.elicode.app.runtime.RuntimeManager
import com.elicode.app.runtime.SetupOrchestrator
import com.elicode.app.ui.SessionState
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/** Application + object graph (no DI framework needed at this size). */
class EliCodeApp : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        installCrashHandler(File(filesDir, "elicode/logs"))
        graph = AppGraph(this)
    }

    companion object {
        const val CRASH_FILE = "crash.log"
        const val MAX_CRASH_BYTES = 200_000L

        /**
         * Persists uncaught exceptions (e.g. Compose crashes) to
         * [logsDir]/crash.log so Diagnostics can show + copy them on the
         * next launch. Pure-Java report (no android.util) for JVM tests.
         */
        fun installCrashHandler(
            logsDir: File,
            prev: Thread.UncaughtExceptionHandler? = Thread.getDefaultUncaughtExceptionHandler()
        ) {
            Thread.setDefaultUncaughtExceptionHandler { t, e ->
                runCatching {
                    logsDir.mkdirs()
                    val f = File(logsDir, CRASH_FILE)
                    if (f.length() > MAX_CRASH_BYTES) f.delete()
                    val sw = StringWriter()
                    e.printStackTrace(PrintWriter(sw))
                    val causes = generateSequence(e.cause) { it.cause }.toList()
                        .joinToString("\nCaused by: ") { it.toString() }
                    f.appendText(
                        "=== ${java.util.Date()} thread=${t.name} ===\n" +
                            e.toString() + "\n" +
                            (if (causes.isNotBlank()) "Caused by: $causes\n" else "") +
                            sw.toString() + "\n"
                    )
                }
                prev?.uncaughtException(t, e)
            }
        }

        fun readCrash(logsDir: File): String =
            runCatching { File(logsDir, CRASH_FILE).takeIf { it.isFile }?.readText().orEmpty() }
                .getOrDefault("")

        fun clearCrash(logsDir: File) {
            runCatching { File(logsDir, CRASH_FILE).delete() }
        }
    }
}

class AppGraph(val app: EliCodeApp) {
    val prefs = AppPrefs(app)
    val logs = LogStore(app)
    val secrets = KeystoreStore(app)
    val session = SessionState()
    // Unified log: installer + setup funnel everything into LogStore, so
    // Diagnostics → Copiar diagnóstico always carries the full story.
    val runtime = RuntimeManager(app) { c, t, m -> logs.add(c, t, m) }
    val projects = ProjectManager(app)
    val git = GitEngine(runtime)
    val agent = OpenCodeEngine(runtime)
    val build = BuildEngine(app, runtime)
    val preview = PreviewEngine(runtime)
    val github = GitHubApi()
    val setup = SetupOrchestrator(app, runtime, agent) { c, t, m -> logs.add(c, t, m) }
}
