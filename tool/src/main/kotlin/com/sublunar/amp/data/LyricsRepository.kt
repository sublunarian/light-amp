package com.sublunar.amp.data

import com.sublunar.amp.App
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json

/**
 * Where lyrics come from — the downloaded copy, the server, lrclib.net, asked in
 * that order — and which copy wins: the first synced one, else the server's plain
 * text. A server has timed lyrics only when the file carries them, which most
 * don't, so lrclib fills in the timing; it is asked only when nothing timed
 * turned up before it.
 */
object LyricsRepository {

    private const val USER_AGENT = "Amp (Light Phone III music player)"
    private const val LRCLIB = "https://lrclib.net/api"

    private val http by lazy { NetworkGate.httpClient { expectSuccess = false } }
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * What a lookup found. [Unavailable] (couldn't ask) is not [None] (there are
     * none): conflated, a track that failed once looks wordless for good.
     */
    sealed interface Outcome {
        data class Found(val lyrics: Lyrics) : Outcome
        data object None : Outcome
        data object Unavailable : Outcome

        /** Nothing stored, and the caller said not to go looking for more. */
        data object NotFetched : Outcome
    }

    /** Settled answers only — a failed lookup is never remembered. */
    private val cache = ConcurrentHashMap<String, Outcome>()

    /**
     * [allowNetwork] is the caller's "may this spend data" (see App.metadataAllowed).
     * False still returns anything downloaded with the song.
     */
    suspend fun forTrack(
        track: Track,
        client: MusicServer?,
        allowNetwork: Boolean = true,
    ): Outcome {
        cache[track.id]?.let { return it }
        val resolved = resolve(track, client, allowNetwork)
        // An outage and a question never asked are not answers to remember.
        if (resolved != Outcome.Unavailable && resolved != Outcome.NotFetched) {
            cache[track.id] = resolved
        }
        return resolved
    }

    private suspend fun resolve(
        track: Track,
        client: MusicServer?,
        allowNetwork: Boolean,
    ): Outcome {
        // Downloaded lyrics first: they cost nothing and work with no network.
        val cached = runCatching { App.downloader.cachedLyrics(track.id) }.getOrNull()
        val stored = parseStored(cached)
        stored?.let { if (it.synced) return Outcome.Found(it) }

        // A mode that forbids bytes must not also hide the copy already on the phone.
        if (!allowNetwork) return stored?.let { Outcome.Found(it) } ?: Outcome.NotFetched

        val server = runCatching { client?.getLyrics(track.id) }
        val fromServer = server.getOrNull()
        if (fromServer?.synced == true) return Outcome.Found(fromServer)

        val lrclib = runCatching { fetchLrclib(track) }
        val fromLrclib = lrclib.getOrNull()
        if (fromLrclib?.synced == true) return Outcome.Found(fromLrclib)

        // Nothing timed anywhere: whatever plain text turned up, the server's first.
        val plain = fromServer ?: fromLrclib ?: stored
        if (plain != null) return Outcome.Found(plain)

        // A lookup that threw (no network, server down) is not a "no lyrics".
        val asked = server.isSuccess && lrclib.isSuccess
        return if (asked) Outcome.None else Outcome.Unavailable
    }

    /** The `[millis]text` form Downloader.fetchLyrics stores with a download. */
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
        // get-cached is the cheap lookup; get does a full match, as lrclib's own clients do.
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
     * A line may hold several stamps (a repeated chorus), each its own entry.
     * Metadata tags like `[ar:...]` don't match and fall away. Empty-text stamps
     * (instrumental gaps) are dropped, so the previous line stays highlighted
     * through the gap.
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
