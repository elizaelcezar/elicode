package com.elicode.app.runtime

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.GZIPInputStream

/**
 * Extracts Termux `.deb` packages (ar container + `data.tar.xz`) and
 * Ubuntu `rootfs.tar.gz` archives with progress and validation.
 *
 * No system `tar`/`dpkg` is required — everything is pure JVM so it
 * runs on any Android 9+ device.
 */
object ArchiveExtractor {

    data class Progress(val entries: Int, val bytesOut: Long, val bytesTotalHint: Long)

    // ---------------- .deb (ar) ----------------

    /** Extracts `data.tar.*` from [deb] into [destDir]. Returns staged root. */
    fun extractDeb(
        deb: File,
        destDir: File,
        onProgress: (Progress) -> Unit = {}
    ): File {
        destDir.mkdirs()
        FileInputStream(deb).buffered().use { fis ->
            val magic = ByteArray(8)
            require(fis.read(magic) == 8 && String(magic) == "!<arch>\n") {
                "Not an ar archive: ${deb.name}"
            }
            var extracted = false
            while (!extracted) {
                val header = ByteArray(60)
                val read = readFully(fis, header, 60)
                if (read < 60) break
                // GNU ar terminates short names with '/' ("data.tar.xz/"):
                // strip it, or compression detection (.xz/.gz) fails and
                // xz bytes get parsed as plain tar ("Corrupted TAR archive").
                val name = String(header, 0, 16).trim().trimEnd('/')
                val size = String(header, 48, 10).trim().toLong()
                require(header[58] == 0x60.toByte() && header[59] == 0x0A.toByte()) {
                    "Corrupt ar header in ${deb.name}"
                }
                if (name.startsWith("data.tar")) {
                    // Stream the member straight into the decompressor:
                    // no full-file RAM buffering (OOM safety on phones) and
                    // no toInt() overflow cliff on large members.
                    BoundedMemberStream(fis, size).use { bounded ->
                        extractTar(bounded, name, destDir, onProgress)
                        bounded.drain()
                    }
                    extracted = true
                } else {
                    skipFully(fis, size)
                }
                if (size % 2 == 1L) skipFully(fis, 1) // ar 2-byte alignment
            }
            require(extracted) { "No data.tar.* member in ${deb.name} (truncated download?)" }
        }
        return destDir
    }

    // ---------------- rootfs .tar.gz ----------------

    fun extractRootfsTarGz(
        tgz: File,
        destDir: File,
        onProgress: (Progress) -> Unit = {}
    ) {
        destDir.mkdirs()
        val total = tgz.length()
        val counting = CountingInputStream(FileInputStream(tgz))
        BufferedInputStream(counting).use { bis ->
            GZIPInputStream(bis).use { gz ->
                extractTarStream(
                    TarArchiveInputStream(gz),
                    destDir,
                    onProgress
                ) { counting.count to total }
            }
        }
        // Some ubuntu-base tarballs nest everything under a single top dir; flatten it.
        flattenSingleTopDir(destDir)
    }

    // ---------------- internals ----------------

    private fun extractTar(
        raw: InputStream,
        name: String,
        destDir: File,
        onProgress: (Progress) -> Unit
    ) {
        val stream: InputStream = when {
            name.endsWith(".xz") -> XZInputStream(raw)
            name.endsWith(".gz") -> GZIPInputStream(raw)
            else -> raw
        }
        stream.use {
            extractTarStream(TarArchiveInputStream(it), destDir, onProgress) { -1L to -1L }
        }
    }

