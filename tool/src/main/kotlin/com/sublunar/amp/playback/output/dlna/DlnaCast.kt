package com.sublunar.amp.playback.output.dlna

import com.sublunar.amp.data.Track
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A media renderer as [DlnaCast.discover] described it. */
data class DlnaRenderer(
    /** Device UDN, stable across discoveries. */
    val id: String,
    val name: String,
    /** Absolute URL of the device's AVTransport control endpoint. */
    val controlUrl: String,
    /** RenderingControl endpoint, when the device exposes volume control. */
    val renderingControlUrl: String?,
    /** ConnectionManager endpoint, used to ask which formats it accepts. */
    val connectionManagerUrl: String?,
)

/** Playback state reported by a renderer. */
enum class DlnaState { PLAYING, PAUSED, STOPPED, TRANSITIONING, UNKNOWN }

data class DlnaPosition(
    val positionMs: Long,
    val durationMs: Long,
    /** What the renderer says it is playing — how a gapless hand-off is noticed. */
    val trackUri: String?,
)

/**
 * Just enough of a UPnP AV control point to push a stream at a renderer: SSDP
 * discovery, then SOAP calls against the device's AVTransport service. SSDP is
 * raw UDP and SOAP is plain HTTP, so `java.net` covers all of it.
 */
object DlnaCast {

    /**
     * Whether casting may touch the network right now. The tool wires this to
     * its own rule; every function here that sends anything asks it first —
     * discovery, the two probes of the music server, the description fetch and
     * every SOAP call — and answers as it would had nobody replied.
     *
     * UPnP is a LAN protocol, so the rule a tool wants is "on Wi-Fi": off it,
     * a control call is a connection attempt to a private address sent out
     * over cellular, and a probe of the music server is real data on a link
     * the user may have forbidden. One switch here covers all of them, the
     * ones a caller forgot included.
     */
    @Volatile
    var allowsNetwork: () -> Boolean = { true }

    private const val SSDP_ADDRESS = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
    private const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"
    private const val CONNECTION_MANAGER = "urn:schemas-upnp-org:service:ConnectionManager:1"

    private const val RENDERER_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1"

    /**
     * Top of the fader's range, as a DLNA 0-100 volume: a full fader is 74,
     * not 100, so a receiver sounds as it does with an iPhone casting to it.
     */
    private const val VOLUME_CEILING = 74
    private const val HTTP_TIMEOUT_MS = 5_000
    private const val DISCOVERY_MS = 3_000L
    private const val RECEIVE_SLICE_MS = 400

    /** The tag a failed call is logged under: the cast's own. */
    private const val LOG_TAG = "AmpCast"

    /** More escaping than any renderer has been seen to add. */
    private const val UNESCAPE_ROUNDS = 3

    /** The parts of a stream address a log line keeps: which song, in what, from where. */
    private val LOGGED_PARAMS = listOf("id", "format", "maxBitRate", "timeOffset", "path")

    private val SERVICE_BLOCK = Regex("<service>.*?</service>", RegexOption.DOT_MATCHES_ALL)

    /** [tagValue]'s pattern for each tag, compiled once: a cast's poll reads four tags a second. */
    private val TAG_PATTERNS = ConcurrentHashMap<String, Regex>()

    // --- Discovery -----------------------------------------------------------

