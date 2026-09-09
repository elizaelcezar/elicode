package com.elicode.app.util

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * HTTP downloader with resume (Range), progress callback and SHA-256 verification.
 * Used by the runtime bootstrap; surfaces real progress, never fake "done".
 */
object Net {

    data class Progress(val bytesDone: Long, val bytesTotal: Long) {
        val fraction: Float get() = if (bytesTotal > 0) bytesDone.toFloat() / bytesTotal else -1f
    }

    /**
     * Downloads [url] to [dest]. Resumes a partial `.part` file when the
     * server honors Range. Returns the final file.
     *
     * Fix history: an older finalize step kept a stale [dest] when
     * `renameTo` failed (Android's renameTo fails if [dest] already
     * exists), so a re-download after a corrupted cache silently returned
     * the old corrupt file and the installer looped on "checksum ok but
     * runtime broken". Now [dest] is removed before the rename and a
     * copy fallback covers filesystems where rename fails.
     */
    fun download(
        url: String,
        dest: File,
        expectedSha256: String = "",
        onProgress: (Progress) -> Unit = {},
        isCancelled: () -> Boolean = { false }
    ): File {
        dest.parentFile?.mkdirs()
        val part = File(dest.parentFile, dest.name + ".part")
        var done = if (part.exists()) part.length() else 0L

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "EliCode/0.1")
            if (done > 0) setRequestProperty("Range", "bytes=$done-")
        }
        conn.connect()
        val code = conn.responseCode
        val total: Long = when (code) {
            HttpURLConnection.HTTP_PARTIAL -> {
                // Content-Range: bytes 100-999/1234
                conn.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                    ?: (done + conn.contentLengthLong)
            }
            HttpURLConnection.HTTP_OK -> {
                done = 0L
                part.delete()
                conn.contentLengthLong
            }
            else -> throw java.io.IOException("HTTP $code for $url")
        }
        if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
            throw java.io.IOException("HTTP $code for $url")
        }
        onProgress(Progress(done, total))
        conn.inputStream.use { input ->
            FileOutputStream(part, done > 0).use { out ->
                val buf = ByteArray(128 * 1024)
                var lastEmit = 0L
                while (true) {
                    if (isCancelled()) throw InterruptedException("download cancelled")
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (done - lastEmit > 256 * 1024) {
                        lastEmit = done
                        onProgress(Progress(done, total))
                    }
                }
            }
        }
        onProgress(Progress(done, total))
        if (expectedSha256.isNotBlank()) {
            val actual = sha256(part)
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                // Drop the bad partial data: resuming from it would only
                // re-append garbage and fail the checksum again forever.
                part.delete()
                throw java.io.IOException(
                    "Checksum mismatch for ${dest.name}: expected $expectedSha256, got $actual (source: $url)"
                )
            }
        }
        // renameTo() fails when dest already exists: remove it first so a
        // re-download after corruption really replaces the file.
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) {
            // Cross-filesystem fallback: copy + delete.
            part.inputStream().use { input ->
                dest.outputStream().use { out -> input.copyTo(out) }
            }
            part.delete()
        }
        if (!dest.exists()) throw java.io.IOException("Failed to finalize ${dest.name} from $url")
        return dest
    }

    /**
     * Tries each URL in order until one downloads successfully.
     * Used so a dead primary mirror falls back instead of aborting the
     * whole runtime install. Throws the LAST error when all fail.
     */
    fun downloadFirstAvailable(
        urls: List<String>,
        dest: File,
        expectedSha256: String = "",
        onProgress: (Progress) -> Unit = {},
        isCancelled: () -> Boolean = { false }
    ): File {
        require(urls.isNotEmpty()) { "No download URLs for ${dest.name}" }
        var last: Throwable? = null
        urls.forEachIndexed { index, url ->
            try {
                return download(url, dest, expectedSha256, onProgress, isCancelled)
            } catch (e: InterruptedException) {
                throw e
            } catch (t: Throwable) {
                last = t
                if (index < urls.lastIndex && !isCancelled()) {
                    onProgress(Progress(-1L, -1L))
                }
            }
        }
        throw java.io.IOException(
            "All ${urls.size} mirror(s) failed for ${dest.name}: ${last?.message}",
            last
        )
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun readUrl(url: String, timeoutMs: Int = 15_000, headers: Map<String, String> = emptyMap()): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "EliCode/0.1")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        val code = conn.responseCode
        if (code !in 200..299) throw java.io.IOException("HTTP $code for $url")
        return conn.inputStream.bufferedReader().use { it.readText() }
    }
}
