package com.sublunar.amp.source.plex.companion

import android.util.Log
import com.sublunar.amp.source.plex.PlexClient
import com.sublunar.amp.source.plex.PlexException
import com.sublunar.amp.source.plex.PlexResource
import com.sublunar.amp.source.serverJson
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.isSuccess
import java.net.URI
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withLock

// Plex Companion, Plex's own remote-control protocol, as calls on the Plex
// client, which holds the token, the server's address and the per-player lock
// they need. Commands go straight to the player's Companion port (see
// PlexPlayer); the player pulls the audio from the server itself and, unlike a
// DLNA renderer, holds the whole play queue and walks it on its own. The play
// queues are the server's. Everything here answers XML, read by
// [plexXmlElements].

/**
 * Companion-capable players, from two lists because neither has everyone:
 * the server's `/clients` knows only players on its own LAN, and the Apple
 * TV app announces itself to plex.tv instead, which the account's
 * resources cover. Both are asked at once.
 */
suspend fun PlexClient.companionPlayers(): List<PlexPlayer> = coroutineScope {
    val fromServer = async { runCatching { serverPlayers() }.getOrDefault(emptyList()) }
    val fromAccount = async {
        runCatching { accountPlayers() }.getOrElse {
            Log.i(TAG, "plex.tv device list failed: ${it::class.simpleName}")
            emptyList()
        }
    }
    val server = fromServer.await()
    val account = fromAccount.await()
    val seen = server.map { it.id }.toSet()
    Log.i(TAG, "players: server=${server.map { it.name }} account=${account.map { "${it.name}@${it.directUrl}" }}")
    server + account.filter { it.id !in seen }
}

/** The players the server found on its own LAN. */
private suspend fun PlexClient.serverPlayers(): List<PlexPlayer> =
    plexXmlElements(companionXml("/clients"), "Server").mapNotNull { attrs ->
        val id = attrs["machineIdentifier"] ?: return@mapNotNull null
        // Entries that can't take playback commands are controllers or
        // servers — not somewhere sound can go.
        if (!(attrs["protocolCapabilities"] ?: "").contains("playback")) return@mapNotNull null
        // The server's list names the player's own address and Companion
        // port; commands go there, not back through the server.
        val host = attrs["address"] ?: attrs["host"] ?: return@mapNotNull null
        val port = attrs["port"]?.toIntOrNull() ?: COMPANION_PORT
        PlexPlayer(
            id = id,
            name = attrs["name"] ?: attrs["product"] ?: "Plex player",
            directUrl = "http://$host:$port",
            // The server found it; it is who the server says.
            identityKnown = true,
        )
    }

/** The account's players, as plex.tv lists them. */
private suspend fun PlexClient.accountPlayers(): List<PlexPlayer> {
    val response = request("/api/v2/resources", listOf("includeHttps" to "1"), host = PLEX_TV)
    if (!response.status.isSuccess()) {
        throw PlexException("plex.tv says ${response.status.value} for the device list")
    }
    return serverJson.decodeFromString<List<PlexResource>>(response.bodyAsText())
        .filter { it.provides.contains("player") && it.clientIdentifier.isNotBlank() }
        // Not filtered on plex.tv's `presence`, which goes stale: an Apple TV
        // that slept and woke reads as absent while playing. A player that
        // answers nothing simply fails to take the cast, which is visible,
        // unlike a device missing from the list.
        .mapNotNull { resource ->
            // The local address first: plain http, no round trip through
            // Plex's relay. A player with no address can't be commanded, so it
            // isn't offered.
            val direct = resource.connections
                .filter { it.address.isNotBlank() && it.port > 0 }
                .sortedByDescending { it.local }
                .firstOrNull()
                ?.let { "http://${it.address}:${it.port}" }
                ?: return@mapNotNull null
            PlexPlayer(
                id = resource.clientIdentifier,
                name = resource.name.ifBlank { resource.product.ifBlank { "Plex player" } },
                directUrl = direct,
            )
        }
}

