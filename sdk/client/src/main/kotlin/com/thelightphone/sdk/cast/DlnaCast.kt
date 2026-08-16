package com.thelightphone.sdk.cast

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * TEMPORARY — DLNA / UPnP-AV casting.
 *
 * Lives in the SDK because a tool can't do this itself: the plugin sandbox blocks
 * the pieces network discovery needs, and this is deliberately NOT part of the
 * Light SDK's supported surface. It must be deleted before submitting the tool
 * to Light. See the memory note `dlna-cast-spike` for the full revert list.
 *
 * Implements just enough of a UPnP AV control point to push a stream at a
 * renderer: SSDP discovery, then SOAP calls against the device's AVTransport
 * service. No external dependency — SSDP is raw UDP and the SOAP calls are
 * plain HTTP, so `java.net` covers all of it.
 */
data class DlnaRenderer(
    /** Device UDN, stable across discoveries. */
    val id: String,
    val name: String,
    /** Absolute URL of the device's AVTransport control endpoint. */
    val controlUrl: String,
    /** RenderingControl endpoint, when the device exposes volume control. */
    val renderingControlUrl: String? = null,
    /** ConnectionManager endpoint, used to ask which formats it accepts. */
    val connectionManagerUrl: String? = null,
)

/** Playback state reported by a renderer. */
enum class DlnaState { PLAYING, PAUSED, STOPPED, TRANSITIONING, UNKNOWN }

data class DlnaPosition(
    val positionMs: Long,
    val durationMs: Long,
    /** What the renderer says it is playing — how a gapless hand-off is noticed. */
    val trackUri: String? = null,
)

object DlnaCast {

    private const val SSDP_ADDRESS = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
    private const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"
    private const val CONNECTION_MANAGER = "urn:schemas-upnp-org:service:ConnectionManager:1"

    /**
     * Top of the fader's range, as a DLNA 0-100 volume.
     *
     * Carried over from the previous app (`services/castVolume.ts`), where the
     * window was tuned so this receiver behaves like an iPhone casting to it —
     * a full fader is 74, not a raw 100.
     */
    private const val VOLUME_CEILING = 74
    private const val RENDERER_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1"
    private const val HTTP_TIMEOUT_MS = 5_000

    // --- Discovery -----------------------------------------------------------

    /**
     * Broadcast an SSDP M-SEARCH and describe every media renderer that answers.
     *
     * Replies arrive as unicast to our ephemeral port, so a plain [DatagramSocket]
     * is enough — no multicast lock (and therefore no WifiManager) required.
     */
    suspend fun discover(timeoutMs: Long = 3_000): List<DlnaRenderer> = withContext(Dispatchers.IO) {
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
                val deadline = System.currentTimeMillis() + timeoutMs
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
        val absolute = runCatching { URL(URL(location), control).toString() }.getOrNull() ?: return null
        return DlnaRenderer(
            id = tagValue(xml, "UDN")?.takeIf { it.isNotBlank() } ?: location,
            name = name.trim(),
            controlUrl = absolute,
            renderingControlUrl = controlUrlFor(xml, "RenderingControl")
                ?.let { runCatching { URL(URL(location), it).toString() }.getOrNull() },
            connectionManagerUrl = controlUrlFor(xml, "ConnectionManager")
                ?.let { runCatching { URL(URL(location), it).toString() }.getOrNull() },
        )
    }

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
     * [title]/[artist]/[album] go over as DIDL-Lite so the renderer can show
     * what's playing rather than a bare URL.
     */
    suspend fun play(
        renderer: DlnaRenderer,
        url: String,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        mimeType: String = "audio/mpeg",
    ): Boolean {
        val didl = didlMetadata(url, title, artist, album, durationMs, mimeType)
        val set = soap(
            renderer, "SetAVTransportURI",
            """
            <InstanceID>0</InstanceID>
            <CurrentURI>${escape(url)}</CurrentURI>
            <CurrentURIMetaData>${escape(didl)}</CurrentURIMetaData>
            """.trimIndent(),
        )
        if (set == null) return false
        return resume(renderer)
    }

