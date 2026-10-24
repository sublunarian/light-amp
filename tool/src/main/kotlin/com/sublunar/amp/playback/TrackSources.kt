package com.sublunar.amp.playback

import com.sublunar.amp.App
import com.sublunar.amp.data.DownloadStore
import com.sublunar.amp.data.LinkRules
import com.sublunar.amp.data.MusicServer
import com.sublunar.amp.data.PhoneFiles
import com.sublunar.amp.data.StreamFormat
import com.sublunar.amp.data.Track
import com.sublunar.amp.data.db.DownloadFile
import com.sublunar.amp.data.qualityRank
import com.thelightphone.sdk.audio.LightAudioItem
import com.thelightphone.sdk.audio.LightAudioSource
import com.thelightphone.sdk.audio.LightMediaMetadata
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Where each track plays from: the phone's own file, its download, or a
 * stream through the loopback door, and in which format from which address.
 * The controller asks [source] whenever it builds or corrects the player's
 * queue, and tells this what failed.
 */
internal class TrackSources(
    private val serverClient: StateFlow<MusicServer?>,
    private val downloads: DownloadStore,
    /** The id a stream of a song carries — see [ServerReports.streamSessionIdFor]. */
    private val streamSessionIdFor: (trackId: String, decidedFirst: Boolean) -> String,
) {
    /** Downloaded tracks' files and formats, kept current as downloads land, so queue items build synchronously. */
    private var localFiles: Map<String, Pair<File, StreamFormat>> = emptyMap()

    /**
     * Latched by the controller's fallBackOffline: downloads play from disk
     * even where the stream is better, until the server answers again.
     */
    var forceOffline = false

    /** The track fallBackOffline last picked up, so it tries each one once — see [firstRetryOf]. */
    private var retriedTrackId: String? = null

    /**
     * Tracks whose stream failed, which play from their download instead.
     * Narrower than [forceOffline]: for a server that is plainly there but
     * won't serve this one song.
     */
    private val streamFailed = mutableSetOf<String>()

    /**
     * Songs whose original the server failed on its usual address, fetched
     * from its other address from then on — see [moveOriginal]. By source and
     * id, since two servers can share an id. Kept for the process: a file
     * refused once is refused on the next queue too.
     */
    private val originalElsewhere = mutableSetOf<Pair<String, String>>()

    /**
     * The active source's downloads changed: note which are on disk, and in
     * which format. The files are looked at off Main, and the answer kept on
     * the caller's thread.
     */
    suspend fun downloadsChanged(rows: List<DownloadFile>) {
        localFiles = withContext(Dispatchers.IO) {
            rows.mapNotNull { row ->
                downloads.existing(App.source.value.id, row.fileName)?.let { file ->
                    row.trackId to (file to StreamFormat.fromId(row.format))
                }
            }.toMap()
        }
    }

    /** Streaming may be tried again: nothing learnt while it failed or wasn't allowed outlives that. */
    fun forgetFailures() {
        forceOffline = false
        retriedTrackId = null
        streamFailed.clear()
    }

    /** Something new to play is reason enough to try each song's stream again. */
    fun forgetStreamFailures() {
        streamFailed.clear()
    }

    /** True the first time a dead stream of [trackId] is picked up, false when it was tried already. */
    fun firstRetryOf(trackId: String): Boolean {
        if (retriedTrackId == trackId) return false
        retriedTrackId = trackId
        return true
    }

    /** [trackId] plays from its download from now on, even where its stream would be preferred. */
    fun preferDownload(trackId: String) {
        streamFailed += trackId
    }

    fun hasDownload(trackId: String): Boolean = localFiles.containsKey(trackId)

    /** A phone file or a download: playable with no server. */
    fun hasLocalCopy(track: Track): Boolean = PhoneFiles.isPhoneFile(track.id) || hasDownload(track.id)

    /** Whether [track] can play under [rules]: from the phone, or streamed through the door. */
    fun isPlayable(track: Track, rules: LinkRules): Boolean =
        hasLocalCopy(track) || (rules.mayStream && App.streamProxy.isOn)

    /**
     * The format to ask for: the source's Wi-Fi format on an unmetered link,
     * its cellular format otherwise. Two stated choices, never a mode quietly
     * capping one: asked for lossless on cellular, lossless is what comes.
     */
    fun effectiveFormat(): StreamFormat {
        val source = App.source.value
        return if (App.rules.value.unmetered) source.wifiFormat else source.cellularFormat
    }

    /** Whether the door will ask the server's decision for this stream right before fetching it — see [playerUrl]. */
    fun decidesAtDoor(client: MusicServer, track: Track, format: StreamFormat): Boolean =
        client.oneSessionPerPlayer && App.streamProxy.isOn && client.streamNeedsDecision(track, format)

    /**
     * The server failed [track]'s original on its usual address: fetch it from
     * the other address its server has for the same file, for good. False
     * when that isn't the original, it moved already, or there is no other
     * address.
     */
    fun moveOriginal(track: Track): Boolean {
        if (effectiveFormat() != StreamFormat.RAW || track.originalKey() in originalElsewhere) return false
        if (serverClient.value?.alternateOriginalUrl(track) == null) return false
        originalElsewhere += track.originalKey()
        return true
    }

    /** This song's entry in [originalElsewhere]: the source playing it, and its id there. */
    private fun Track.originalKey(): Pair<String, String> = App.source.value.id to id

    /**
     * [MusicServer.streamUrl], except for a song moved to its server's other
     * address for the original — see [moveOriginal]. Every URL Amp plays,
     * casts or probes is made here, so a moved song stays moved however it is
     * reached next.
     */
    private fun MusicServer.playableUrl(
        track: Track,
        format: StreamFormat,
        timeOffsetSeconds: Int,
        estimateContentLength: Boolean,
        sessionId: String?,
    ): String {
        val moved = format == StreamFormat.RAW && track.originalKey() in originalElsewhere
        return (if (moved) alternateOriginalUrl(track) else null)
            ?: streamUrl(track, format, timeOffsetSeconds, estimateContentLength, sessionId)
    }

    /** The server's own address for [track], for a far end that fetches it itself; null with no server. */
    fun serverUrl(
        track: Track,
        format: StreamFormat,
        timeOffsetSeconds: Int,
        estimateContentLength: Boolean,
        sessionId: String?,
    ): String? = serverClient.value?.playableUrl(track, format, timeOffsetSeconds, estimateContentLength, sessionId)

    /**
     * Where to play a track from. A download wins, and is passed over only
     * when the chosen streaming format is better than the copy and the bytes
     * are free: an unmetered link, whatever the mode. Even Make it Hurt plays
     * a download from its file.
     */
    fun source(track: Track): LightAudioSource {
        // A phone file is the file, always: no stream to prefer, no server to ask.
        PhoneFiles.fileOf(track.id)?.let { return LightAudioSource.FileSource(it) }
        val local = localFiles[track.id]
        if (local != null) {
            val (file, downloadedFormat) = local
            val preferStream = !forceOffline && track.id !in streamFailed && App.rules.value.unmetered &&
                effectiveFormat().qualityRank > downloadedFormat.qualityRank
            if (!preferStream) return LightAudioSource.FileSource(file)
        }
        return LightAudioSource.UrlSource(playerUrl(track))
    }

    /** [track] as the player's queue holds it, from where [source] says. */
    fun item(track: Track): LightAudioItem = track.toAudioItem(source(track))

    /**
     * The address the player is handed for [track], in the format this link
     * asks for and starting [timeOffsetSeconds] in: through the loopback door
     * when it is up (see StreamProxy), so the player only ever holds this
     * phone's address.
     *
     * Where the server keeps one session per player, the door also asks the
     * server's decision for this very song right before fetching it, under
     * that session's id: Plex holds a session it tracks to a decision per
     * song, and the player fetches ahead and on its own, so nothing in front
     * of it can know when to ask. Without the door the stream gets an id of
     * its own — see [ServerReports.streamSessionIdFor].
     */
    fun playerUrl(track: Track, timeOffsetSeconds: Int = 0): String {
        val client = serverClient.value ?: return ""
        val format = effectiveFormat()
        val decideFirst = decidesAtDoor(client, track, format)
        val sid = streamSessionIdFor(track.id, decideFirst)
        val url = client.playableUrl(
            track,
            format,
            timeOffsetSeconds = timeOffsetSeconds,
            // No estimated Content-Length: declaring one for a transcode makes
            // the server finish the encode first, so a long track took minutes
            // to start. Seeks go through the server, not by byte, so nothing
            // needs it. Only Subsonic reads the flag.
            estimateContentLength = false,
            sessionId = sid,
        )
        // Asked of whichever client is current at fetch time: one rebuilt for
        // the same source since (a new address, a renewed token) is the one
        // that can still be heard.
        val decision: (suspend () -> Unit)? =
            if (decideFirst) ({ serverClient.value?.prepareStream(track.id, format, sid) }) else null
        return App.streamProxy.wrap(url, group = track.id, prepare = decision)
    }
}

/** [tracks] as player queue items, each from the source already resolved for it in [from]. */
internal fun itemsFrom(tracks: List<Track>, from: List<LightAudioSource>): List<LightAudioItem> =
    tracks.mapIndexed { i, track -> track.toAudioItem(from[i]) }

/** [this] as a player queue item playing from [from]. */
internal fun Track.toAudioItem(from: LightAudioSource): LightAudioItem = LightAudioItem(
    source = from,
    metadata = LightMediaMetadata(
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
    ),
)
