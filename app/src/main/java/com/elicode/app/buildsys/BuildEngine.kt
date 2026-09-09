package com.elicode.app.buildsys

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.elicode.app.core.BuildArtifact
import com.elicode.app.core.EliError
import com.elicode.app.core.EliResult
import com.elicode.app.runtime.EliProcess
import com.elicode.app.runtime.ProcessListener
import com.elicode.app.runtime.RuntimeManager
import java.io.File

/**
 * Android build pipeline: `./gradlew assembleDebug` (or system `gradle`)
 * inside the Ubuntu runtime → locate APK/AAB → install/share.
 *
 * No ADB is involved: installation uses the Android Package Installer
 * with a FileProvider content URI.
 */
class BuildEngine(private val context: Context, private val runtime: RuntimeManager) {

    data class GradleInfo(val command: String, val viaWrapper: Boolean)

    /** Detects how to invoke Gradle for [projectDir]. */
    fun detectGradle(projectDir: File): EliResult<GradleInfo> {
        val wrapper = File(projectDir, "gradlew")
        val wrapperJar = File(projectDir, "gradle/wrapper/gradle-wrapper.jar")
        if (wrapper.isFile && (wrapperJar.isFile || wrapper.canExecute())) {
            return EliResult.Ok(GradleInfo("./gradlew", viaWrapper = true))
        }
        if (wrapper.isFile) {
            // Our template's gradlew falls back to system gradle by itself.
            return EliResult.Ok(GradleInfo("./gradlew", viaWrapper = true))
        }
        if (File(projectDir, "build.gradle").isFile || File(projectDir, "build.gradle.kts").isFile()) {
            return EliResult.Ok(GradleInfo("gradle", viaWrapper = false))
        }
        return EliResult.Err(
            EliError(
                operation = "Detect Gradle",
                message = "No build.gradle / gradlew in ${projectDir.name}.",
                probableCause = "This project is not a Gradle project, or the root folder is wrong.",
                suggestedFix = "Open the project root (folder with settings.gradle), or create an Android project from a template."
            )
        )
    }

    fun assembleDebug(
        projectDir: File, listener: ProcessListener?, extraArgs: String = ""
    ): EliResult<Pair<String, EliProcess>> = assemble(projectDir, "assembleDebug", listener, extraArgs)

    fun bundleRelease(
        projectDir: File, listener: ProcessListener?, extraArgs: String = ""
    ): EliResult<Pair<String, EliProcess>> = assemble(projectDir, "bundleRelease", listener, extraArgs)

    private fun assemble(
        projectDir: File, task: String, listener: ProcessListener?, extraArgs: String
    ): EliResult<Pair<String, EliProcess>> {
        val info = detectGradle(projectDir)
        if (info is EliResult.Err) return info
        val cmd = (info as EliResult.Ok).value.command
        val args = if (extraArgs.isBlank()) "" else " $extraArgs"
        val res = runtime.startGuestTracked(
            bashCmd = "$cmd $task --console=plain --stacktrace$args",
            projectDir = projectDir,
            label = "gradle-$task",
            listener = listener
        )
        if (res is EliResult.Err) {
            val e = res.error
            return EliResult.Err(e.copy(
                probableCause = e.probableCause.ifBlank {
                    "Gradle failed. Common cause: SDK platform/build-tools missing in the runtime."
                },
                suggestedFix = e.suggestedFix.ifBlank {
                    "Install the Android toolchain (Settings → Runtime → Android), then retry."
                }
            ))
        }
        return res
    }

    /** Finds APKs produced by a build, newest first. */
    fun findApks(projectDir: File): List<BuildArtifact> =
        findOutputs(projectDir, "apk")

    fun findAabs(projectDir: File): List<BuildArtifact> =
        findOutputs(projectDir, "aab")

    private fun findOutputs(projectDir: File, ext: String): List<BuildArtifact> {
        if (!projectDir.isDirectory) return emptyList()
        return projectDir.walkTopDown()
            .filter { it.isFile && it.extension == ext && "outputs" in it.path }
            .map { f ->
                val variant = f.parentFile?.name.orEmpty()
                BuildArtifact(f, variant)
            }
            .sortedByDescending { it.file.lastModified() }
            .toList()
    }

    fun interpretBuildFailure(output: String): EliError {
        var cause = "Gradle exited with an error."
        var fix = "Read the output above; the failing task is usually named (e.g. :app:compileDebugKotlin)."
        when {
            "SDK location not found" in output || "sdk.dir" in output.lowercase() -> {
                cause = "Android SDK location is not configured for this project."
                fix = "Set sdk.dir in local.properties to the runtime Android SDK, or install the Android toolchain."
            }
            "Platform" in output && "not installed" in output -> {
                cause = "Required SDK Platform is not installed in the runtime."
                fix = "Install the missing platform (Settings → Runtime → Android toolchain)."
            }
            "Build Tools" in output && ("not installed" in output || "failed" in output) -> {
                cause = "Required Android Build-Tools are missing."
                fix = "Install build-tools in the runtime Android SDK."
            }
            "Could not resolve" in output || "No cached version" in output -> {
                cause = "A dependency could not be downloaded (offline or network issue)."
                fix = "Connect to the network and retry; Gradle caches deps after the first build."
            }
            "OutOfMemory" in output || "GC overhead" in output -> {
                cause = "Gradle ran out of memory on this device."
                fix = "Lower org.gradle.jvmargs (e.g. -Xmx1g) in gradle.properties and retry."
            }
            "AAPT2" in output && ("x86" in output || "cannot execute" in output) -> {
                cause = "An x86_64 AAPT2 was resolved instead of the ARM64 one."
                fix = "Ensure the ARM64 aapt2 override is active (runtime Android toolchain handles this)."
            }
        }
        return EliError("Gradle build", "gradlew", 1, output.takeLast(3000), cause, fix)
    }

    // ---------------- APK install (Package Installer, no ADB) ----------------

    fun canInstallUnknownApps(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun installIntent(apk: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", apk
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun shareIntent(artifact: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", artifact
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = if (artifact.extension == "aab") "application/octet-stream"
            else "application/vnd.android.package-archive"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
