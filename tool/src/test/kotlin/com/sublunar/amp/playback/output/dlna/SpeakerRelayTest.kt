package com.sublunar.amp.playback.output.dlna

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.OkHttpClient

/**
 * The relay end to end, the way a speaker uses it: raw HTTP from a socket on
 * one side, a plain "music server" on a local port on the other.
 */
class SpeakerRelayTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val song = Random(11).nextBytes(200_000)
    private val heard = CopyOnWriteArrayList<String>()
    private val logged = CopyOnWriteArrayList<String>()
    private lateinit var origin: ServerSocket
    private lateinit var relay: SpeakerRelay
    @Volatile private var allowed = true

    private val secret get() = "http://127.0.0.1:${origin.localPort}/rest/stream?id=7&u=me&t=SECRET&s=salt"

    @BeforeTest
    fun setUp() {
        origin = ServerSocket(0, 16, loopback)
        thread(isDaemon = true) { serveOrigin() }
        relay = SpeakerRelay({ OkHttpClient() }, { allowed }, { logged += it }, { loopback })
    }

    @AfterTest
    fun tearDown() {
        relay.release("127.0.0.1")
        origin.close()
    }

    private class Answer(val head: String, val body: ByteArray) {
        val status get() = head.lineSequence().first().split(' ')[1].toInt()
        fun header(name: String) = head.lineSequence().firstOrNull { it.startsWith("$name:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()
    }

    private fun address(url: String = secret) = assertNotNull(relay.address(url, "127.0.0.1"))

    private fun ask(address: String, method: String = "GET", version: String = "1.1", vararg headers: String): Answer {
        val uri = URI(address)
        Socket(uri.host, uri.port).use { socket ->
            val request = "$method ${uri.rawPath}${uri.rawQuery?.let { "?$it" }.orEmpty()} HTTP/$version\r\nHost: phone\r\n" +
                headers.joinToString("") { "$it\r\n" } + "\r\n"
            socket.getOutputStream().write(request.toByteArray())
            return read(socket.getInputStream())
        }
    }

    private fun read(input: InputStream): Answer {
        val all = input.readBytes()
        val end = String(all, Charsets.ISO_8859_1).indexOf("\r\n\r\n")
        return Answer(String(all, 0, end, Charsets.ISO_8859_1), all.copyOfRange(end + 4, all.size))
    }

    private fun dechunk(body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var at = 0
        while (true) {
            val lineEnd = String(body, at, minOf(16, body.size - at), Charsets.ISO_8859_1).indexOf("\r\n")
            val size = String(body, at, lineEnd, Charsets.ISO_8859_1).toInt(16)
            at += lineEnd + 2
            if (size == 0) return out.toByteArray()
            out.write(body, at, size)
            at += size + 2
        }
    }

    @Test
    fun `the speaker gets the song and never the login`() {
        val address = address()
        assertFalse("SECRET" in address || "u=me" in address || ":${origin.localPort}" in address, address)
        val answer = ask(address, headers = arrayOf("User-Agent: Denon"))
        assertEquals(200, answer.status)
        assertEquals("audio/flac", answer.header("Content-Type"))
        assertEquals(song.size.toString(), answer.header("Content-Length"))
        assertContentEquals(song, answer.body)
        assertFalse("SECRET" in answer.head)
        assertTrue(heard.single().startsWith("GET /rest/stream?id=7&u=me&t=SECRET"), heard.single())
        assertTrue("User-Agent: Denon" in heard.single(), heard.single())
    }

    @Test
    fun `one stream keeps one address, and another has its own`() {
        assertEquals(address(), address())
        assertNotEquals(address(), address("$secret&format=mp3"))
    }

    @Test
    fun `a restart's extra parameter leads to the same stream`() {
        assertContentEquals(song, ask(address() + "?ampTry=2").body)
    }

    @Test
    fun `a range is the server's range`() {
        val answer = ask(address(), headers = arrayOf("Range: bytes=1000-1999"))
        assertEquals(206, answer.status)
        assertEquals("bytes 1000-1999/${song.size}", answer.header("Content-Range"))
        assertContentEquals(song.copyOfRange(1000, 2000), answer.body)
    }

    @Test
    fun `a HEAD asks the server for a HEAD and tells the real length`() {
        val answer = ask(address(), method = "HEAD")
        assertEquals(200, answer.status)
        assertEquals(song.size.toString(), answer.header("Content-Length"))
        assertEquals(0, answer.body.size)
        assertTrue(heard.single().startsWith("HEAD "), heard.single())
    }

    @Test
    fun `a stream of unknown length is chunked for HTTP 1_1 and ends with the connection for 1_0`() {
        val live = address("$secret&live=1")
        val chunked = ask(live)
        assertEquals("chunked", chunked.header("Transfer-Encoding"))
        assertContentEquals(song, dechunk(chunked.body))
        val plain = ask(live, version = "1.0")
        assertNull(plain.header("Transfer-Encoding"))
        assertNull(plain.header("Content-Length"))
        assertContentEquals(song, plain.body)
    }

    @Test
    fun `two requests for one song run side by side`() {
        val address = address()
        val uri = URI(address)
        Socket(uri.host, uri.port).use { first ->
            first.getOutputStream().write("GET ${uri.rawPath} HTTP/1.1\r\nHost: phone\r\n\r\n".toByteArray())
            first.getInputStream().read()
            assertContentEquals(song.copyOfRange(0, 10), ask(address, headers = arrayOf("Range: bytes=0-9")).body)
            assertContentEquals(song, read(ByteArrayPrefixed('H'.code, first.getInputStream())).body)
        }
    }

    @Test
    fun `a server's refusal goes on as its status, without its words`() {
        val answer = ask(address("$secret&fail=1"))
        assertEquals(500, answer.status)
        assertEquals(0, answer.body.size)
        assertFalse("SECRET" in answer.head)
    }

    @Test
    fun `an address never handed out leads nowhere`() {
        val address = address()
        assertEquals(404, ask(address.substringBeforeLast('/') + "/00000000000000000000000000000000").status)
        assertEquals(404, ask(address.substringBefore("/c/") + "/selftest").status)
        assertEquals(405, ask(address, method = "POST").status)
        assertTrue(heard.isEmpty())
    }

    @Test
    fun `while the link's rule is closed the server isn't asked`() {
        val address = address()
        allowed = false
        assertEquals(503, ask(address).status)
        assertTrue(heard.isEmpty())
    }

    @Test
    fun `a device that isn't the speaker is turned away unanswered`() {
        val address = assertNotNull(relay.address(secret, "192.0.2.50"))
        val uri = URI(address)
        Socket(uri.host, uri.port).use { socket ->
            socket.soTimeout = 5_000
            val got = try {
                socket.getOutputStream().write("GET ${uri.rawPath} HTTP/1.1\r\n\r\n".toByteArray())
                socket.getInputStream().read()
            } catch (_: SocketException) {
                -1
            }
            assertEquals(-1, got)
        }
        assertTrue(heard.isEmpty())
        relay.release("192.0.2.50")
    }

    @Test
    fun `when the cast ends the address stops working`() {
        val uri = URI(address())
        relay.release("127.0.0.1")
        assertTrue(runCatching { Socket(uri.host, uri.port).close() }.isFailure)
    }

    @Test
    fun `a new speaker replaces the last, whose addresses go with it`() {
        val first = address()
        relay.address(secret, "localhost")
        val back = address()
        assertNotEquals(first, back)
    }

    @Test
    fun `the log says which side ended a stream`() {
        ask(address())
        ask(address("$secret&short=1"))
        val ends = logged.filter { "ended by" in it }
        assertTrue(ends[0].startsWith("relay: 200 sized ended by the server's end after"), ends[0])
        assertTrue("ended by the server breaking off" in ends[1], ends[1])
        assertTrue(ends.none { "SECRET" in it || "127.0.0.1" in it }, ends.toString())
    }

    @Test
    fun `a speaker's request is read as it was sent`() {
        val plain = assertNotNull(speakerRequest("GET /c/abc?ampTry=3 HTTP/1.0\r\nrange: bytes=5-\r\nUSER-AGENT: Denon\r\n\r\n"))
        assertEquals("abc", plain.token)
        assertTrue(plain.http10)
        assertEquals("bytes=5-", plain.range)
        assertEquals("Denon", plain.userAgent)
        assertEquals("abc", speakerRequest("GET http://10.0.0.2:5000/c/abc HTTP/1.1\r\n\r\n")?.token)
        assertNull(speakerRequest("GET /other HTTP/1.1\r\n\r\n")?.token)
        assertNull(speakerRequest("nonsense\r\n\r\n"))
    }

    /** [stream], with the [first] byte already taken from it put back in front. */
    private class ByteArrayPrefixed(private var first: Int, private val stream: InputStream) : InputStream() {
        override fun read(): Int = if (first >= 0) first.also { first = -1 } else stream.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (first < 0) return stream.read(b, off, len)
            b[off] = first.toByte()
            first = -1
            return 1
        }
    }

    private fun serveOrigin() {
        while (!origin.isClosed) {
            val socket = runCatching { origin.accept() }.getOrNull() ?: return
            thread(isDaemon = true) { socket.use(::answerOrigin) }
        }
    }

    private fun answerOrigin(socket: Socket) {
        val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val lines = generateSequence { input.readLine() }.takeWhile { it.isNotEmpty() }.toList()
        heard += lines.joinToString("\n")
        val output = socket.getOutputStream()
        val path = lines.first()
        val head = path.startsWith("HEAD")
        val range = lines.firstOrNull { it.startsWith("Range:", ignoreCase = true) }
            ?.substringAfter("bytes=")?.split('-')
        when {
            "fail=1" in path -> {
                val words = "no such song: u=me&t=SECRET".toByteArray()
                output.write("HTTP/1.1 500 Oops\r\nContent-Length: ${words.size}\r\nConnection: close\r\n\r\n".toByteArray())
                output.write(words)
            }
            "short=1" in path -> {
                output.write("HTTP/1.1 200 OK\r\nContent-Type: audio/flac\r\nContent-Length: ${song.size}\r\nConnection: close\r\n\r\n".toByteArray())
                output.write(song, 0, song.size / 2)
            }
            "live=1" in path -> {
                output.write("HTTP/1.1 200 OK\r\nContent-Type: audio/mpeg\r\nConnection: close\r\n\r\n".toByteArray())
                output.write(song)
            }
            range != null -> {
                val from = range[0].toInt()
                val to = range[1].toIntOrNull() ?: song.lastIndex
                output.write(
                    ("HTTP/1.1 206 Partial Content\r\nContent-Type: audio/flac\r\nContent-Range: bytes $from-$to/${song.size}\r\n" +
                        "Content-Length: ${to - from + 1}\r\nConnection: close\r\n\r\n").toByteArray(),
                )
                output.write(song, from, to - from + 1)
            }
            else -> {
                output.write(
                    ("HTTP/1.1 200 OK\r\nContent-Type: audio/flac\r\nAccept-Ranges: bytes\r\nSet-Cookie: session=SECRET\r\n" +
                        "Content-Length: ${song.size}\r\nConnection: close\r\n\r\n").toByteArray(),
                )
                if (!head) output.write(song)
            }
        }
        output.flush()
    }
}
