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
            var dataBytes: ByteArray? = null
            var dataName = ""
            while (true) {
                val header = ByteArray(60)
                val read = fis.read(header)
                if (read < 60) break
                val name = String(header, 0, 16).trim()
                val size = String(header, 48, 10).trim().toLong()
                require(header[58] == 0x60.toByte() && header[59] == 0x0A.toByte()) {
                    "Corrupt ar header in ${deb.name}"
                }
                if (name.startsWith("data.tar")) {
                    dataName = name
                    dataBytes = ByteArray(size.toInt())
                    var off = 0
                    while (off < size) {
                        val n = fis.read(dataBytes, off, (size - off).toInt())
                        if (n < 0) throw java.io.IOException("Truncated data member in ${deb.name}")
                        off += n
                    }
                } else {
                    var skipped = 0L
                    while (skipped < size) {
                        val n = fis.skip(size - skipped)
                        if (n <= 0) break
                        skipped += n
                    }
                }
                if (size % 2 == 1L) fis.skip(1) // ar 2-byte alignment
                if (dataBytes != null) break
            }
            require(dataBytes != null) { "No data.tar.* member in ${deb.name}" }
            extractTar(dataBytes.inputStream(), dataName, destDir, onProgress)
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
                        runCatching { Files.deleteIfExists(target.toPath()) }
                        runCatching {
                            Files.createSymbolicLink(target.toPath(), java.nio.file.Paths.get(e.linkName))
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

    private class CountingInputStream(private val inner: InputStream) : InputStream() {
        var count = 0L
        override fun read(): Int = inner.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            inner.read(b, off, len).also { if (it > 0) count += it }
        override fun close() = inner.close()
    }
}
