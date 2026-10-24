package com.sublunar.amp.data

import com.sublunar.amp.data.db.AlbumEntity
import com.sublunar.amp.data.db.TrackEntity

/** The albums a sync reads the songs of; why, counted; and how many had nothing to compare and were left alone. */
internal class SongFetch(val albums: List<Album>, val reasons: Map<String, Int>, val untouched: Int)

/**
 * Which of [serverAlbums] a sync reads the songs of: on a [refill] all of them,
 * otherwise only new or changed albums — and "changed" includes "its songs
 * aren't here": albums are written before their songs, so an interrupted sync
 * leaves complete-looking album rows with no tracks. The real per-album track
 * count ([cachedTrackCounts]) makes that self-heal. Then whatever [reread]
 * adds, once each.
 *
 * Why each album was chosen is counted for the log: a sync walking the whole
 * library must be told apart from one that has everything to fetch.
 */
internal fun albumsNeedingSongs(
    serverAlbums: List<Album>,
    cached: Map<String, AlbumEntity>,
    cachedTrackCounts: Map<String, Int>,
    reread: Reread?,
    refill: Boolean,
): SongFetch {
    val reasons = LinkedHashMap<String, Int>()
    var untouched = 0
    val changed = if (refill) serverAlbums else serverAlbums.filter { album ->
        val prev = cached[album.id]
        val cachedCount = cachedTrackCounts[album.id] ?: 0
        val why = when {
            prev == null -> "new album"
            cachedCount == 0 -> "no songs cached"
            // A song count of 0 means "not sent" (Plex leaves leafCount
            // off), not zero: compared as a count, every album looks
            // changed on every sync. Ask when the server last touched the
            // album instead (Plex moves updatedAt with its contents). A
            // server sending neither leaves both at 0, which keeps the
            // cache; rows from before the column read 0 and walk once.
            album.songCount == 0 ->
                if (album.updatedMs != prev.updatedMs) {
                    "album changed on the server"
                } else {
                    untouched++
                    null
                }
            prev.songCount != album.songCount -> "server's count changed"
            cachedCount != album.songCount -> "cache short of the count"
            else -> null
        }
        if (why != null) reasons[why] = (reasons[why] ?: 0) + 1
        why != null
    }
    val already = changed.mapTo(HashSet()) { it.id }
    val more = reread?.albums.orEmpty().filter { already.add(it.id) }
    if (reread != null && more.isNotEmpty()) reasons[reread.why] = more.size
    return SongFetch(changed + more, reasons, untouched)
}

/**
 * The next [size] of [ordered] after [after], and where the walk got to — null
 * once it has reached the end, so the next one starts from the beginning.
 */
internal fun nextWalk(ordered: List<String>, after: String?, size: Int): Pair<List<String>, String?> {
    val rest = if (after == null) ordered else ordered.filter { it > after }
    val batch = rest.take(size)
    return batch to if (rest.size > size) batch.last() else null
}

/** What a sync re-reads beyond new and re-counted albums, why, and the [SongCheck] to keep after. */
internal class Reread(val albums: List<Album>, val why: String, val next: SongCheck)

/**
 * Turns a server's [SongChanges] into albums to re-read.
 *
 * Only a sync of every library ([whole]) may settle a mark: one scoped to a
 * single library would mark as read the changes in the others. Re-reading
 * every album, or walking, is bulk — left for a link that allows it ([bulk]),
 * with the check kept where it was so a later sync does it instead.
 *
 * A walk that has gone round rests for [WALK_REST_MS], or a server that can't
 * say anything (or scans on a schedule, so always "may have changed") would
 * keep one going for ever.
 *
 * A server that names what changed but misses some asks for a walk as well
 * ([SongChanges.Albums.alsoWalk]): its named albums are read every sync and its
 * mark moves with them, while the walk goes on beside on its own cursor and
 * rest, settling no mark — the two share one [SongCheck] without undoing each other.
 */