    /**
     * Hand the renderer the *next* track so it can cross into it without a gap.
     *
     * This is how UPnP does gapless: the renderer pre-buffers `NextAVTransportURI`
     * and switches at the end of the current stream on its own, with no round trip
     * to us. Waiting for it to report STOPPED and then pushing a new URI costs a
     * poll interval plus two SOAP calls plus a buffer fill — seconds of silence.
     *
     * Optional in the spec: renderers that don't implement it answer with a SOAP
     * fault, which surfaces here as false and leaves the stop-and-start path to
     * carry on as before.
     */
    suspend fun setNext(
        renderer: DlnaRenderer,
        url: String,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        mimeType: String = "audio/mpeg",
    ): Boolean {
        val didl = didlMetadata(url, title, artist, album, durationMs, mimeType)
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
     * protocol info.
     *
     * Worth asking because it decides whether we can hand the device lossless
     * audio instead of transcoding to MP3. An empty result means "unknown" — the
     * caller should then assume only the safe baseline.
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
     * The MIME type of what the server will actually stream, decided by sniffing
     * the container's magic bytes.
     *
     * The response header can't be trusted: a Navidrome transcoding "flac" happily
     * sends `Content-Type: audio/flac` with an **Ogg**-FLAC body. A renderer that
     * advertises `audio/flac` accepts that URI and then stalls without erroring, so
     * the bytes are the only reliable answer. Falls back to the header, then null.
     */
    suspend fun probeMime(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Range", "bytes=0-11")
                connectTimeout = HTTP_TIMEOUT_MS
                readTimeout = HTTP_TIMEOUT_MS
            }
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
     * renderer can seek inside it at all. Null when the question could not be
     * answered, so the caller keeps doing whatever it would have done.
     *
     * This has to be measured rather than inferred from the format asked for. A
     * server asked for mp3 when the file is *already* mp3 hands the file over
     * untouched — seekable, and it ignores a time offset — while the identical
     * request against a flac original is a live transcode that is neither. Only
     * the response separates them: a range request against a real file comes
     * back 206, and against a chunked encode it comes back 200 with the range
     * quietly ignored.
     */
    suspend fun supportsRanges(url: String): Boolean? = withContext(Dispatchers.IO) {
        try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Range", "bytes=0-11")
                connectTimeout = HTTP_TIMEOUT_MS
                readTimeout = HTTP_TIMEOUT_MS
            }
            val partial = connection.responseCode == HttpURLConnection.HTTP_PARTIAL
            // Drained and closed rather than abandoned: a transcode left hanging
            // on a socket nobody reads is the exact stall this file already had
            // to be fixed for once.
            runCatching { connection.inputStream.use { it.read(ByteArray(12)) } }
            connection.disconnect()
            partial
        } catch (_: Exception) {
            null
        }
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
     * Set the renderer's volume from a 0..1 fader position.
     *
     * Volume lives on a *different* UPnP service from transport, so this needs the
     * device's RenderingControl endpoint; devices without one report false.
     */
    suspend fun setVolume(renderer: DlnaRenderer, fraction: Float): Boolean {
        val url = renderer.renderingControlUrl ?: return false
        return soapTo(
            url, RENDERING_CONTROL, "SetVolume",
            "<InstanceID>0</InstanceID><Channel>Master</Channel>" +
                "<DesiredVolume>${fractionToVolume(fraction)}</DesiredVolume>",
        ) != null
    }

    /** The renderer's current volume as a 0..1 fader position, or null. */
    suspend fun volume(renderer: DlnaRenderer): Float? {
        val url = renderer.renderingControlUrl ?: return null
        val response = soapTo(
            url, RENDERING_CONTROL, "GetVolume",
            "<InstanceID>0</InstanceID><Channel>Master</Channel>",
        ) ?: return null
        val level = tagValue(response, "CurrentVolume")?.trim()?.toIntOrNull() ?: return null
        return volumeToFraction(level)
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
            val ok = connection.responseCode in 200..299
            val body = (if (ok) connection.inputStream else connection.errorStream)
                ?.use { it.readBytes().decodeToString() }
            connection.disconnect()
            if (ok) body.orEmpty() else null
        } catch (_: IOException) {
            null
        } catch (_: IllegalStateException) {
            null
        }
    }

    private fun httpGet(url: String): String? = try {
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

    private val SERVICE_BLOCK = Regex("<service>.*?</service>", RegexOption.DOT_MATCHES_ALL)
    private const val RECEIVE_SLICE_MS = 400

    /** Value of an SSDP/HTTP response header, case-insensitively. */
    internal fun headerValue(response: String, name: String): String? =
        response.lineSequence()
            .firstOrNull { it.startsWith("$name:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /**
     * First value of an XML element, ignoring namespace prefixes. Deliberately
     * regex rather than a parser: these documents are small and we only need a
     * handful of leaf values.
     */
    internal fun tagValue(xml: String, tag: String): String? =
        Regex("<(?:[A-Za-z0-9_.-]+:)?$tag[^>]*>(.*?)</(?:[A-Za-z0-9_.-]+:)?$tag>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)
            ?.groupValues
            ?.get(1)
            ?.let(::unescape)

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
