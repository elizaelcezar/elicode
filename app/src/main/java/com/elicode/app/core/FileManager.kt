package com.elicode.app.core

import java.io.File

/**
 * Safe file operations scoped to a project root.
 *
 * Guards against path traversal: every resolved path must stay inside
 * the project directory. Large files are rejected before being loaded
 * into memory (see [MAX_EDIT_BYTES]).
 */
object FileManager {

    /** Files bigger than this are opened read-only in chunks, never fully loaded. */
    const val MAX_EDIT_BYTES: Long = 1_000_000L

    fun resolveSafe(root: File, relative: String): File {
        val base = root.canonicalFile
        val target = File(base, relative).canonicalFile
        require(target.path == base.path || target.path.startsWith(base.path + File.separator)) {
            "Path traversal blocked: $relative"
        }
        return target
    }

    fun listChildren(dir: File): List<File> {
        val entries = dir.listFiles() ?: return emptyList()
        return entries.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    fun readText(file: File, maxBytes: Long = MAX_EDIT_BYTES): String {
        require(file.length() <= maxBytes) {
            "File too large (${file.length()} bytes). Limit is $maxBytes bytes."
        }
        return file.readText()
    }

    fun readTail(file: File, maxChars: Int = 8000): String {
        if (!file.exists()) return ""
        val bytes = file.readBytes()
        val text = String(bytes, Charsets.UTF_8)
        return if (text.length <= maxChars) text else text.takeLast(maxChars)
    }

    fun writeText(file: File, text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    fun createFile(root: File, relative: String, content: String = ""): File {
        val target = resolveSafe(root, relative)
        require(!target.exists()) { "File already exists: $relative" }
        target.parentFile?.mkdirs()
        target.writeText(content)
        return target
    }

    fun createDir(root: File, relative: String): File {
        val target = resolveSafe(root, relative)
        target.mkdirs()
        return target
    }

    fun deleteRecursively(root: File, relative: String) {
        val target = resolveSafe(root, relative)
        require(target != root.canonicalFile) { "Refusing to delete the project root itself." }
        target.deleteRecursively()
    }

    fun rename(root: File, from: String, to: String): File {
        val src = resolveSafe(root, from)
        val dst = resolveSafe(root, to)
        require(!dst.exists()) { "Destination already exists: $to" }
        require(src.renameTo(dst)) { "Rename failed: $from -> $to" }
        return dst
    }

    /** Relative path of [file] inside [root], using '/' separators. */
    fun relativePath(root: File, file: File): String {
        val base = root.canonicalFile.toURI()
        return base.relativize(file.canonicalFile.toURI()).path.trimEnd('/')
    }
}
