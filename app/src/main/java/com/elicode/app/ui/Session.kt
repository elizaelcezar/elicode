package com.elicode.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.elicode.app.core.ProjectInfo
import java.io.File

/**
 * Workspace session: current project + open editor files.
 * Owned by AppGraph (application scope) so it survives rotation
 * and navigation. [treeNonce] forces file-tree refreshes
 * (e.g. after an agent run or git operation).
 */
class SessionState {
    var project: ProjectInfo? by mutableStateOf(null)
    var openFiles: List<String> by mutableStateOf(emptyList())
    var activeFile: String? by mutableStateOf(null)
    var treeNonce: Int by mutableIntStateOf(0)

    fun openProject(info: ProjectInfo) {
        project = info
        openFiles = emptyList()
        activeFile = null
        treeNonce++
    }

    fun closeProject() {
        project = null
        openFiles = emptyList()
        activeFile = null
    }

    fun openFile(relative: String) {
        if (relative !in openFiles) openFiles = openFiles + relative
        activeFile = relative
    }

    fun closeFile(relative: String) {
        openFiles = openFiles.filterNot { it == relative }
        if (activeFile == relative) activeFile = openFiles.lastOrNull()
    }

    fun refreshTree() {
        treeNonce++
    }

    fun projectDir(): File? = project?.let { File(it.path) }
}
