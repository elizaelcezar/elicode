package com.elicode.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.elicode.app.AppGraph
import com.elicode.app.core.FileManager
import com.elicode.app.ui.Routes
import com.elicode.app.ui.components.CodeEditor
import com.elicode.app.ui.components.EmptyState
import com.elicode.app.ui.components.FileTree
import java.io.File

/**
 * Project detail: file tree + editor. Detail screens (AI agent, APK
 * build, Git, live Preview) are one tap away in the action row —
 * they are not bottom tabs (product premise: 4 tabs only).
 */
@Composable
fun EditorScreen(graph: AppGraph, onNavigate: (String) -> Unit) {
    val session = graph.session
    val project = session.project
    val context = LocalContext.current
    if (project == null) {
        EmptyState("No project open.", actionLabel = "Go to Projects", onAction = {})
        return
    }
    val root = remember(project.path, session.treeNonce) { File(project.path) }
    var saveNonce by remember { mutableStateOf(0) }
    val modified = remember { mutableStateMapOf<String, Boolean>() }
    var showTree by remember { mutableStateOf(true) }

    Column(Modifier.fillMaxSize()) {
        // Project actions (detail screens, not tabs).
        androidx.compose.foundation.lazy.LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)
        ) {
            val actions = listOf(
                "🤖 AI" to Routes.AGENT,
                "📦 Build APK" to Routes.BUILD,
                "⎇ Git" to Routes.GIT,
                "👁 Preview" to Routes.PREVIEW
            )
            items(actions.size) { i ->
                val (label, route) = actions[i]
                androidx.compose.material3.OutlinedButton(onClick = { onNavigate(route) }) {
                    Text(label, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Divider()
        // Open file tabs.
        androidx.compose.foundation.lazy.LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
        ) {
            items(session.openFiles.size) { i ->
                val rel = session.openFiles[i]
                AssistChip(
                    onClick = { session.activeFile = rel },
                    label = { Text((if (modified[rel] == true) "• " else "") + File(rel).name) },
                    trailingIcon = {
                        IconButton(onClick = { session.closeFile(rel); modified.remove(rel) }) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    },
                    modifier = Modifier.padding(end = 4.dp)
                )
            }
        }
        Divider()
        Row(Modifier.weight(1f)) {
            if (showTree) {
                FileTree(
                    root = root,
                    nonce = session.treeNonce,
                    onOpenFile = { rel ->
                        val f = runCatching { FileManager.resolveSafe(root, rel) }.getOrNull()
                        if (f != null && f.isFile) session.openFile(rel)
                    },
                    onChanged = { session.refreshTree() },
                    modifier = Modifier.width(190.dp).fillMaxHeight()
                )
                Divider(Modifier.fillMaxHeight().width(1.dp))
            }
            Column(Modifier.weight(1f)) {
                val active = session.activeFile
                if (active == null) {
                    EmptyState(
                        "Select a file in the tree.",
                        actionLabel = if (showTree) "Hide files" else "Show files",
                        onAction = { showTree = !showTree }
                    )
                } else {
                    val f = runCatching { FileManager.resolveSafe(root, active) }.getOrNull()
                    if (f == null || !f.isFile) {
                        EmptyState("File no longer exists: $active")
                    } else {
                        Row {
                            Text(
                                active,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.weight(1f).padding(8.dp),
                                maxLines = 1
                            )
                            IconButton(onClick = { saveNonce++ }) {
                                Icon(Icons.Default.Save, contentDescription = "Save")
                            }
                            IconButton(onClick = { showTree = !showTree }) {
                                Text(if (showTree) "«" else "»")
                            }
                        }
                        CodeEditor(
                            file = f,
                            fontSizeSp = graph.prefs.editorFontSize,
                            onModifiedChange = { modified[active] = it },
                            onSaved = {
                                modified[active] = false
                                graph.logs.add("Terminal", "editor", "Saved $active")
                            },
                            saveRequestNonce = saveNonce
                        )
                    }
                }
            }
        }
    }
}