/**
 * Create a server-side play queue over [trackIds], cued on [startId].
 * The queue belongs to the server; whoever plays it walks it themselves.
 */
suspend fun PlexClient.createPlayQueue(trackIds: List<String>, startId: String, repeatAll: Boolean): PlexQueue? = runCatching {
    val mid = machineId()
    if (mid.isBlank()) {
        Log.i(TAG, "no machine identifier — the server can't be named in a queue uri")
        return@runCatching null
    }
    Log.i(TAG, "building a queue of ${trackIds.size} on $mid, starting at $startId")
    // A queue is built out of this server's own rating keys. Ids from
    // another source name nothing here, and the server rejects the lot
    // with no hint which it was.
    val alien = trackIds.filterNot { it.all(Char::isDigit) }
    if (alien.isNotEmpty()) {
        Log.i(TAG, "queue holds ${alien.size} track(s) that aren't Plex ids, e.g. ${alien.first()}")
        return@runCatching null
    }
    val body = companionXml(
        "/playQueues",
        listOf(
            "type" to "audio",
            "uri" to itemUri(trackIds, mid),
            "key" to "/library/metadata/$startId",
            "shuffle" to "0",
            "repeat" to if (repeatAll) "1" else "0",
            "own" to "1",
        ),
        HttpMethod.Post,
    )
    queueFrom(body).also {
        if (it == null) Log.i(TAG, "the server made a queue with no id in it")
    }
}.getOrElse {
    Log.i(TAG, "play queue refused: ${it::class.simpleName}")
    null
}

/**
 * Re-read a queue, asking for [window] items around the current one: an
 * edit's own answer carries only as much of the queue as Plex likes, and
 * an item whose place isn't known can't be moved or removed.
 */
suspend fun PlexClient.readPlayQueue(queue: PlexQueue, window: Int): PlexQueue? = runCatching {
    queueFrom(companionXml("/playQueues/${queue.id}", listOf("window" to window.toString(), "own" to "1")))
}.getOrElse {
    Log.i(TAG, "re-reading the queue failed: ${it::class.simpleName}")
    null
}

/**
 * Move a track's place so it follows [afterTrackId] — or to the front when
 * that is null. The server answers with the queue as it now stands.
 */
suspend fun PlexClient.movePlayQueueItem(queue: PlexQueue, trackId: String, afterTrackId: String?): PlexQueue? = runCatching {
    val place = queue.items[trackId] ?: return@runCatching null
    val after = afterTrackId?.let { queue.items[it] }
    queueFrom(
        companionXml(
            "/playQueues/${queue.id}/items/$place/move",
            if (after != null) listOf("after" to after) else emptyList(),
            HttpMethod.Put,
        )
    )
}.getOrNull()

/** Drop a track's place from the queue. */
suspend fun PlexClient.removePlayQueueItem(queue: PlexQueue, trackId: String): PlexQueue? = runCatching {
    val place = queue.items[trackId] ?: return@runCatching null
    queueFrom(companionXml("/playQueues/${queue.id}/items/$place", method = HttpMethod.Delete))
}.getOrNull()

/** Append [trackIds], or put them next when [next]. */
suspend fun PlexClient.addToPlayQueue(queue: PlexQueue, trackIds: List<String>, next: Boolean): PlexQueue? = runCatching {
    if (trackIds.isEmpty()) return@runCatching null
    val mid = machineId().ifBlank { return@runCatching null }
    queueFrom(
        companionXml(
            "/playQueues/${queue.id}",
            listOf("uri" to itemUri(trackIds, mid)) + if (next) listOf("next" to "1") else emptyList(),
            HttpMethod.Put,
        )
    )
}.getOrNull()

/** The queue id, version and item places, out of any play-queue answer. */
private fun queueFrom(body: String): PlexQueue? {
    val container = plexXmlElements(body, "MediaContainer").firstOrNull() ?: return null
    val id = container["playQueueID"] ?: return null
    val items = plexXmlElements(body, "Track").mapNotNull { t ->
        val rating = t["ratingKey"] ?: return@mapNotNull null
        val place = t["playQueueItemID"] ?: return@mapNotNull null
        rating to place
    }.toMap()
    return PlexQueue(id, items, container["playQueueVersion"])
}

