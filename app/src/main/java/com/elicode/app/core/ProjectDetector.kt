package com.elicode.app.core

import java.io.File

/**
 * Detects the project type from well-known marker files.
 * Pure JVM logic — covered by unit tests.
 */
object ProjectDetector {

    fun detect(root: File): Detection {
        if (!root.isDirectory) return Detection(ProjectType.UNKNOWN, "")
        fun has(name: String) = File(root, name).exists()

        // Gradle / Android first (most specific markers win).
        if (has("settings.gradle") || has("settings.gradle.kts")) {
            return Detection(ProjectType.ANDROID_GRADLE, "settings.gradle")
        }
        if (has("build.gradle") || has("build.gradle.kts")) {
            val marker = if (has("AndroidManifest.xml") || File(root, "app").isDirectory) {
                "build.gradle (+app/)"
            } else "build.gradle"
            return Detection(ProjectType.ANDROID_GRADLE, marker)
        }
        if (has("pubspec.yaml")) return Detection(ProjectType.FLUTTER, "pubspec.yaml")
        if (has("CMakeLists.txt") || has("Makefile")) {
            return Detection(ProjectType.CPP, if (has("CMakeLists.txt")) "CMakeLists.txt" else "Makefile")
        }
        if (has("package.json")) {
            val text = runCatching { File(root, "package.json").readText() }.getOrDefault("")
            return when {
                has("vite.config.ts") || has("vite.config.js") || has("vite.config.mjs") ->
                    Detection(ProjectType.VITE, "vite.config.*")
                has("next.config.js") || has("next.config.mjs") || has("next.config.ts") ||
                    text.contains("\"next\"") -> Detection(ProjectType.NEXTJS, "next.config.*")
                text.contains("\"react\"") || text.contains("\"react-scripts\"") ->
                    Detection(ProjectType.REACT, "package.json (react)")
                else -> Detection(ProjectType.NODE, "package.json")
            }
        }
        if (has("requirements.txt") || has("pyproject.toml") || has("main.py") || has("app.py")) {
            return Detection(ProjectType.PYTHON, "requirements.txt/pyproject.toml")
        }
        if (has("index.html") || has("index.htm")) return Detection(ProjectType.STATIC_WEB, "index.html")
        val entries = root.listFiles()
        if (entries == null || entries.isEmpty()) return Detection(ProjectType.EMPTY, "(empty dir)")
        return Detection(ProjectType.UNKNOWN, "")
    }

    /** Default shell command used by "Run" for a given type. */
    fun defaultRunCommand(type: ProjectType): List<String> = when (type) {
        ProjectType.VITE, ProjectType.REACT, ProjectType.NEXTJS, ProjectType.NODE ->
            listOf("sh", "-c", "npm install --no-audit --no-fund && npm run dev -- --host 0.0.0.0")
        ProjectType.STATIC_WEB ->
            listOf("sh", "-c", "python3 -m http.server 8080")
        ProjectType.PYTHON ->
            listOf("sh", "-c", "python3 main.py || python3 app.py")
        ProjectType.FLUTTER -> listOf("sh", "-c", "flutter run")
        ProjectType.ANDROID_GRADLE -> listOf("sh", "-c", "./gradlew assembleDebug")
        ProjectType.CPP -> listOf("sh", "-c", "cmake -B build && cmake --build build")
        else -> listOf("sh")
    }

    /** Default preview port hint per type. */
    fun defaultPort(type: ProjectType): Int = when (type) {
        ProjectType.VITE -> 5173
        ProjectType.NEXTJS, ProjectType.REACT, ProjectType.NODE -> 3000
        ProjectType.STATIC_WEB -> 8080
        ProjectType.PYTHON -> 8000
        else -> 8080
    }
}
