package com.sublunar.amp.data

import com.sublunar.amp.App
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json

/**
 * Where lyrics come from, and which copy wins.
 *
 * Navidrome only returns timed lyrics when the file itself carries them, and this
 * library's files mostly don't — so `getLyricsBySongId` answers with plain text
 * and there is nothing for the karaoke view to highlight. The old React Native
 * app solved this by falling back to lrclib.net for timed lyrics; this is the
 * same strategy, in the same order.
 *
 * Synced always wins, whichever side it comes from. lrclib is only asked when the
 * server didn't already provide timed lyrics, so the common case costs no extra
 * request.
 */
object LyricsRepository {

    private const val USER_AGENT = "Amp (Light Phone III music player)"
    private const val LRCLIB = "https://lrclib.net/api"

    private val http by lazy { HttpClient(OkHttp) { expectSuccess = false } }
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * What a lookup found. [Unavailable] is not [None]: the difference is whether
     * we know there are no lyrics or only that we couldn't ask, and conflating
     * them is what makes a track that failed once look permanently wordless.
     */
    sealed interface Outcome {
        data class Found(val lyrics: Lyrics) : Outcome
        data object None : Outcome
        data object Unavailable : Outcome
    }

    /** Settled answers only — a failed lookup is never remembered. */
    private val cache = ConcurrentHashMap<String, Outcome>()

    suspend fun forTrack(track: Track, client: MusicServer?): Outcome {
        cache[track.id]?.let { return it }
        val resolved = resolve(track, client)
        if (resolved != Outcome.Unavailable) cache[track.id] = resolved
        return resolved
    }

    private suspend fun resolve(track: Track, client: MusicServer?): Outcome {
        // Downloaded lyrics first: they cost nothing and work with no network.
        val cached = runCatching { App.downloader.cachedLyrics(track.id) }.getOrNull()
        parseStored(cached)?.let { if (it.synced) return Outcome.Found(it) }

        val server = runCatching { client?.getLyrics(track.id) }
        val fromServer = server.getOrNull()
        if (fromServer?.synced == true) return Outcome.Found(fromServer)

        val lrclib = runCatching { fetchLrclib(track) }
        val fromLrclib = lrclib.getOrNull()
        if (fromLrclib?.synced == true) return Outcome.Found(fromLrclib)

        // Nothing timed anywhere — take whatever plain text turned up, preferring
        // the server's own copy.
        val plain = fromServer ?: fromLrclib ?: parseStored(cached)
        if (plain != null) return Outcome.Found(plain)

        // Both lookups threw (no network, server down): say so rather than
        // caching a "no lyrics" that would outlive the outage.
        val asked = server.isSuccess && lrclib.isSuccess
        return if (asked) Outcome.None else Outcome.Unavailable
    }

    /** The `[millis]text` form the downloader writes alongside an audio file. */
    private fun parseStored(raw: String?): Lyrics? {
        if (raw.isNullOrBlank()) return null
        val lines = raw.split('\n').mapNotNull { line ->
            val match = STORED_STAMP.find(line)
            if (match != null) {
                val text = line.substring(match.range.last + 1).trim()
                if (text.isEmpty()) null else LyricLine(match.groupValues[1].toLong(), text)
            } else {
                line.trim().takeIf { it.isNotEmpty() }?.let { LyricLine(null, it) }
            }
        }
        if (lines.isEmpty()) return null
        return Lyrics(lines, synced = lines.all { it.timeMs != null })
    }

    /** Timed lyrics from lrclib, or null — used when downloading for karaoke. */
    suspend fun timedFromLrclib(track: Track): Lyrics? =
        fetchLrclib(track)?.takeIf { it.synced }

    private suspend fun fetchLrclib(track: Track): Lyrics? {
        if (track.title.isBlank() || track.artist.isBlank()) return null
        // get-cached is the cheap lookup; get does a full match and is the
        // fallback, mirroring what lrclib's own clients do.
        for (endpoint in listOf("get-cached", "get")) {
            val response = http.get("$LRCLIB/$endpoint") {
                header("User-Agent", USER_AGENT)
                parameter("track_name", track.title)
                parameter("artist_name", track.artist)
                if (track.album.isNotBlank()) parameter("album_name", track.album)
                parameter("duration", (track.durationMs / 1000).toString())
            }
            if (!response.status.isSuccess()) continue
            val body = runCatching {
                json.decodeFromString(LrcLibResponse.serializer(), response.bodyAsText())
            }.getOrNull() ?: continue
            if (body.instrumental) return null
            body.syncedLyrics?.let { parseLrc(it)?.let { parsed -> return parsed } }
            body.plainLyrics?.let { plain ->
                val lines = plain.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
                if (lines.isNotEmpty()) {
                    return Lyrics(lines.map { LyricLine(null, it) }, synced = false)
                }
            }
        }
        return null
    }

    private val STORED_STAMP = Regex("""^\[(\d+)]""")

    /** `[mm:ss.xx]` — one or more may prefix a single line. */
    private val LRC_STAMP = Regex("""\[(\d{1,2}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    /**
     * Parse LRC into timed lines, or null when it carries no usable timestamps.
     *
     * A line may hold several stamps (a chorus repeated at different times), and
     * each becomes its own entry. Metadata tags like `[ar:...]` don't match the
     * time pattern and fall away. Empty-text stamps mark instrumental gaps in LRC;
     * dropping them leaves the previous line highlighted through the gap, which is
     * what you want on screen.
     */
    fun parseLrc(raw: String): Lyrics? {
        val parsed = mutableListOf<LyricLine>()
        for (line in raw.split('\n')) {
            val stamps = LRC_STAMP.findAll(line).toList()
            if (stamps.isEmpty()) continue
            val text = LRC_STAMP.replace(line, "").trim()
            if (text.isEmpty()) continue
            for (stamp in stamps) {
                val minutes = stamp.groupValues[1].toLong()
                val seconds = stamp.groupValues[2].toLong()
                // The fraction may be centi- or milliseconds; normalise to ms.
                val fraction = stamp.groupValues[3]
                    .takeIf { it.isNotEmpty() }
                    ?.padEnd(3, '0')?.take(3)?.toLong() ?: 0L
                parsed += LyricLine(minutes * 60_000 + seconds * 1000 + fraction, text)
            }
        }
        if (parsed.isEmpty()) return null
        return Lyrics(parsed.sortedBy { it.timeMs ?: 0L }, synced = true)
    }
}

@kotlinx.serialization.Serializable
private data class LrcLibResponse(
    val syncedLyrics: String? = null,
    val plainLyrics: String? = null,
    val instrumental: Boolean = false,
)
