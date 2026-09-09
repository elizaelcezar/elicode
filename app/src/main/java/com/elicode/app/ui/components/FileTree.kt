package com.elicode.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.elicode.app.core.FileManager
import java.io.File

/**
 * Project file tree: expandable dirs, tap-to-open files, long-press-free
 * management via the toolbar (new file/dir) and per-file actions dialog.
 */
@Composable
fun FileTree(
    root: File,
    nonce: Int,
    onOpenFile: (String) -> Unit,
    onChanged: () -> Unit,
    modifier: Modifier = Modifier
) {
    var tick by remember(nonce) { mutableStateOf(0) }
    LaunchedEffect(nonce) { tick++ }
    val expanded = remember(root.absolutePath) { mutableStateMapOf<String, Boolean>() }
    var showNewFile by remember { mutableStateOf(false) }
    var showNewDir by remember { mutableStateOf(false) }
    var manageTarget by remember { mutableStateOf<File?>(null) }

    // Ensure at least the root is expanded on first composition.
    LaunchedEffect(root.absolutePath, tick) {
        expanded.putIfAbsent(root.absolutePath, true)
    }

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                root.name,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f).padding(start = 4.dp)
            )
            IconButton(onClick = { showNewFile = true }) {
                Icon(Icons.Default.NoteAdd, contentDescription = "New file")
            }
            IconButton(onClick = { showNewDir = true }) {
                Icon(Icons.Default.CreateNewFolder, contentDescription = "New folder")
            }
        }
        LazyColumn(Modifier.weight(1f, fill = false)) {
            items(treeRows(root, expanded)) { row ->
                TreeRowView(
                    row = row,
                    onToggle = {
                        expanded[row.file.absolutePath] = !(expanded[row.file.absolutePath] ?: false)
                    },
                    onOpen = {
                        onOpenFile(FileManager.relativePath(root, row.file))
                    },
                    onManage = { manageTarget = row.file }
                )
            }
        }
    }

    if (showNewFile) {
        NameDialog("New file", "e.g. src/Main.kt") { name ->
            showNewFile = false
            if (name != null) {
                runCatching { FileManager.createFile(root, name) }
                onChanged()
            }
        }
    }
    if (showNewDir) {
        NameDialog("New folder", "e.g. src/assets") { name ->
            showNewDir = false
            if (name != null) {
                runCatching { FileManager.createDir(root, name) }
                onChanged()
            }
        }
    }
    manageTarget?.let { target ->
        ManageDialog(
            root = root,
            target = target,
            onClose = { manageTarget = null },
            onChanged = onChanged
        )
    }
}

private data class TreeRow(val file: File, val depth: Int, val isDir: Boolean)

private fun treeRows(root: File, expanded: Map<String, Boolean>): List<TreeRow> {
    val out = mutableListOf<TreeRow>()
    fun walk(dir: File, depth: Int) {
        FileManager.listChildren(dir).forEach { f ->
            // Hide noise.
            if (f.name == ".git" || f.name == "build" || f.name == ".gradle" || f.name == "node_modules") {
                if (depth == 0) {
                    out += TreeRow(f, depth, true)
                    return@forEach
                }
            }
            out += TreeRow(f, depth, f.isDirectory)
            val isOpen = expanded[f.absolutePath] ?: (depth < 1)
            if (f.isDirectory && isOpen) {
                walk(f, depth + 1)
            }
        }
    }
    walk(root, 0)
    return out
}

@Composable
private fun TreeRowView(
    row: TreeRow,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onManage: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (row.isDir) onToggle() else onOpen() }
            .padding(start = (4 + row.depth * 14).dp, top = 5.dp, bottom = 5.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = when {
                row.isDir -> Icons.Default.Folder
                else -> Icons.Default.Description
            },
            contentDescription = null,
            tint = if (row.isDir) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
        androidx.compose.foundation.layout.Spacer(Modifier.padding(3.dp))
        Text(
            row.file.name,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            maxLines = 1
        )
        if (row.isDir) {
            IconButton(onClick = onToggle) {
                Icon(Icons.Default.FolderOpen, contentDescription = "Expand")
            }
        } else {
            TextButton(onClick = onManage) { Text("•••") }
        }
    }
}

@Composable
fun NameDialog(title: String, hint: String, onDone: (String?) -> Unit) {
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name, onValueChange = { name = it; error = "" },
                    label = { Text(hint) }, singleLine = true
                )
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isBlank() || ".." in name) {
                    error = "Invalid name."
                } else onDone(name.trim())
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Cancel") } }
    )
}

@Composable
private fun ManageDialog(root: File, target: File, onClose: () -> Unit, onChanged: () -> Unit) {
    var error by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf(target.name) }
    var confirmDelete by remember { mutableStateOf(false) }
    val rel = remember(target) {
        runCatching { FileManager.relativePath(root, target) }.getOrDefault(target.name)
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(target.name) },
        text = {
            Column {
                Text(rel, style = MaterialTheme.typography.bodySmall)
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                if (renaming) {
                    OutlinedTextField(
                        value = newName, onValueChange = { newName = it },
                        label = { Text("New name") }, singleLine = true
                    )
                }
            }
        },
        confirmButton = {
            if (renaming) {
                TextButton(onClick = {
                    val r = runCatching {
                        FileManager.rename(root, rel, File(File(rel).parent ?: "", newName).path)
                    }
                    if (r.isSuccess) { onChanged(); onClose() }
                    else error = r.exceptionOrNull()?.message ?: "Rename failed."
                }) { Text("Rename") }
            } else {
                TextButton(onClick = { renaming = true }) { Text("Rename") }
            }
        },
        dismissButton = {
            if (confirmDelete) {
                TextButton(onClick = {
                    val r = runCatching { FileManager.deleteRecursively(root, rel) }
                    if (r.isSuccess) { onChanged(); onClose() }
                    else error = r.exceptionOrNull()?.message ?: "Delete failed."
                }) { Text("Delete!") }
            } else {
                TextButton(onClick = { confirmDelete = true }) { Text("Delete") }
            }
        }
    )
}
