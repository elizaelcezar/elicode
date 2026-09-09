package com.elicode.app.core

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.UUID

/**
 * Creates, opens, imports and tracks projects.
 *
 * Projects live by default in `<filesDir>/elicode/projects/`.
 * A `project.json` sidecar file stores metadata for each project.
 */
class ProjectManager(private val context: Context) {

    private val gson = Gson()

    fun projectsRoot(): File = File(context.filesDir, "elicode/projects").apply { mkdirs() }

    fun list(): List<ProjectInfo> {
        val root = projectsRoot()
        val out = mutableListOf<ProjectInfo>()
        // 1) projects created/imported under our root (have project.json)
        root.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            readSidecar(dir)?.let { out += it }
                ?: run {
                    val det = ProjectDetector.detect(dir)
                    out += ProjectInfo(
                        id = dir.name, name = dir.name, path = dir.absolutePath, type = det.type
                    )
                }
        }
        // 2) external projects registered via SAF (sidecars stored in app prefs dir)
        out += readExternalRegistry()
        return out.sortedByDescending { it.lastOpened }
    }

    fun create(name: String, type: ProjectType, files: Map<String, String>): ProjectInfo {
        val slug = slugify(name).ifBlank { "project" }
        var dir = File(projectsRoot(), slug)
        var n = 1
        while (dir.exists()) {
            dir = File(projectsRoot(), "$slug-${++n}")
        }
        dir.mkdirs()
        files.forEach { (rel, content) ->
            val f = FileManager.resolveSafe(dir, rel)
            f.parentFile?.mkdirs()
            f.writeText(content)
        }
        val info = ProjectInfo(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { dir.name },
            path = dir.absolutePath,
            type = type,
            lastOpened = System.currentTimeMillis()
        )
        writeSidecar(dir, info)
        return info
    }

    fun importExternal(name: String, dir: File): ProjectInfo {
        require(dir.isDirectory) { "Not a directory: ${dir.absolutePath}" }
        val det = ProjectDetector.detect(dir)
        val info = ProjectInfo(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { dir.name },
            path = dir.absolutePath,
            type = det.type,
            lastOpened = System.currentTimeMillis()
        )
        // External dirs may be read-only for us; keep the sidecar in our registry.
        val registry = readExternalRegistry().toMutableList()
        registry.removeAll { it.path == info.path }
        registry += info
        writeExternalRegistry(registry)
        runCatching { writeSidecar(dir, info) }
        return info
    }

    fun registerCloned(dir: File, remote: String, branch: String): ProjectInfo {
        val det = ProjectDetector.detect(dir)
        val info = ProjectInfo(
            id = UUID.randomUUID().toString(),
            name = dir.name,
            path = dir.absolutePath,
            type = det.type,
            gitRemote = remote,
            branch = branch,
            lastOpened = System.currentTimeMillis()
        )
        runCatching { writeSidecar(dir, info) }
        return info
    }

    fun touchOpened(info: ProjectInfo): ProjectInfo {
        val updated = info.copy(lastOpened = System.currentTimeMillis())
        runCatching { writeSidecar(File(info.path), updated) }
        val registry = readExternalRegistry()
        if (registry.any { it.id == info.id }) {
            writeExternalRegistry(registry.map { if (it.id == info.id) updated else it })
        }
        return updated
    }

    fun delete(info: ProjectInfo, deleteFiles: Boolean) {
        if (deleteFiles) {
            val f = File(info.path)
            if (f.exists() && f.canonicalPath.startsWith(projectsRoot().canonicalPath)) {
                f.deleteRecursively()
            }
        } else {
            runCatching { File(File(info.path), "project.json").delete() }
        }
        writeExternalRegistry(readExternalRegistry().filterNot { it.id == info.id })
    }

    fun readGitBranch(dir: File): String {
        val head = File(dir, ".git/HEAD")
        if (!head.isFile) return ""
        val text = runCatching { head.readText().trim() }.getOrDefault("")
        return if (text.startsWith("ref: refs/heads/")) text.removePrefix("ref: refs/heads/") else text.take(12)
    }

    fun readGitRemote(dir: File): String {
        val cfg = File(dir, ".git/config")
        if (!cfg.isFile) return ""
        val text = runCatching { cfg.readText() }.getOrDefault("")
        val m = Regex("""\[remote "origin"\][^\[]*?url\s*=\s*(.+)""").find(text)
        return m?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }

    // ---- sidecars ----

    private fun sidecarFile(dir: File) = File(dir, "project.json")

    private fun readSidecar(dir: File): ProjectInfo? {
        val f = sidecarFile(dir)
        if (!f.isFile) return null
        return runCatching {
            val info = gson.fromJson(f.readText(), ProjectInfo::class.java)
            info.copy(path = dir.absolutePath)
        }.getOrNull()
    }

    private fun writeSidecar(dir: File, info: ProjectInfo) {
        sidecarFile(dir).writeText(gson.toJson(info))
    }

    private fun registryFile(): File = File(context.filesDir, "elicode/external_projects.json")

    private fun readExternalRegistry(): List<ProjectInfo> {
        val f = registryFile()
        if (!f.isFile) return emptyList()
        return runCatching {
            val t = object : TypeToken<List<ProjectInfo>>() {}.type
            (gson.fromJson<List<ProjectInfo>>(f.readText(), t) ?: emptyList()).filter {
                File(it.path).isDirectory
            }
        }.getOrDefault(emptyList())
    }

    private fun writeExternalRegistry(list: List<ProjectInfo>) {
        registryFile().apply { parentFile?.mkdirs() }.writeText(gson.toJson(list))
    }

    companion object {
        fun slugify(name: String): String =
            name.lowercase()
                .replace(Regex("[^a-z0-9._-]+"), "-")
                .trim('-')
                .take(64)
    }
}
