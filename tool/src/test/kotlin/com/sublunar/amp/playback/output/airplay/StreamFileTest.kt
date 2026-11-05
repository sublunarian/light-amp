package com.sublunar.amp.playback.output.airplay

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files

/** The fetch-once file a streamed track is read from, against a server on the loopback. */
class StreamFileTest {
    private val body = ByteArray(300_000) { (it * 31 % 251).toByte() }
    // One request per connection: /whole sends the body, /cut declares it
    // all, sends a third and hangs up.
    private val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
    private val serving = Thread {
        while (!server.isClosed) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            socket.use {
                val request = it.getInputStream().bufferedReader().readLine().orEmpty()
                val out = it.getOutputStream()
                out.write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                if (request.contains("/cut")) out.write(body, 0, body.size / 3) else out.write(body)
                out.flush()
            }
        }
    }.apply {
        isDaemon = true
        start()
    }
    private val dir: File = Files.createTempDirectory("streamfile").toFile()

    @After
    fun stop() {
        server.close()
        dir.deleteRecursively()
    }

    private fun url(path: String) = "http://127.0.0.1:${server.localPort}$path"

    private fun readAll(file: StreamFile): ByteArray {
        val out = ByteArray(body.size)
        var at = 0
        while (at < out.size) {
            val n = file.readAt(at.toLong(), out, at, minOf(8192, out.size - at))
            if (n < 0) break
            at += n
        }
        return out.copyOf(at)
    }

    @Test
    fun `a stream reads back whole, from anywhere, as often as asked`() {
        val file = StreamFile(url("/whole"), File(dir, "a"))
        assertArrayEquals(body, readAll(file))
        val tail = ByteArray(10)
        assertEquals(10, file.readAt(body.size - 10L, tail, 0, 10))
        assertArrayEquals(body.copyOfRange(body.size - 10, body.size), tail)
        assertEquals(-1, file.readAt(body.size.toLong(), tail, 0, 10))
        assertEquals(body.size.toLong(), file.size)
        file.close()
    }

    @Test
    fun `a reader interrupted mid-read doesn't spoil the file for the next`() {
        val file = StreamFile(url("/whole"), File(dir, "b"))
        readAll(file)
        // A stopped feed's thread, interrupted while it reads.
        val reader = Thread {
            Thread.currentThread().interrupt()
            runCatching { file.readAt(0, ByteArray(100), 0, 100) }
        }
        reader.start()
        reader.join()
        assertTrue(!file.failed)
        assertArrayEquals(body, readAll(file))
        file.close()
    }

    @Test
    fun `a stream cut short of its length is a failure, not the end`() {
        val file = StreamFile(url("/cut"), File(dir, "c"))
        val read = runCatching { readAll(file) }
        assertTrue(read.exceptionOrNull() is IOException || file.failed)
        assertTrue(file.failed)
        file.close()
    }
}