/**
 * Whether the player at this address really is the one we mean. A device
 * listed by plex.tv is named by whatever connection the account has on
 * file, and a DHCP lease that moved would have us commanding whatever
 * answers there now. GDM replies carry their own identity.
 */
suspend fun PlexClient.playerIsWhoWeThink(target: PlexPlayer): Boolean = runCatching {
    val reported = plexXmlElements(companionXml("/resources", target = target), "Player")
        .firstNotNullOfOrNull { it["machineIdentifier"] }
    if (reported == null) {
        // Nothing said either way. Not proof of an impostor, and refusing
        // on silence would be worse than the risk.
        Log.i(TAG, "${target.name} didn't say who it is; going ahead")
        return@runCatching true
    }
    (reported == target.id).also {
        if (!it) Log.i(TAG, "${target.directUrl} is $reported, not ${target.id}")
    }
}.getOrElse {
    Log.i(TAG, "couldn't check who ${target.name} is: ${it::class.simpleName}")
    false
}

/** Tell [target] its play queue changed underneath it. */
suspend fun PlexClient.companionRefreshQueue(target: PlexPlayer, queue: PlexQueue, commandId: Int): Boolean =
    playerCommand(
        target,
        "/player/playback/refreshPlayQueue",
        commandId,
        listOfNotNull("playQueueID" to queue.id, queue.version?.let { "playQueueVersion" to it }),
    )

/**
 * Ask [target] to push its timeline to us at [port] (see
 * PlexCompanionListener) instead of being asked every second. Polling
 * remains the fallback for a player that won't subscribe.
 */
suspend fun PlexClient.companionSubscribe(target: PlexPlayer, port: Int, commandId: Int): Boolean =
    playerCommand(
        target,
        "/player/timeline/subscribe",
        commandId,
        listOf("port" to port.toString(), "protocol" to "http"),
        music = false,
    )

/** Stop the pushes started by [companionSubscribe]. */
suspend fun PlexClient.companionUnsubscribe(target: PlexPlayer, commandId: Int): Boolean =
    playerCommand(target, "/player/timeline/unsubscribe", commandId, music = false)

/** Start [queue] on [target] at [startId]/[offsetMs]. True when the player took it. */
suspend fun PlexClient.companionPlay(
    target: PlexPlayer,
    queue: PlexQueue,
    startId: String,
    offsetMs: Long,
    commandId: Int,
    window: Int,
): Boolean {
    val server = runCatching { URI(baseUrl) }.getOrElse {
        Log.i(TAG, "playMedia refused: ${it::class.simpleName}")
        return false
    }
    return playerCommand(
        target,
        "/player/playback/playMedia",
        commandId,
        listOf(
            "key" to "/library/metadata/$startId",
            "offset" to offsetMs.coerceAtLeast(0L).toString(),
            "machineIdentifier" to machineId(),
            "protocol" to (server.scheme ?: "http"),
            "address" to server.host.orEmpty(),
            "port" to (if (server.port > 0) server.port else if (server.scheme == "https") 443 else 32400).toString(),
            // The window the queue was pushed with: a smaller one here and
            // the player re-reads less of the queue than we pushed.
            "containerKey" to "/playQueues/${queue.id}?window=$window&own=1",
            "token" to token,
        ),
        music = false,
    )
}

/**
 * Jump to a track *within the queue the player already holds*. Not
 * [companionPlay]: that hands the player a queue to fetch, and pulling a
 * hundred-item container over the WAN takes long enough that the command
 * times out.
 */
suspend fun PlexClient.companionSkipTo(target: PlexPlayer, trackId: String, commandId: Int): Boolean =
    playerCommand(target, "/player/playback/skipTo", commandId, listOf("key" to "/library/metadata/$trackId"))

