package com.elicode.app.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elicode.app.core.FileManager
import java.io.File

/**
 * Real code editor: open/edit/save, undo/redo history, find/replace,
 * go-to-line, cursor line/col indicator, syntax highlight (via
 * [SyntaxVisualTransformation], length-preserving so offsets stay valid),
 * modified indicator, large-file guard.
 */
@Composable
fun CodeEditor(
    file: File,
    fontSizeSp: Float,
    onModifiedChange: (Boolean) -> Unit,
    onSaved: () -> Unit,
    saveRequestNonce: Int = 0,
    modifier: Modifier = Modifier
) {
    val ext = file.extension
    val lang = remember(ext) { Syntax.forExtension(ext) }
    var state by remember(file.absolutePath) { mutableStateOf<TextFieldValue?>(null) }
    var loadError by remember(file.absolutePath) { mutableStateOf<String?>(null) }
    var tooLarge by remember(file.absolutePath) { mutableStateOf(false) }
    var original by remember(file.absolutePath) { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var showHighlight by remember { mutableStateOf(false) }
    var showGoto by remember { mutableStateOf(false) }
    val undoStack = remember(file.absolutePath) { mutableStateListOf<String>() }
    val redoStack = remember(file.absolutePath) { mutableStateListOf<String>() }
    var lastSaved by remember(file.absolutePath) { mutableStateOf(0L) }

    // Load file.
    LaunchedEffect(file.absolutePath) {
        try {
            if (file.length() > FileManager.MAX_EDIT_BYTES) {
                tooLarge = true
                original = ""
                state = null
            } else {
                val text = file.readText()
                original = text
                state = TextFieldValue(text)
            }
        } catch (t: Throwable) {
            loadError = t.message ?: "Failed to open file."
        }
    }

    // External save request (e.g. toolbar save button).
    LaunchedEffect(saveRequestNonce) {
        if (saveRequestNonce > 0 && state != null && !tooLarge) {
            val cur = state!!.text
            if (cur != original) {
                runCatching { file.writeText(cur) }.onSuccess {
                    original = cur
                    lastSaved = System.currentTimeMillis()
                    onSaved()
                }
            }
        }
    }

    val modified = state != null && state!!.text != original
    LaunchedEffect(modified) { onModifiedChange(modified) }

    fun pushUndo(prev: String) {
        undoStack.add(prev)
        if (undoStack.size > 100) undoStack.removeAt(0)
        redoStack.clear()
    }

    fun doSave() {
        val cur = state?.text ?: return
        runCatching { file.writeText(cur) }.onSuccess {
            original = cur
            lastSaved = System.currentTimeMillis()
            onSaved()
        }
    }

    Column(modifier.fillMaxSize()) {
        // Toolbar.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            val cur = state
            val pos = cur?.selection?.start ?: 0
            val text = cur?.text.orEmpty()
            val line = text.take(pos).count { it == '\n' } + 1
            val col = pos - (text.lastIndexOf('\n', pos - 1).let { if (it < 0) -1 else it }) 
            Text(
                if (tooLarge) "read-only (large file)" else "Ln $line, Col $col${if (modified) " •" else ""}",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f).padding(start = 8.dp)
            )
            IconButton(onClick = {
                val s = state ?: return@IconButton
                if (undoStack.isNotEmpty()) {
                    redoStack.add(s.text)
                    val prev = undoStack.removeAt(undoStack.lastIndex)
                    state = TextFieldValue(prev, TextRange(prev.length.coerceAtMost(prev.length)))
                }
            }, enabled = undoStack.isNotEmpty() && !tooLarge) {
                Icon(Icons.Default.Undo, contentDescription = "Undo")
            }
            IconButton(onClick = {
                val s = state ?: return@IconButton
                if (redoStack.isNotEmpty()) {
                    undoStack.add(s.text)
                    val next = redoStack.removeAt(redoStack.lastIndex)
                    state = TextFieldValue(next, TextRange(next.length))
                }
            }, enabled = redoStack.isNotEmpty() && !tooLarge) {
                Icon(Icons.Default.Redo, contentDescription = "Redo")
            }
            IconButton(onClick = { showSearch = !showSearch }) {
                Icon(Icons.Default.FindReplace, contentDescription = "Find/replace")
            }
            IconButton(onClick = { showHighlight = !showHighlight }) {
                Icon(Icons.Default.Highlight, contentDescription = "Preview highlight")
            }
            IconButton(onClick = { showGoto = true }) {
                Text(":#", style = MaterialTheme.typography.labelLarge)
            }
            IconButton(onClick = { doSave() }, enabled = modified) {
                Icon(Icons.Default.Save, contentDescription = "Save")
            }
        }

        if (showSearch && state != null) {
            SearchBar(
                text = state!!.text,
                onReplaceAll = { find, replace ->
                    val s = state ?: return@SearchBar
                    pushUndo(s.text)
                    state = s.copy(text = s.text.replace(find, replace))
                },
                onJumpTo = { index ->
                    val s = state ?: return@SearchBar
                    state = s.copy(selection = TextRange(index.coerceIn(0, s.text.length)))
                }
            )
        }

        when {
            loadError != null -> EmptyState("Cannot open file: $loadError")
            tooLarge -> {
                // Read-only tail view for huge files (never loads all into editor).
                val tail = remember(file.absolutePath, file.lastModified()) {
                    FileManager.readTail(file)
                }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    Text(
                        "File is ${file.length()} bytes — showing last ${tail.length} chars read-only.",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(8.dp)
                    )
                    Text(
                        tail,
                        fontFamily = FontFamily.Monospace,
                        fontSize = fontSizeSp.sp,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
            showHighlight -> {
                HighlightPreview(text = state?.text.orEmpty(), lang = lang, fontSizeSp = fontSizeSp)
            }
            state != null -> {
                TextField(
                    value = state!!,
                    onValueChange = { nv ->
                        val ov = state!!
                        if (nv.text != ov.text) pushUndo(ov.text)
                        state = nv
                    },
                    modifier = Modifier.fillMaxSize(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = fontSizeSp.sp,
                        lineHeight = (fontSizeSp * 1.45).sp
                    ),
                    visualTransformation = SyntaxVisualTransformation(lang),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    singleLine = false,
                    maxLines = Int.MAX_VALUE
                )
            }
        }
    }

    if (showGoto && state != null) {
        GotoDialog(
            maxLine = state!!.text.count { it == '\n' } + 1,
            onGoto = { lineNo ->
                val s = state ?: return@GotoDialog
                var idx = 0
                var cur = 1
                while (cur < lineNo && idx < s.text.length) {
                    if (s.text[idx] == '\n') cur++
                    idx++
                }
                state = s.copy(selection = TextRange(idx.coerceIn(0, s.text.length)))
                showGoto = false
            },
            onCancel = { showGoto = false }
        )
    }
}

private class SyntaxVisualTransformation(val lang: Syntax.Lang) : VisualTransformation {
    override fun filter(text: androidx.compose.ui.text.AnnotatedString):
        androidx.compose.ui.text.input.TransformedText {
        val out = if (text.length > 200_000) text
        else Syntax.highlight(text.text, lang)
        return androidx.compose.ui.text.input.TransformedText(
            out, androidx.compose.ui.text.input.OffsetMapping.Identity
        )
    }
}

@Composable
private fun SearchBar(text: String, onReplaceAll: (String, String) -> Unit, onJumpTo: (Int) -> Unit) {
    var find by remember { mutableStateOf("") }
    var replace by remember { mutableStateOf("") }
    var matchCase by remember { mutableStateOf(false) }
    val matches = remember(text, find, matchCase) {
        if (find.isEmpty()) emptyList()
        else {
            val src = if (matchCase) text else text.lowercase()
            val q = if (matchCase) find else find.lowercase()
            val out = mutableListOf<Int>()
            var i = src.indexOf(q)
            while (i >= 0 && out.size < 500) {
                out += i
                i = src.indexOf(q, i + 1)
            }
            out
        }
    }
    var cursor by remember(find) { mutableStateOf(0) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = find, onValueChange = { find = it },
                label = { Text("Find") }, singleLine = true, modifier = Modifier.weight(1f)
            )
            Text("${matches.size}", style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = {
                if (matches.isNotEmpty()) {
                    cursor = (cursor + 1) % matches.size
                    onJumpTo(matches[cursor])
                }
            }, enabled = matches.isNotEmpty()) { Text("Next") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = replace, onValueChange = { replace = it },
                label = { Text("Replace") }, singleLine = true, modifier = Modifier.weight(1f)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = matchCase, onCheckedChange = { matchCase = it })
                Text("Aa", style = MaterialTheme.typography.labelMedium)
            }
            TextButton(
                onClick = { if (find.isNotEmpty()) onReplaceAll(find, replace) },
                enabled = find.isNotEmpty()
            ) { Text("All") }
        }
    }
}

