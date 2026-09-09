package com.elicode.app

import com.elicode.app.util.Net
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Regression tests for the runtime bootstrap downloader.
 *
 * Bug history: a re-download over an existing (corrupt) cache kept the
 * old file, and a checksum failure left the bad `.part` behind so every
 * retry failed again — the "runtime corrupt, install never finishes" loop.
 */
class NetTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun sha256(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    /**
     * Minimal loopback HTTP/1.1 server (raw ServerSocket — no JDK-only
     * `com.sun.*` APIs, which are invisible to the Android unit-test
     * compile classpath). Serves [payload] at /file with Range support
     * and 404 at /missing.
     */
    private class FakeHttp(var payload: ByteArray) {
        private val server = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        private val running = AtomicBoolean(true)
        val port: Int get() = server.localPort

        init {
            thread(isDaemon = true, name = "fake-http") {
                while (running.get()) {
                    val sock = runCatching { server.accept() }.getOrNull() ?: break
                    runCatching { handle(sock) }
                    runCatching { sock.close() }
                }
            }
        }

        private fun handle(sock: java.net.Socket) {
            val reader = sock.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val requestLine = reader.readLine() ?: return
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }
            val out = sock.getOutputStream()
            fun respond(status: String, extra: List<String>, body: ByteArray) {
                val head = buildString {
                    append(status).append("\r\n")
                    append("Content-Length: ").append(body.size).append("\r\n")
                    append("Connection: close\r\n")
                    extra.forEach { append(it).append("\r\n") }
                    append("\r\n")
                }
                out.write(head.toByteArray(Charsets.ISO_8859_1))
                out.write(body)
                out.flush()
            }
            val path = requestLine.split(" ").getOrNull(1).orEmpty()
            if (path != "/file") {
                respond("HTTP/1.1 404 Not Found", emptyList(), ByteArray(0))
                return
            }
            val data = payload
            val range = headers["range"]
            if (range != null && range.startsWith("bytes=")) {
                val start = range.removePrefix("bytes=").substringBefore("-").toLongOrNull() ?: 0L
                val tail = data.copyOfRange(start.coerceIn(0L, data.size.toLong()).toInt(), data.size)
                respond(
                    "HTTP/1.1 206 Partial Content",
                    listOf("Content-Range: bytes $start-${data.size - 1}/${data.size}"),
                    tail
                )
            } else {
                respond("HTTP/1.1 200 OK", emptyList(), data)
            }
        }

        fun baseUrl(): String = "http://127.0.0.1:$port"

        fun stop() {
            running.set(false)
            runCatching { server.close() }
        }
    }

    private fun serve(payload: ByteArray): FakeHttp = FakeHttp(payload)

    @Test
    fun downloadWritesFileAndVerifiesSha() {
        val payload = "elicode-runtime-bytes".toByteArray()
        val server = serve(payload)
        try {
            val dest = File(tmp.root, "a.bin")
            Net.download("${server.baseUrl()}/file", dest, sha256(payload), onProgress = {})
            assertArrayEquals(payload, dest.readBytes())
        } finally {
            server.stop()
        }
    }

    @Test
    fun redownloadReplacesExistingCorruptCache() {
        val v1 = "version-one-payload!!".toByteArray()
        val v2 = "version-two-payload!!".toByteArray()
        val server = serve(v1)
        val dest = File(tmp.root, "b.bin")
        try {
            Net.download("${server.baseUrl()}/file", dest, sha256(v1), onProgress = {})
        } finally {
            server.stop()
        }
        // Simulate a corrupt/stale cache sitting at dest.
        dest.writeBytes("CORRUPT".toByteArray())
        val server2 = serve(v2)
        try {
            Net.download("${server2.baseUrl()}/file", dest, sha256(v2), onProgress = {})
            assertArrayEquals(v2, dest.readBytes())
        } finally {
            server2.stop()
        }
    }

    @Test
    fun checksumMismatchFailsAndDropsPartial() {
        val payload = "good-payload".toByteArray()
        val server = serve(payload)
        try {
            val dest = File(tmp.root, "c.bin")
            try {
                Net.download(
                    "${server.baseUrl()}/file",
                    dest,
                    "00".repeat(32),
                    onProgress = {})
                fail("expected checksum failure")
            } catch (e: java.io.IOException) {
                assertTrue(e.message!!.contains("Checksum", ignoreCase = true))
            }
            assertFalse(File(tmp.root, "c.bin.part").exists())
            assertFalse(dest.exists())
        } finally {
            server.stop()
        }
    }

    @Test
    fun downloadFirstAvailableFallsBackToNextMirror() {
        val payload = "mirror-payload".toByteArray()
        val server = serve(payload)
        try {
            val port = server.port
            val dest = File(tmp.root, "d.bin")
            Net.downloadFirstAvailable(
                listOf(
                    "http://127.0.0.1:$port/missing",
                    "http://127.0.0.1:$port/file"
                ),
                dest,
                sha256(payload),
                onProgress = {})
            assertArrayEquals(payload, dest.readBytes())
        } finally {
            server.stop()
        }
    }

    @Test
    fun resumeContinuesPartialFile() {
        val payload = ByteArray(512 * 1024) { (it % 251).toByte() }
        val server = serve(payload)
        try {
            val dest = File(tmp.root, "e.bin")
            // Simulate an interrupted earlier download: first half present.
            File(tmp.root, "e.bin.part").writeBytes(payload.copyOfRange(0, payload.size / 2))
            Net.download("${server.baseUrl()}/file", dest, sha256(payload), onProgress = {})
            assertEquals(payload.size.toLong(), dest.length())
            assertArrayEquals(payload, dest.readBytes())
        } finally {
            server.stop()
        }
    }
}
