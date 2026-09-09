package com.elicode.app.core

import java.io.File

/** Supported project kinds, detected automatically from marker files. */
enum class ProjectType(val label: String) {
    ANDROID_GRADLE("Android (Gradle)"),
    NODE("Node.js"),
    VITE("Vite"),
    NEXTJS("Next.js"),
    REACT("React"),
    FLUTTER("Flutter"),
    PYTHON("Python"),
    CPP("C/C++"),
    STATIC_WEB("Static Web"),
    EMPTY("Empty"),
    UNKNOWN("Unknown")
}

/** Persistent metadata of a project known to EliCode. */
data class ProjectInfo(
    val id: String,
    val name: String,
    val path: String,
    val type: ProjectType,
    val gitRemote: String = "",
    val branch: String = "",
    val lastOpened: Long = 0L
) {
    val dir: File get() = File(path)
}

/** A single detection hit: which marker file implied which type. */
data class Detection(val type: ProjectType, val marker: String)

/** A dev-server found running for preview purposes. */
data class PreviewTarget(val port: Int, val url: String, val pid: Long = -1)

/** A built Android artifact (APK/AAB). */
data class BuildArtifact(
    val file: File,
    val variant: String,
    val sizeBytes: Long = file.length()
)

/** Log entry with category, persisted by [com.elicode.app.core.logs.LogStore]. */
data class LogEntry(
    val time: Long = System.currentTimeMillis(),
    val category: String,
    val tag: String,
    val message: String
)
