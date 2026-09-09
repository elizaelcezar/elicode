package com.elicode.app

import com.elicode.app.core.ProjectDetector
import com.elicode.app.core.ProjectType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProjectDetectorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun dir(vararg files: Pair<String, String>): File {
        val root = tmp.newFolder()
        files.forEach { (rel, content) ->
            val f = File(root, rel)
            f.parentFile.mkdirs()
            f.writeText(content)
        }
        return root
    }

    @Test
    fun detectsAndroidGradle() {
        val root = dir("settings.gradle" to "", "app/build.gradle" to "")
        assertEquals(ProjectType.ANDROID_GRADLE, ProjectDetector.detect(root).type)
    }

    @Test
    fun detectsVite() {
        val root = dir(
            "package.json" to "{\"dependencies\":{\"vite\":\"^5.0.0\"}}",
            "vite.config.ts" to ""
        )
        assertEquals(ProjectType.VITE, ProjectDetector.detect(root).type)
    }

    @Test
    fun detectsNext() {
        val root = dir(
            "package.json" to "{\"dependencies\":{\"next\":\"14.0.0\"}}",
            "next.config.js" to ""
        )
        assertEquals(ProjectType.NEXTJS, ProjectDetector.detect(root).type)
    }

    @Test
    fun detectsReact() {
        val root = dir("package.json" to "{\"dependencies\":{\"react\":\"18.0.0\"}}")
        assertEquals(ProjectType.REACT, ProjectDetector.detect(root).type)
    }

    @Test
    fun detectsPlainNode() {
        val root = dir("package.json" to "{\"name\":\"x\"}")
        assertEquals(ProjectType.NODE, ProjectDetector.detect(root).type)
    }

    @Test
    fun detectsFlutter() {
        val root = dir("pubspec.yaml" to "name: x")
        assertEquals(ProjectType.FLUTTER, ProjectDetector.detect(root).type)
    }

    @Test
    fun detectsPython() {
        val root = dir("requirements.txt" to "")
        assertEquals(ProjectType.PYTHON, ProjectDetector.detect(root).type)
    }

    @Test
    fun detectsCpp() {
        val root = dir("CMakeLists.txt" to "")
        assertEquals(ProjectType.CPP, ProjectDetector.detect(root).type)
    }

    @Test
    fun detectsStaticWeb() {
        val root = dir("index.html" to "<html></html>")
        assertEquals(ProjectType.STATIC_WEB, ProjectDetector.detect(root).type)
    }

    @Test
    fun detectsEmpty() {
        val root = tmp.newFolder()
        assertEquals(ProjectType.EMPTY, ProjectDetector.detect(root).type)
    }

    @Test
    fun defaultPorts() {
        assertEquals(5173, ProjectDetector.defaultPort(ProjectType.VITE))
        assertEquals(3000, ProjectDetector.defaultPort(ProjectType.NODE))
        assertEquals(8080, ProjectDetector.defaultPort(ProjectType.STATIC_WEB))
    }
}