internal fun rereadFor(
    changes: SongChanges?,
    check: SongCheck,
    albums: List<Album>,
    whole: Boolean,
    bulk: Boolean,
    nowMs: Long,
): Reread? {
    val settled = { mark: String -> if (whole) SongCheck(mark = mark) else check }
    return when (changes) {
        null -> null
        is SongChanges.Nothing -> Reread(emptyList(), "", settled(changes.mark))
        is SongChanges.Albums -> {
            val named = albums.filter { it.id in changes.ids }
            if (!changes.alsoWalk) return Reread(named, "server says changed", settled(changes.mark))
            val marked = if (whole) check.copy(mark = changes.mark) else check
            if (!whole || !bulk) return Reread(named, "server says changed", marked)
            val (picked, cursor) = walkOn(check, albums, nowMs)
                ?: return Reread(named, "server says changed", marked)
            val next = if (cursor == null) {
                marked.copy(walkCursor = null, walkedMs = nowMs)
            } else {
                marked.copy(walkCursor = cursor)
            }
            Reread(
                albums.filter { it.id in changes.ids || it.id in picked },
                if (named.isEmpty()) "walk" else "server says changed, and walk",
                next,
            )
        }
        is SongChanges.Everything -> when {
            !bulk -> null
            // Every album read is a walk gone round, so one that follows rests from here.
            whole -> Reread(albums, "read once, to compare from", SongCheck(mark = changes.mark, walkedMs = nowMs))
            else -> Reread(albums, "read once, to compare from", check)
        }
        is SongChanges.Unknown -> {
            if (!whole || !bulk) return null
            val (picked, cursor) = walkOn(check, albums, nowMs) ?: return Reread(emptyList(), "", check)
            // A walk covers changes up to the mark it began under; a scan
            // during the walk is caught by the next one.
            val walkMark = check.walkMark ?: changes.mark
            val next = if (cursor == null) {
                SongCheck(mark = walkMark, walkedMs = nowMs)
            } else {
                check.copy(walkMark = walkMark, walkCursor = cursor)
            }
            Reread(albums.filter { it.id in picked }, "walk", next)
        }
    }
}

/**
 * The ids of the walk's next batch from where [check] left it, and the cursor
 * to keep after — or null while a walk that has gone round rests.
 */
private fun walkOn(check: SongCheck, albums: List<Album>, nowMs: Long): Pair<Set<String>, String?>? {
    if (check.walkCursor == null && nowMs - check.walkedMs in 0 until WALK_REST_MS) return null
    val (ids, cursor) = nextWalk(albums.map { it.id }.sorted(), check.walkCursor, WALK_BATCH)
    return ids.toHashSet() to cursor
}

/**
 * Albums a walk re-reads per sync, where the server can't say which changed,
 * or can't say all of it — see [rereadFor]. A 1,400-album library goes round
 * in about fifty syncs on Wi-Fi: a day or so, for thirty small requests each.
 */
internal const val WALK_BATCH = 30

/** How long a finished walk rests before another may start — see [rereadFor]. */
internal const val WALK_REST_MS = 12 * 60 * 60 * 1000L

/**
 * Whether [fresh], an album's songs as the server has them, says anything its
 * [cached] rows don't. Compared as sets: the server's order is not the table's.
 *
 * Plays this phone counted first are not news: markPlayed stamps them here at
 * once, and the server's figures never match the stamp (its own clock, a
 * scrobble on its way), so every album just heard would be rewritten. A count
 * higher than the cached one is a play somewhere else, and is.
 */
internal fun songsChanged(cached: List<TrackEntity>, fresh: List<TrackEntity>): Boolean {
    val before = cached.associateBy { it.id }
    val heard = fresh.mapTo(HashSet()) { row ->
        val old = before[row.id]
        if (old == null || row.playCount > old.playCount) row
        else row.copy(playCount = old.playCount, lastPlayedMs = old.lastPlayedMs)
    }
    return heard != cached.toHashSet()
}