@Composable
private fun GotoDialog(maxLine: Int, onGoto: (Int) -> Unit, onCancel: () -> Unit) {
    var value by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Go to line (1–$maxLine)") },
        text = {
            Column {
                OutlinedTextField(
                    value = value, onValueChange = { value = it.filter(Char::isDigit); error = "" },
                    singleLine = true
                )
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val n = value.toIntOrNull()
                if (n == null || n < 1 || n > maxLine) error = "Out of range."
                else onGoto(n)
            }) { Text("Go") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}

@Composable
private fun HighlightPreview(text: String, lang: Syntax.Lang, fontSizeSp: Float) {
    val annotated = remember(text, lang) {
        if (text.length > 200_000) androidx.compose.ui.text.AnnotatedString(text.take(200_000) + "\n…(truncated)")
        else Syntax.highlight(text, lang)
    }
    val lines = remember(annotated) { annotated.text.count { it == '\n' } + 1 }
    Row(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .horizontalScroll(rememberScrollState())
            .padding(8.dp)
    ) {
        Text(
            (1..lines).joinToString("\n"),
            fontFamily = FontFamily.Monospace,
            fontSize = fontSizeSp.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp)
        )
        Text(annotated, fontFamily = FontFamily.Monospace, fontSize = fontSizeSp.sp)
    }
}
