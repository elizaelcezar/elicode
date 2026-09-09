package com.elicode.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.elicode.app.ui.EliCodeNav
import com.elicode.app.ui.theme.EliCodeTheme
import java.io.File

class MainActivity : ComponentActivity() {

    val graph: AppGraph get() = (application as EliCodeApp).graph

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        restoreSession()
        setContent {
            EliCodeTheme(mode = graph.prefs.themeMode) {
                EliCodeNav(graph)
            }
        }
        graph.runtime.refreshStatus(probeTools = false)
    }

    override fun onResume() {
        super.onResume()
        restoreSession()
    }

    /** Reopens the last project (metadata refreshed, never stale). */
    private fun restoreSession() {
        if (graph.session.project != null) return
        val path = graph.prefs.currentProjectPath
        if (path.isBlank()) return
        val dir = File(path)
        if (!dir.isDirectory) return
        val known = runCatching {
            graph.projects.list().firstOrNull { File(it.path).canonicalPath == dir.canonicalPath }
        }.getOrNull()
        val info = known ?: runCatching {
            graph.projects.importExternal(dir.name, dir)
        }.getOrNull()
        if (info != null) graph.session.openProject(graph.projects.touchOpened(info))
    }
}