/** A plain transport command: `play`, `pause`, `stop`, `skipNext`, `skipPrevious`. */
suspend fun PlexClient.companionCommand(target: PlexPlayer, command: String, commandId: Int): Boolean =
    playerCommand(target, "/player/playback/$command", commandId)

suspend fun PlexClient.companionSeek(target: PlexPlayer, ms: Long, commandId: Int): Boolean =
    playerCommand(target, "/player/playback/seekTo", commandId, listOf("offset" to ms.coerceAtLeast(0L).toString()))

/** `volume` 0–100 and/or `repeat` 0 off / 1 track / 2 queue — the player keeps what it understands. */
suspend fun PlexClient.companionSetParameters(target: PlexPlayer, params: List<Pair<String, String>>, commandId: Int): Boolean =
    playerCommand(target, "/player/playback/setParameters", commandId, params)

/** The player's music timeline, or null when it can't be asked. */
suspend fun PlexClient.companionTimeline(target: PlexPlayer, commandId: Int): PlexPlayerTimeline? = runCatching {
    plexTimelineFrom(
        companionXml(
            "/player/timeline/poll",
            listOf("wait" to "0", "commandID" to commandId.toString()),
            target = target,
        )
    )
}.getOrNull()

/**
 * A command to [target]: true when the player took it, and a refusal logged.
 * Each carries its [commandId], and a playback command names the `music`
 * timeline it acts on.
 */
private suspend fun PlexClient.playerCommand(
    target: PlexPlayer,
    path: String,
    commandId: Int,
    params: List<Pair<String, String>> = emptyList(),
    music: Boolean = true,
): Boolean = runCatching {
    val typed = if (music) params + ("type" to "music") else params
    companionXml(path, typed + ("commandID" to commandId.toString()), target = target)
    true
}.getOrElse {
    Log.i(TAG, "${path.substringAfterLast('/')} refused: ${it::class.simpleName}")
    false
}

/**
 * A Companion request: to the player itself when one is named, to the
 * server otherwise (the client list and play queues are the server's).
 */
private suspend fun PlexClient.companionXml(
    path: String,
    params: List<Pair<String, String>> = emptyList(),
    method: HttpMethod = HttpMethod.Get,
    target: PlexPlayer? = null,
): String {
    val host = target?.directUrl ?: baseUrl
    // XML on purpose: a player answers XML whatever is asked for, so asking
    // for it everywhere keeps one parser.
    suspend fun send() = request(path, params, method, host = host, accept = "application/xml") {
        if (target != null) {
            header("X-Plex-Target-Client-Identifier", target.id)
            // A player answering for itself refuses an unknown controller with
            // no provides header before the command is read.
            header("X-Plex-Provides", "controller")
        }
    }
    // A player answers one caller at a time. The poll runs every second, so
    // without this a command lands on top of it, the socket wedges, and
    // enough of those in a row take the player down altogether.
    val response = if (target != null) playerLock.withLock { send() } else send()
    if (!response.status.isSuccess()) {
        // The server says why in the body.
        val why = runCatching { response.bodyAsText() }.getOrDefault("").take(300).replace('\n', ' ')
        throw PlexException("Plex says ${response.status.value} for $path at $host — $why")
    }
    return response.bodyAsText()
}

private const val TAG = "AmpPlex"

private const val PLEX_TV = "https://plex.tv"

/** Where a Plex player listens for Companion commands. */
private const val COMPANION_PORT = 32500

/**
 * A Companion-capable player, from the server's list, the account's or GDM.
 *
 * Commands go to [directUrl], the player's own Companion port, never through
 * the server: the server answers 404 for a player it has not discovered itself,
 * such as the Apple TV app, which registers with plex.tv instead. So a player
 * is listed only when it can be reached directly.
 */