    private inline fun extractTarStream(
        tar: TarArchiveInputStream,
        destDir: File,
        onProgress: (Progress) -> Unit,
        bytesHint: () -> Pair<Long, Long>
    ) {
        val base = destDir.canonicalFile
        var entries = 0
        var bytesOut = 0L
        var symlinkFails = 0
        var firstSymlinkFail = ""
        var entry = tar.nextEntry
        while (entry != null) {
            val e = entry as TarArchiveEntry
            var rel = e.name.removePrefix("./").trimStart('/')
            if (rel.isNotBlank()) {
                val target = File(base, rel).canonicalFile
                require(target == base || target.path.startsWith(base.path + File.separator)) {
                    "Tar path traversal blocked: ${e.name}"
                }
                when {
                    e.isDirectory -> target.mkdirs()
                    e.isSymbolicLink -> {
                        target.parentFile?.mkdirs()
                        val ok = runCatching {
                            Files.deleteIfExists(target.toPath())
                            Files.createSymbolicLink(target.toPath(), java.nio.file.Paths.get(e.linkName))
                        }.isSuccess
                        if (!ok) {
                            symlinkFails++
                            if (firstSymlinkFail.isEmpty()) firstSymlinkFail = "$rel -> ${e.linkName}"
                        }
                    }
                    e.isFile -> {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { out ->
                            org.apache.commons.compress.utils.IOUtils.copy(tar, out)
                        }
                        bytesOut += e.size
                        if (e.mode and 0b001001001 != 0) target.setExecutable(true, false)
                    }
                }
            }
            entries++
            if (entries % 200 == 0) {
                val (a, b) = bytesHint()
                onProgress(Progress(entries, bytesOut, b))
            }
            entry = tar.nextEntry
        }
        require(symlinkFails == 0) {
            "Symlink creation failed $symlinkFails time(s) (first: $firstSymlinkFail) — " +
                "the filesystem may not support symlinks, leaving a broken tree."
        }
        val (a, b) = bytesHint()
        onProgress(Progress(entries, bytesOut, b))
    }

    private fun flattenSingleTopDir(dir: File) {
        val kids = dir.listFiles() ?: return
        if (kids.size == 1 && kids[0].isDirectory && File(kids[0], "bin/bash").exists()) {
            val top = kids[0]
            val tmp = File(dir.parentFile, dir.name + ".mv")
            top.renameTo(tmp)
            dir.deleteRecursively()
            tmp.renameTo(dir)
        }
    }

    /** ELF machine check: 183 = AArch64. Pure JVM, no execution needed. */
    fun elfMachine(path: File): Int? {
        if (!path.isFile || path.length() < 64) return null
        return runCatching {
            FileInputStream(path).use { fis ->
                val h = ByteArray(64)
                if (fis.read(h) < 64) return null
                if (!(h[0] == 0x7F.toByte() && h[1] == 'E'.code.toByte() &&
                        h[2] == 'L'.code.toByte() && h[3] == 'F'.code.toByte())
                ) return null
                ((h[19].toInt() and 0xFF) shl 8) or (h[18].toInt() and 0xFF)
            }
        }.getOrNull()
    }

    const val EM_AARCH64 = 183
    const val EM_X86_64 = 62

    /** Human-readable sizes for progress/errors (97KB debs read "0MB" otherwise). */
    fun humanBytes(bytes: Long): String = when {
        bytes < 0 -> "?"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${bytes / (1024 * 1024)} MB"
    }

    private class CountingInputStream(private val inner: InputStream) : InputStream() {
        var count = 0L
        override fun read(): Int = inner.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            inner.read(b, off, len).also { if (it > 0) count += it }
        override fun close() = inner.close()
    }

    /**
     * Caps reads at [limit] bytes (one ar member). Decompressors must
     * never read into the next member's header — the cap turns that
     * over-read into a clean EOF.
     */
    private class BoundedMemberStream(
        private val inner: InputStream,
        private var remaining: Long
    ) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val n = inner.read()
            if (n >= 0) remaining--
            return n
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = inner.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n > 0) remaining -= n
            return n
        }

        /** Discards unread member bytes so the ar cursor stays aligned. */
        fun drain() {
            val scratch = ByteArray(8192)
            while (remaining > 0) {
                val n = inner.read(scratch, 0, minOf(scratch.size.toLong(), remaining).toInt())
                if (n < 0) break
                remaining -= n
            }
        }

        override fun close() {
            // Do NOT close [inner]: the ar walk continues after this member.
        }
    }

    /** Reliable header reads: BufferedInputStream.read may short-read. */
    private fun readFully(input: InputStream, buf: ByteArray, len: Int): Int {
        var off = 0
        while (off < len) {
            val n = input.read(buf, off, len - off)
            if (n < 0) break
            off += n
        }
        return off
    }

    /** Reliable skips: InputStream.skip may return 0 before EOF. */
    private fun skipFully(input: InputStream, n: Long) {
        var left = n
        val scratch = ByteArray(8192)
        while (left > 0) {
            val want = minOf(scratch.size.toLong(), left).toInt()
            val r = input.read(scratch, 0, want)
            if (r < 0) break
            left -= r
        }
    }
}
