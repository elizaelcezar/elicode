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
import com.elicode.app.ui.SessionState

/** Application + object graph (no DI framework needed at this size). */
class EliCodeApp : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

class AppGraph(val app: EliCodeApp) {
    val prefs = AppPrefs(app)
    val logs = LogStore(app)
    val secrets = KeystoreStore(app)
    val session = SessionState()
    val runtime = RuntimeManager(app)
    val projects = ProjectManager(app)
    val git = GitEngine(runtime)
    val agent = OpenCodeEngine(runtime)
    val build = BuildEngine(app, runtime)
    val preview = PreviewEngine(runtime)
    val github = GitHubApi()
}
