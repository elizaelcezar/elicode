package com.elicode.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFolderUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.elicode.app.AppGraph
import com.elicode.app.core.EliError
import com.elicode.app.core.EliResult
import com.elicode.app.core.ProjectDetector
import com.elicode.app.core.ProjectInfo
import com.elicode.app.core.ProjectType
import com.elicode.app.core.templates.Templates
import com.elicode.app.service.EliCodeService
import com.elicode.app.ui.components.ConfirmDialog
import com.elicode.app.ui.components.EmptyState
import com.elicode.app.ui.components.ErrorCard
import com.elicode.app.ui.components.LoadingRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(graph: AppGraph, onOpenProject: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var projects by remember { mutableStateOf<List<ProjectInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var showMenu by remember { mutableStateOf(false) }
    var showNew by remember { mutableStateOf(false) }
    var showClone by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<EliError?>(null) }
    var confirmDelete by remember { mutableStateOf<ProjectInfo?>(null) }

    fun reload() {
        loading = true
        scope.launch(Dispatchers.IO) {
            val list = graph.projects.list().map { info ->
                val dir = File(info.path)
                if (dir.isDirectory) {
                    val det = ProjectDetector.detect(dir)
                    info.copy(
                        type = if (info.type == ProjectType.UNKNOWN) det.type else info.type,
                        branch = graph.projects.readGitBranch(dir).ifBlank { info.branch },
                        gitRemote = graph.projects.readGitRemote(dir).ifBlank { info.gitRemote }
                    )
                } else info
            }
            withContext(Dispatchers.Main) {
                projects = list
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { reload() }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Throwable) {
            }
            val result = importTree(context, uri, graph)
            withContext(Dispatchers.Main) {
                when (result) {
                    is EliResult.Ok -> {
                        graph.session.openProject(result.value)
                        graph.prefs.currentProjectPath = result.value.path
                        onOpenProject()
                    }
                    is EliResult.Err -> error = result.error
                }
                reload()
            }
        }
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showMenu = true }) {
                Icon(Icons.Default.Add, contentDescription = "New")
            }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(text = { Text("New project") }, onClick = { showMenu = false; showNew = true })
                DropdownMenuItem(text = { Text("Clone from GitHub / URL") }, onClick = { showMenu = false; showClone = true })
                DropdownMenuItem(text = { Text("Import folder") }, onClick = { showMenu = false; importLauncher.launch(null) })
            }
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(12.dp)) {
            Text("Projects", style = MaterialTheme.typography.headlineSmall)
            Text(
                if (graph.runtime.isInstalled()) "Ubuntu runtime ✓" else "Runtime missing — install in Settings",
                style = MaterialTheme.typography.labelMedium,
                color = if (graph.runtime.isInstalled()) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.error
            )
            error?.let { ErrorCard(it, onDismiss = { error = null }) }
            if (loading) {
                LoadingRow("Loading projects…")
            } else if (projects.isEmpty()) {
                EmptyState("No projects yet. Create one to start coding on your phone.")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(projects, key = { it.id }) { p ->
                        ProjectCard(
                            info = p,
                            onOpen = {
                                val updated = graph.projects.touchOpened(p)
                                graph.prefs.currentProjectPath = updated.path
                                graph.session.openProject(updated)
                                graph.logs.add("Terminal", "projects", "Opened ${updated.path}")
                                onOpenProject()
                            },
                            onDelete = { confirmDelete = p }
                        )
                    }
                }
            }
        }
    }

    if (showNew) {
        NewProjectDialog(
            onCancel = { showNew = false },
            onCreate = { name, templateId, pkg ->
                showNew = false
                scope.launch(Dispatchers.IO) {
                    val tpl = Templates.byId(templateId)
                    val info = graph.projects.create(name, tpl.type, tpl.files(name, pkg))
                    graph.prefs.currentProjectPath = info.path
                    withContext(Dispatchers.Main) {
                        graph.session.openProject(info)
                        reload()
                        onOpenProject()
                    }
                }
            }
        )
    }
    if (showClone) {
        CloneDialog(
            graph = graph,
            onCancel = { showClone = false },
            onCloned = { info ->
                showClone = false
                graph.session.openProject(info)
                graph.prefs.currentProjectPath = info.path
                reload()
                onOpenProject()
            },
            onError = { showClone = false; error = it }
        )
    }
    confirmDelete?.let { p ->
        var deleteFiles by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete project?") },
            text = {
                Column {
                    Text(p.name)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(deleteFiles, { deleteFiles = it })
                        Text("Also delete files from storage")
                    }
                    if (!deleteFiles) Text("Only removes it from the list.")
                }
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch(Dispatchers.IO) {
                        graph.projects.delete(p, deleteFiles)
                        if (graph.session.project?.id == p.id) {
                            withContext(Dispatchers.Main) { graph.session.closeProject() }
                        }
                        withContext(Dispatchers.Main) { confirmDelete = null; reload() }
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun ProjectCard(info: ProjectInfo, onOpen: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        ListItem(
            headlineContent = { Text(info.name) },
            supportingContent = {
                Column {
                    Text(info.path, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Badge { Text(info.type.label) }
                        if (info.branch.isNotBlank()) Badge { Text("⎇ ${info.branch}") }
                    }
                    if (info.lastOpened > 0) {
                        Text(
                            "Opened " + SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
                                .format(Date(info.lastOpened)),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            },
            trailingContent = {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                }
            }
        )
    }
}

@Composable
private fun NewProjectDialog(
    onCancel: () -> Unit,
    onCreate: (name: String, templateId: String, pkg: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var pkg by remember { mutableStateOf("com.example.app") }
    var templateId by remember { mutableStateOf(Templates.all.first().id) }
    var expanded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("New project") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(Templates.byId(templateId).label)
                }
                DropdownMenu(expanded, { expanded = false }) {
                    Templates.all.forEach { t ->
                        DropdownMenuItem(
                            text = { Text("${t.label}\n${t.description}") },
                            onClick = { templateId = t.id; expanded = false }
                        )
                    }
                }
                if (templateId.startsWith("android")) {
                    OutlinedTextField(pkg, { pkg = it }, label = { Text("Package") }, singleLine = true)
                }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(onClick = {
                if (name.isBlank()) error = "Give the project a name."
                else onCreate(name.trim(), templateId, pkg.trim())
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}

@Composable
private fun CloneDialog(
    graph: AppGraph,
    onCancel: () -> Unit,
    onCloned: (ProjectInfo) -> Unit,
    onError: (EliError) -> Unit
) {
    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        title = { Text("Clone repository") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(url, { url = it }, label = { Text("https://github.com/user/repo.git") }, singleLine = true)
                if (log.isNotBlank()) Text(log, style = MaterialTheme.typography.bodySmall)
                if (busy) LoadingRow("Cloning… (stays alive in background)")
            }
        },
        confirmButton = {
            Button(onClick = {
                if (url.isBlank()) return@Button
                busy = true
                log = ""
                EliCodeService.taskStarted(context, "git-clone", "Cloning repository")
                scope.launch(Dispatchers.IO) {
                    val token = graph.secrets.get(com.elicode.app.core.security.KeystoreStore.GITHUB_TOKEN)
                    val name = url.trim().substringAfterLast('/').removeSuffix(".git").ifBlank { "repo" }
                    val res = graph.git.clone(url.trim(), graph.projects.projectsRoot(), name, token)
                    withContext(Dispatchers.Main) {
                        EliCodeService.taskFinished(context, "git-clone")
                        busy = false
                        when (res) {
                            is EliResult.Ok -> {
                                graph.logs.add("Git", "clone", "Cloned $url")
                                val dir = res.value
                                val info = graph.projects.registerCloned(
                                    dir,
                                    remote = url.trim(),
                                    branch = graph.projects.readGitBranch(dir)
                                )
                                onCloned(info)
                            }
                            is EliResult.Err -> {
                                graph.logs.add("Git", "clone", "FAILED: ${res.error.format()}")
                                onError(res.error)
                            }
                        }
                    }
                }
            }, enabled = !busy) { Text("Clone") }
        },
        dismissButton = { TextButton(onClick = { if (!busy) onCancel() }) { Text("Cancel") } }
    )
}

/** Copies a SAF tree into EliCode projects (real import, keeps nothing fake). */
private fun importTree(
    context: android.content.Context,
    uri: Uri,
    graph: AppGraph
): EliResult<ProjectInfo> {
    return try {
        val doc = DocumentFile.fromTreeUri(context, uri)
            ?: return EliResult.Err(EliError("Import folder", message = "Cannot read the selected folder."))
        val name = doc.name?.ifBlank { "imported" } ?: "imported"
        val dest = File(graph.projects.projectsRoot(), com.elicode.app.core.ProjectManager.slugify(name))
            .let { base ->
                var d = base
                var n = 1
                while (d.exists()) d = File(base.parent, "${base.name}-${++n}")
                d
            }
        dest.mkdirs()
        var count = 0
        fun copyDir(src: DocumentFile, dst: File) {
            src.listFiles().forEach { child ->
                val childName = child.name ?: return@forEach
                if (child.isDirectory) {
                    val sub = File(dst, childName).apply { mkdirs() }
                    copyDir(child, sub)
                } else if (child.isFile) {
                    // Skip giant binaries on import.
                    if (child.length() in 0..50_000_000) {
                        context.contentResolver.openInputStream(child.uri)?.use { input ->
                            File(dst, childName).outputStream().use { input.copyTo(it) }
                            count++
                        }
                    }
                }
                if (count > 5000) throw IllegalStateException("Too many files (>5000); import a smaller folder.")
            }
        }
        copyDir(doc, dest)
        EliResult.Ok(graph.projects.importExternal(dest.name, dest))
    } catch (t: Throwable) {
        EliResult.Err(EliError.unknown("Import folder", t))
    }
}