data class PlexPlayer(
    /** The player's own client identifier — the command target. */
    val id: String,
    val name: String,
    /** `http://host:32500`, the player's own door. */
    val directUrl: String,
    /**
     * Whether the device at [directUrl] said who it was.
     *
     * True for GDM (the reply carries its identity) and for the server's own
     * client list (the server discovered it). False for a device named only by
     * plex.tv, whose address is whatever the account has on file — that one is
     * asked before it is handed any audio.
     */
    val identityKnown: Boolean = false,
)

/**
 * A server-side play queue.
 *
 * [items] maps a track id to its *place* in this queue (`playQueueItemID`),
 * which is what the edit endpoints address — the same track appearing twice
 * has two of them, so the queue is edited by place, not by song.
 */
data class PlexQueue(
    val id: String,
    val items: Map<String, String> = emptyMap(),
    /**
     * The queue's version, which Plex bumps on every edit. Sent with a refresh
     * so a player re-reads the version we mean rather than whichever it
     * happens to fetch — two edits in quick succession are otherwise a race.
     */
    val version: String? = null,
)

/** What a player reports about its music playback. */
data class PlexPlayerTimeline(
    /** `playing`, `paused`, `stopped` or `buffering`. */
    val state: String,
    val timeMs: Long,
    val durationMs: Long,
    /** The playing track, in the server's terms — Amp's Plex track id. */
    val ratingKey: String?,
    /** 0–100 where the player reports one; null where volume isn't its to control. */
    val volume: Int?,
    /** The player's repeat: 0 off, 1 the track, 2 the queue. Null when unsaid. */
    val repeat: Int?,
    /** Whether the player is shuffling. Null when unsaid. */
    val shuffle: Boolean?,
    /**
     * What the player says it accepts — `playPause,stop,volume,seekTo,…` —
     * or null where it doesn't say. The player's own word on its capabilities,
     * which beats guessing from what a number does after we push it.
     */
    val controllable: String?,
)

/**
 * The attributes of every `<tag …>` in [xml], in document order.
 *
 * Companion bodies are flat attribute lists — the shape DlnaCast already
 * parses by hand for UPnP — so a full XML parser buys nothing here.
 */
internal fun plexXmlElements(xml: String, tag: String): List<Map<String, String>> {
    val out = mutableListOf<Map<String, String>>()
    var from = 0
    while (true) {
        val at = xml.indexOf("<$tag", from).takeIf { it >= 0 } ?: break
        // The character after the tag name has to end it, or "Timeline"
        // would also match "TimelineEntry".
        val after = xml.getOrNull(at + tag.length + 1)
        val end = xml.indexOf('>', at).takeIf { it >= 0 } ?: break
        if (after == null || after == ' ' || after == '>' || after == '/' || after == '\n' || after == '\t') {
            val attrs = ATTR.findAll(xml.substring(at, end)).associate {
                it.groupValues[1] to unescapeXml(it.groupValues[2])
            }
            out += attrs
        }
        from = end + 1
    }
    return out
}

private val ATTR = Regex("""([A-Za-z0-9_:-]+)="([^"]*)"""")

private fun unescapeXml(s: String): String = s
    .replace("&lt;", "<").replace("&gt;", ">")
    .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
    .replace("&amp;", "&")

/** The music `<Timeline>` out of a `/player/timeline/poll` body, if any. */
internal fun plexTimelineFrom(body: String): PlexPlayerTimeline? {
    val music = plexXmlElements(body, "Timeline").firstOrNull { it["type"] == "music" } ?: return null
    return PlexPlayerTimeline(
        state = music["state"] ?: "stopped",
        timeMs = music["time"]?.toLongOrNull() ?: 0L,
        durationMs = music["duration"]?.toLongOrNull() ?: 0L,
        ratingKey = music["ratingKey"]?.takeIf { it.isNotBlank() },
        volume = music["volume"]?.toIntOrNull(),
        controllable = music["controllable"]?.takeIf { it.isNotBlank() },
        repeat = music["repeat"]?.toIntOrNull(),
        shuffle = music["shuffle"]?.let { it == "1" || it.equals("true", ignoreCase = true) },
    )
}
