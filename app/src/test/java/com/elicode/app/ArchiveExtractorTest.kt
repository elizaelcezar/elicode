package com.elicode.app

import com.elicode.app.runtime.ArchiveExtractor
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPOutputStream

class ArchiveExtractorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun elfMachineRejectsNonElf() {
        val f = tmp.newFile("plain.txt")
        f.writeText("hello")
        assertNull(ArchiveExtractor.elfMachine(f))
    }

    @Test
    fun elfMachineDetectsAarch64() {
        val f = tmp.newFile("proot")
        val header = ByteArray(64)
        header[0] = 0x7F.toByte()
        header[1] = 'E'.code.toByte()
        header[2] = 'L'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = 2 // 64-bit
        header[18] = 0xB7.toByte() // 183 = EM_AARCH64 (little endian)
        header[19] = 0x00.toByte()
        f.writeBytes(header)
        assertEquals(183, ArchiveExtractor.elfMachine(f))
    }

    @Test
    fun extractDebRoundTrip() {
        // Build a minimal .deb: ar container with data.tar.gz member.
        val payload = "fake proot binary".toByteArray()
        val tarGz = buildTarGz(mapOf("./usr/bin/proot" to payload))
        val deb = File(tmp.root, "p.deb")
        writeAr(deb, mapOf("debian-binary" to "2.0\n".toByteArray(), "data.tar.gz" to tarGz))

        val out = File(tmp.root, "out")
        ArchiveExtractor.extractDeb(deb, out)

        val proot = File(out, "usr/bin/proot")
        assertTrue("missing: ${out.walkTopDown().map { it.path }}", proot.isFile)
        assertEquals("fake proot binary", proot.readText())
    }

    @Test
    fun extractDebGnuSlashNames() {
        // Real GNU ar (Termux .debs) terminates short names with '/':
        // "data.tar.xz/". A parser that only trim()s whitespace mistakes
        // xz bytes for plain tar ("Corrupted TAR archive" on-device).
        // NOTE: the payload must exceed one 512B tar record, otherwise the
        // truncated stream ends silently instead of throwing like the
        // real 97KB .deb does.
        val rnd = java.util.Random(7)
        val payload = ByteArray(4096).also { rnd.nextBytes(it) }
        val tarXz = buildTarXz(mapOf("./usr/bin/proot" to payload))
        val deb = File(tmp.root, "gnu.deb")
        writeAr(deb, mapOf("debian-binary/" to "2.0\n".toByteArray(), "data.tar.xz/" to tarXz))

        val out = File(tmp.root, "out-gnu")
        ArchiveExtractor.extractDeb(deb, out)

        val proot = File(out, "usr/bin/proot")
        assertTrue(proot.isFile)
        assertEquals(payload.toList(), proot.readBytes().toList())
    }

    @Test
    fun extractDebStreamsLargeMember() {
        // 2MB of incompressible payload: proves the member is streamed,
        // not buffered whole into RAM (OOM safety on phones).
        val rnd = java.util.Random(42)
        val payload = ByteArray(2 * 1024 * 1024).also { rnd.nextBytes(it) }
        val tarGz = buildTarGz(mapOf("./opt/big.bin" to payload))
        val deb = File(tmp.root, "big.deb")
        writeAr(deb, mapOf("debian-binary" to "2.0\n".toByteArray(), "data.tar.gz" to tarGz))

        val out = File(tmp.root, "out-big")
        ArchiveExtractor.extractDeb(deb, out)

        val f = File(out, "opt/big.bin")
        assertTrue(f.isFile)
        assertEquals(payload.toList(), f.readBytes().toList())
    }

    @Test
    fun extractDebSymlinkBehaviorIsExplicit() {
        // Symlink support is environment-dependent (Windows needs Dev
        // Mode): assert SUCCESS where supported, and the loud failure
        // (not a silently broken tree) where it is not.
        val canLink = runCatching {
            val t = File(tmp.root, "probe-target").apply { writeText("x") }
            val l = File(tmp.root, "probe-link")
            java.nio.file.Files.deleteIfExists(l.toPath())
            java.nio.file.Files.createSymbolicLink(l.toPath(), t.toPath())
            l.delete()
            t.delete()
            true
        }.getOrDefault(false)

        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { gz ->
            TarArchiveOutputStream(gz).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                val f = TarArchiveEntry("./real.txt")
                val payload = "abc".toByteArray()
                f.size = payload.size.toLong()
                tar.putArchiveEntry(f)
                tar.write(payload)
                tar.closeArchiveEntry()
                val l = TarArchiveEntry("./link.txt", TarArchiveEntry.LF_SYMLINK)
                l.linkName = "real.txt"
                tar.putArchiveEntry(l)
                tar.closeArchiveEntry()
                tar.finish()
            }
        }
        val deb = File(tmp.root, "link.deb")
        writeAr(deb, mapOf("data.tar.gz" to bos.toByteArray()))

        val out = File(tmp.root, "out-link")
        if (canLink) {
            ArchiveExtractor.extractDeb(deb, out)
            assertTrue(java.nio.file.Files.isSymbolicLink(File(out, "link.txt").toPath()))
            assertEquals("abc", File(out, "link.txt").readText())
        } else {
            try {
                ArchiveExtractor.extractDeb(deb, out)
                org.junit.Assert.fail("expected loud symlink failure")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("Symlink creation failed"))
            }
        }
    }

    @Test
    fun extractDebTruncatedFailsLoudly() {
        val payload = "fake proot binary".toByteArray()
        val tarGz = buildTarGz(mapOf("./usr/bin/proot" to payload))
        val full = File(tmp.root, "full.deb")
        writeAr(full, mapOf("debian-binary" to "2.0\n".toByteArray(), "data.tar.gz" to tarGz))
        // Simulate a truncated download: cut the file mid-member.
        val bytes = full.readBytes()
        val cut = File(tmp.root, "cut.deb")
        cut.writeBytes(bytes.copyOf((bytes.size * 0.6).toInt()))
        try {
            ArchiveExtractor.extractDeb(cut, File(tmp.root, "out-cut"))
            assertTrue("should have thrown on truncated deb", false)
        } catch (e: Exception) {
            assertTrue(
                "unexpected: ${e.javaClass.simpleName}: ${e.message}",
                e is IllegalArgumentException || e is java.io.IOException
            )
        }
    }

    @Test
    fun extractDebBlocksPathTraversal() {
        val tarGz = buildTarGz(mapOf("../../evil.sh" to "x".toByteArray()))
        val deb = File(tmp.root, "evil.deb")
        writeAr(deb, mapOf("data.tar.gz" to tarGz))
        try {
            ArchiveExtractor.extractDeb(deb, File(tmp.root, "out2"))
            assertTrue("should have thrown", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("traversal", ignoreCase = true))
        }
    }

    // ---------- helpers ----------

    private fun buildTarXz(entries: Map<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        org.tukaani.xz.XZOutputStream(bos, org.tukaani.xz.LZMA2Options()).use { xz ->
            TarArchiveOutputStream(xz).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                entries.forEach { (name, bytes) ->
                    val e = TarArchiveEntry(name)
                    e.size = bytes.size.toLong()
                    e.mode = 493 // 0755
                    tar.putArchiveEntry(e)
                    tar.write(bytes)
                    tar.closeArchiveEntry()
                }
                tar.finish()
            }
        }
        return bos.toByteArray()
    }

    private fun buildTarGz(entries: Map<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { gz ->
            TarArchiveOutputStream(gz).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                entries.forEach { (name, bytes) ->
                    val e = TarArchiveEntry(name)
                    e.size = bytes.size.toLong()
                    e.mode = 493 // 0755
                    tar.putArchiveEntry(e)
                    tar.write(bytes)
                    tar.closeArchiveEntry()
                }
                tar.finish()
            }
        }
        return bos.toByteArray()
    }

    private fun writeAr(deb: File, members: Map<String, ByteArray>) {
        FileOutputStream(deb).use { out ->
            out.write("!<arch>\n".toByteArray())
            members.forEach { (name, bytes) ->
                val header = ByteArray(60) { ' '.code.toByte() }
                name.toByteArray().copyInto(header, 0, 0, minOf(name.length, 16))
                bytes.size.toString().toByteArray().copyInto(header, 48)
                header[58] = 0x60.toByte()
                header[59] = 0x0A.toByte()
                out.write(header)
                out.write(bytes)
                if (bytes.size % 2 == 1) out.write(0x0A)
            }
        }
    }
}