    /**
     * Broadcast an SSDP M-SEARCH and describe every media renderer that answers.
     *
     * Replies arrive as unicast to our ephemeral port, so a plain [DatagramSocket]
     * is enough — no multicast lock (and therefore no WifiManager) required.
     */
    suspend fun discover(): List<DlnaRenderer> = withContext(Dispatchers.IO) {
        if (!allowsNetwork()) return@withContext emptyList()
        val locations = linkedSetOf<String>()
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = RECEIVE_SLICE_MS
                val request = buildString {
                    append("M-SEARCH * HTTP/1.1\r\n")
                    append("HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n")
                    append("MAN: \"ssdp:discover\"\r\n")
                    append("MX: 2\r\n")
                    append("ST: $RENDERER_TYPE\r\n")
                    append("\r\n")
                }.toByteArray()
                val group = InetAddress.getByName(SSDP_ADDRESS)
                // Sent twice: SSDP is UDP, and a single probe is easy to drop.
                repeat(2) {
                    socket.send(DatagramPacket(request, request.size, group, SSDP_PORT))
                }

                val buffer = ByteArray(8192)
                val deadline = System.currentTimeMillis() + DISCOVERY_MS
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    headerValue(String(packet.data, 0, packet.length), "LOCATION")
                        ?.let { locations.add(it) }
                }
            }
        } catch (_: IOException) {
            return@withContext emptyList()
        }
        locations.mapNotNull { describe(it) }
    }

    /** Fetch and parse a device description document. */
    private fun describe(location: String): DlnaRenderer? {
        val xml = httpGet(location) ?: return null
        val name = tagValue(xml, "friendlyName")?.takeIf { it.isNotBlank() } ?: return null
        val control = controlUrlFor(xml, "AVTransport") ?: return null
        val absolute = resolve(location, control) ?: return null
        return DlnaRenderer(
            id = tagValue(xml, "UDN")?.takeIf { it.isNotBlank() } ?: location,
            name = name.trim(),
            controlUrl = absolute,
            renderingControlUrl = controlUrlFor(xml, "RenderingControl")?.let { resolve(location, it) },
            connectionManagerUrl = controlUrlFor(xml, "ConnectionManager")?.let { resolve(location, it) },
        )
    }

    /** [path] made absolute against the description's [location], or null when it won't parse. */
    private fun resolve(location: String, path: String): String? =
        runCatching { URL(URL(location), path).toString() }.getOrNull()

    /** The controlURL of the service block whose serviceType contains [service]. */
    internal fun controlUrlFor(xml: String, service: String): String? =
        SERVICE_BLOCK.findAll(xml)
            .map { it.value }
            .firstOrNull { it.contains(service, ignoreCase = true) }
            ?.let { tagValue(it, "controlURL") }
            ?.trim()

    // --- Transport control ---------------------------------------------------

    /**
     * Point the renderer at [url] and start playing.
     *
     * [track]'s title, artist and album go over as DIDL-Lite so the renderer
     * can show what's playing rather than a bare URL.
     */
    suspend fun play(renderer: DlnaRenderer, url: String, track: Track, mimeType: String): Boolean =
        setUri(renderer, url, track, mimeType) && resume(renderer)

    /**
     * Point the renderer at [url] without playing it. It holds the stream,
     * stopped, until [resume] — so a seek can go in before the first sound.
     */
    suspend fun setUri(renderer: DlnaRenderer, url: String, track: Track, mimeType: String): Boolean {
        val didl = didlMetadata(url, track.title, track.artist, track.album, track.durationMs, mimeType)
        return soap(
            renderer, "SetAVTransportURI",
            """
            <InstanceID>0</InstanceID>
            <CurrentURI>${escape(url)}</CurrentURI>
            <CurrentURIMetaData>${escape(didl)}</CurrentURIMetaData>
            """.trimIndent(),
        ) != null
    }

    /**
     * Hand the renderer the *next* track so it can cross into it without a gap:
     * it pre-buffers `NextAVTransportURI` and switches on its own, where a
     * stop-then-push costs seconds of silence.
     *
     * Optional in the spec: a renderer without it answers with a SOAP fault,
     * false here, and the stop-and-start path carries the queue instead.
     */
    suspend fun setNext(renderer: DlnaRenderer, url: String, track: Track, mimeType: String): Boolean {
        val didl = didlMetadata(url, track.title, track.artist, track.album, track.durationMs, mimeType)
        return soap(
            renderer, "SetNextAVTransportURI",
            """
            <InstanceID>0</InstanceID>
            <NextURI>${escape(url)}</NextURI>
            <NextURIMetaData>${escape(didl)}</NextURIMetaData>
            """.trimIndent(),
        ) != null
    }

    /** Forget any queued next track — used when the queue changes underneath it. */
    suspend fun clearNext(renderer: DlnaRenderer): Boolean =
        soap(
            renderer, "SetNextAVTransportURI",
            "<InstanceID>0</InstanceID><NextURI></NextURI><NextURIMetaData></NextURIMetaData>",
        ) != null

    suspend fun resume(renderer: DlnaRenderer): Boolean =
        soap(renderer, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>") != null

    suspend fun pause(renderer: DlnaRenderer): Boolean =
        soap(renderer, "Pause", "<InstanceID>0</InstanceID>") != null

    suspend fun stop(renderer: DlnaRenderer): Boolean =
        soap(renderer, "Stop", "<InstanceID>0</InstanceID>") != null

    suspend fun seek(renderer: DlnaRenderer, positionMs: Long): Boolean =
        soap(
            renderer, "Seek",
            "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit>" +
                "<Target>${formatClock(positionMs)}</Target>",
        ) != null

    // --- Capabilities (ConnectionManager) ------------------------------------

    /**
     * MIME types the renderer says it can play, from ConnectionManager's Sink
     * protocol info. Empty when it won't say, which [sinkAccepts] takes as
     * accepting anything.
     */
    suspend fun sinkFormats(renderer: DlnaRenderer): Set<String> {
        val url = renderer.connectionManagerUrl ?: return emptySet()
        val response = soapTo(url, CONNECTION_MANAGER, "GetProtocolInfo", "") ?: return emptySet()
        val sink = tagValue(response, "Sink") ?: return emptySet()
        // Entries look like `http-get:*:audio/flac:DLNA.ORG_PN=...`.
        return sink.split(',')
            .mapNotNull { entry -> entry.split(':').getOrNull(2)?.trim()?.lowercase() }
            .filter { it.startsWith("audio/") }
            .toSet()
    }

    /**
     * The MIME type of what the server will actually stream, sniffed from the
     * container's magic bytes. The header can't be trusted: a Navidrome
     * transcoding "flac" sends `Content-Type: audio/flac` with an **Ogg**-FLAC
     * body, which an `audio/flac` renderer accepts and then stalls on silently.
     * Falls back to the header, then null.
     */
    suspend fun probeMime(url: String): String? = withContext(Dispatchers.IO) {
        if (!allowsNetwork()) return@withContext null
        try {
            val connection = rangeGet(url)
            val head = ByteArray(12)
            val read = connection.inputStream.use { it.read(head) }
            val header = connection.contentType?.substringBefore(';')?.trim()?.lowercase()
            connection.disconnect()
            sniffContainer(head, read) ?: header
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Whether the server will hand [url] over in byte ranges, and so whether a
     * renderer can seek inside it at all. Null when it couldn't be asked.
     *
     * Measured, not inferred from the format: mp3 asked of an mp3 file is the
     * file untouched (seekable, and it ignores a time offset), while the same
     * request of a flac original is a live transcode that is neither. A range
     * request answers 206 for a file and 200 for a chunked encode.
     */
    suspend fun supportsRanges(url: String): Boolean? = withContext(Dispatchers.IO) {
        if (!allowsNetwork()) return@withContext null
        try {
            val connection = rangeGet(url)
            val partial = connection.responseCode == HttpURLConnection.HTTP_PARTIAL
            // Drained and closed: a transcode left hanging on a socket nobody
            // reads stalls.
            runCatching { connection.inputStream.use { it.read(ByteArray(12)) } }
            connection.disconnect()
            partial
        } catch (_: Exception) {
            null
        }
    }

    /** A GET for [url]'s first twelve bytes, as both probes ask. */
    private fun rangeGet(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Range", "bytes=0-11")
            connectTimeout = HTTP_TIMEOUT_MS
            readTimeout = HTTP_TIMEOUT_MS
        }

    /** Container from magic bytes, or null when nothing recognisable was read. */
    internal fun sniffContainer(head: ByteArray, length: Int): String? {
        fun matches(offset: Int, text: String): Boolean {
            if (length < offset + text.length) return false
            return text.indices.all { head[offset + it] == text[it].code.toByte() }
        }
        return when {
            matches(0, "fLaC") -> "audio/flac"
            matches(0, "OggS") -> "audio/ogg"
            matches(0, "RIFF") -> "audio/wav"
            matches(4, "ftyp") -> "audio/mp4"
            matches(0, "ID3") -> "audio/mpeg"
            // MPEG frame sync: 11 set bits.
            length >= 2 && (head[0].toInt() and 0xFF) == 0xFF &&
                (head[1].toInt() and 0xE0) == 0xE0 -> "audio/mpeg"
            else -> null
        }
    }

    // --- Volume (RenderingControl) -------------------------------------------

    /**
     * Set the renderer's volume from a 0..1 fader position. Volume is on the
     * RenderingControl service, not AVTransport: false for a device without it.
     */
    suspend fun setVolume(renderer: DlnaRenderer, fraction: Float): Boolean =
        setRawVolume(renderer, fractionToVolume(fraction))

    /** The renderer's current volume as a 0..1 fader position, or null. */
    suspend fun volume(renderer: DlnaRenderer): Float? = rawVolume(renderer)?.let(::volumeToFraction)

    /**
     * The renderer's own 0–100 volume, or null — for putting back exactly
     * what was there, which a round trip through the fader's window can't.
     */
    suspend fun rawVolume(renderer: DlnaRenderer): Int? {
        val url = renderer.renderingControlUrl ?: return null
        val response = soapTo(
            url, RENDERING_CONTROL, "GetVolume",
            "<InstanceID>0</InstanceID><Channel>Master</Channel>",
        ) ?: return null
        return tagValue(response, "CurrentVolume")?.trim()?.toIntOrNull()
    }

    /** Set the renderer's own 0–100 volume, as [rawVolume] reads it. */
    suspend fun setRawVolume(renderer: DlnaRenderer, level: Int): Boolean {
        val url = renderer.renderingControlUrl ?: return false
        return soapTo(
            url, RENDERING_CONTROL, "SetVolume",
            "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>$level</DesiredVolume>",
        ) != null
    }

    /**
     * Mute or unmute the renderer with its own switch, which leaves its volume
     * where it is. False when it has no RenderingControl, or refused.
     */
    suspend fun setMute(renderer: DlnaRenderer, muted: Boolean): Boolean {
        val url = renderer.renderingControlUrl ?: return false
        return soapTo(
            url, RENDERING_CONTROL, "SetMute",
            "<InstanceID>0</InstanceID><Channel>Master</Channel>" +
                "<DesiredMute>${if (muted) 1 else 0}</DesiredMute>",
        ) != null
    }

    /** Whether the renderer is muted, or null when it won't say. */
    suspend fun muted(renderer: DlnaRenderer): Boolean? {
        val url = renderer.renderingControlUrl ?: return null
        val response = soapTo(
            url, RENDERING_CONTROL, "GetMute",
            "<InstanceID>0</InstanceID><Channel>Master</Channel>",
        ) ?: return null
        return parseMute(tagValue(response, "CurrentMute"))
    }

    /** GetMute's answer: UPnP's boolean is 1 or 0, and some renderers spell it out. */
    internal fun parseMute(value: String?): Boolean? = when (value?.trim()?.lowercase()) {
        "1", "true", "yes" -> true
        "0", "false", "no" -> false
        else -> null
    }

    /** Fader position to DLNA volume, capped at [VOLUME_CEILING]. */
    internal fun fractionToVolume(fraction: Float): Int =
        (fraction.coerceIn(0f, 1f) * VOLUME_CEILING).toInt()

    /** DLNA volume back to a fader position, so the fader reflects the device. */
    internal fun volumeToFraction(volume: Int): Float =
        (volume.toFloat() / VOLUME_CEILING).coerceIn(0f, 1f)

    suspend fun position(renderer: DlnaRenderer): DlnaPosition? {
        val response = soap(renderer, "GetPositionInfo", "<InstanceID>0</InstanceID>") ?: return null
        return DlnaPosition(
            positionMs = parseClock(tagValue(response, "RelTime")),
            durationMs = parseClock(tagValue(response, "TrackDuration")),
            trackUri = tagValue(response, "TrackURI")?.trim()?.takeIf { it.isNotBlank() },
        )
    }

    suspend fun state(renderer: DlnaRenderer): DlnaState {
        val response = soap(renderer, "GetTransportInfo", "<InstanceID>0</InstanceID>")
            ?: return DlnaState.UNKNOWN
        return when (tagValue(response, "CurrentTransportState")?.trim()?.uppercase()) {
            "PLAYING" -> DlnaState.PLAYING
            "PAUSED_PLAYBACK", "PAUSED" -> DlnaState.PAUSED
            "STOPPED", "NO_MEDIA_PRESENT" -> DlnaState.STOPPED
            "TRANSITIONING" -> DlnaState.TRANSITIONING
            else -> DlnaState.UNKNOWN
        }
    }

    // --- SOAP / HTTP ---------------------------------------------------------

    private suspend fun soap(
        renderer: DlnaRenderer,
        action: String,
        arguments: String,
    ): String? = soapTo(renderer.controlUrl, AV_TRANSPORT, action, arguments)

    private suspend fun soapTo(
        controlUrl: String,
        serviceType: String,
        action: String,
        arguments: String,
    ): String? = withContext(Dispatchers.IO) {
        if (!allowsNetwork()) return@withContext null
        val envelope = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
              <s:Body>
                <u:$action xmlns:u="$serviceType">
                  $arguments
                </u:$action>
              </s:Body>
            </s:Envelope>
        """.trimIndent()

        try {
            val connection = (URL(controlUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = HTTP_TIMEOUT_MS
                readTimeout = HTTP_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                setRequestProperty("SOAPACTION", "\"$serviceType#$action\"")
            }
            connection.outputStream.use { it.write(envelope.toByteArray()) }
            val status = connection.responseCode
            val ok = status in 200..299
            val body = (if (ok) connection.inputStream else connection.errorStream)
                ?.use { it.readBytes().decodeToString() }
            connection.disconnect()
            // A failed call is logged with the renderer's own reason, where it gave one.
            if (ok) {
                failing.remove("$action $controlUrl")
            } else {
                noteFailure(action, controlUrl, "HTTP $status${upnpError(body)?.let { ", $it" }.orEmpty()}")
            }
            if (ok) body.orEmpty() else null
        } catch (e: IOException) {
            noteFailure(action, controlUrl, e.toString())
            null
        } catch (e: IllegalStateException) {
            noteFailure(action, controlUrl, e.toString())
            null
        }
    }

    /**
     * The calls whose last try failed, by action and renderer. A renderer gone
     * from the network fails its poll every second, too often to log each.
     */
    private val failing = ConcurrentHashMap.newKeySet<String>()

    /** Log [action]'s failure once, until it next succeeds. */
    private fun noteFailure(action: String, controlUrl: String, why: String) {
        if (failing.add("$action $controlUrl")) android.util.Log.i(LOG_TAG, "$action failed: $why")
    }

    /**
     * The UPnP error in a SOAP fault, as "UPnP 701 Transition not available",
     * or null where the body carries none. The code is the reason: 701 is a
     * state that can't take the action, 714 a format refused, 402 arguments
     * it didn't like.
     */
    internal fun upnpError(body: String?): String? {
        val code = body?.let { tagValue(it, "errorCode") }?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val description = tagValue(body, "errorDescription")?.trim().orEmpty()
        return if (description.isEmpty()) "UPnP $code" else "UPnP $code $description"
    }

    /**
     * Whether two stream addresses are the same one, as a renderer reports an
     * address back: trimmed, and with the XML escaping undone however many
     * times it was applied — a renderer may hand it back escaped once more
     * than it went in. A URL holds no entities of its own, so undoing them
     * never makes two different addresses equal.
     */
    internal fun sameUri(a: String?, b: String?): Boolean =
        a != null && b != null && uriForm(a) == uriForm(b)

    private fun uriForm(uri: String): String {
        var form = uri.trim()
        repeat(UNESCAPE_ROUNDS) {
            val undone = unescape(form)
            if (undone == form) return form
            form = undone
        }
        return form
    }

    /**
     * [uri] for a log line: where it points and which song, never the
     * credentials riding in its query — a Subsonic token, a Plex one.
     */
    internal fun loggable(uri: String?): String {
        if (uri == null) return "nothing"
        val form = uriForm(uri)
        val kept = form.substringAfter('?', "").split('&')
            .filter { param -> LOGGED_PARAMS.any { param.startsWith("$it=") } }
        val path = form.substringBefore('?')
        return if (kept.isEmpty()) path else path + "?" + kept.joinToString("&")
    }

    private fun httpGet(url: String): String? = if (!allowsNetwork()) null else try {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = HTTP_TIMEOUT_MS
            readTimeout = HTTP_TIMEOUT_MS
        }
        val body = if (connection.responseCode in 200..299) {
            connection.inputStream.use { it.readBytes().decodeToString() }
        } else {
            null
        }
        connection.disconnect()
        body
    } catch (_: IOException) {
        null
    }

    // --- Parsing helpers -----------------------------------------------------

    /** Value of an SSDP/HTTP response header, case-insensitively. */
    internal fun headerValue(response: String, name: String): String? =
        response.lineSequence()
            .firstOrNull { it.startsWith("$name:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /**
     * First value of an XML element, ignoring namespace prefixes. Regex rather
     * than a parser: the documents are small and only a few leaf values are read.
     */
    internal fun tagValue(xml: String, tag: String): String? =
        tagPattern(tag).find(xml)
            ?.groupValues
            ?.get(1)
            ?.let(::unescape)

    private fun tagPattern(tag: String): Regex = TAG_PATTERNS.getOrPut(tag) {
        Regex("<(?:[A-Za-z0-9_.-]+:)?$tag[^>]*>(.*?)</(?:[A-Za-z0-9_.-]+:)?$tag>", RegexOption.DOT_MATCHES_ALL)
    }

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun unescape(value: String): String = value
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

    internal fun didlMetadata(
        url: String,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        mimeType: String = "audio/mpeg",
    ): String = buildString {
        append("""<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" """)
        append("""xmlns:dc="http://purl.org/dc/elements/1.1/" """)
        append("""xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">""")
        append("""<item id="0" parentID="-1" restricted="1">""")
        append("<dc:title>").append(escape(title)).append("</dc:title>")
        append("<upnp:artist>").append(escape(artist)).append("</upnp:artist>")
        append("<upnp:album>").append(escape(album)).append("</upnp:album>")
        append("<upnp:class>object.item.audioItem.musicTrack</upnp:class>")
        append("""<res protocolInfo="http-get:*:$mimeType:*" """)
        append("""duration="${formatClock(durationMs)}">""")
        append(escape(url))
        append("</res>")
        append("</item></DIDL-Lite>")
    }

    /** Milliseconds as UPnP's `H:MM:SS` clock value. */
    internal fun formatClock(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0L)
        return "%d:%02d:%02d".format(total / 3600, (total % 3600) / 60, total % 60)
    }

    /** Parse a UPnP `H:MM:SS[.f]` clock value; 0 when absent or malformed. */
    internal fun parseClock(value: String?): Long {
        val parts = value?.trim()?.substringBefore('.')?.split(':') ?: return 0L
        if (parts.size != 3) return 0L
        val hours = parts[0].toLongOrNull() ?: return 0L
        val minutes = parts[1].toLongOrNull() ?: return 0L
        val seconds = parts[2].toLongOrNull() ?: return 0L
        return ((hours * 3600) + (minutes * 60) + seconds) * 1000L
    }
}
