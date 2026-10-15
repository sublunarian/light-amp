package com.sublunar.amp.data

import com.sublunar.amp.data.db.TrackRow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * One whole read of the tracks table: the songs in storage (rowid) order, and
 * the rowid each was read at, which is what the next read matches against.
 */
class TrackGeneration(val rowIds: LongArray, val tracks: List<Track>) {
    val size: Int get() = tracks.size

    companion object {
        val EMPTY = TrackGeneration(LongArray(0), emptyList())
    }
}

/**
 * The next read of the tracks table, built row by row against the one before.
 *
 * Every read used to make a new [Track] and new Strings for every row: a whole
 * second library for each song played, liked or synced, alive until everything
 * holding the last list let go of it. A row at the same rowid with the same
 * content is the same song, so it gets the same object back, and a read after
 * a write holds new objects only for the rows that write touched.
 *
 * Rows that are new have their per-album values — what every song of a record
 * repeats — shared with the rows read alongside them, or with the row they
 * replace, so an album's songs hold one String each rather than one per song.
 *
 * Rows must arrive in rowid order, as [forEachTrackPage] reads them.
 */
class TrackRead(private val previous: TrackGeneration, expected: Int) {
    private var rowIds = LongArray(expected.coerceAtLeast(16))
    private val tracks = ArrayList<Track>(expected.coerceAtLeast(0))
    private var cursor = 0
    private val strings = HashMap<String, String>()
    private val years = HashMap<Int, Int?>()

    /** How many rows came back as the previous read's own object. */
    var reused = 0
        private set

    fun add(rowId: Long, row: Track) {
        val n = tracks.size
        // The pages are keyed on rowid, so a row can't come twice; if one ever
        // did, keeping the first is what keeps the list free of duplicates.
        if (n > 0 && rowId <= rowIds[n - 1]) return
        while (cursor < previous.size && previous.rowIds[cursor] < rowId) cursor++
        val before = if (cursor < previous.size && previous.rowIds[cursor] == rowId) previous.tracks[cursor] else null
        val track = if (before != null && before == row) before.also { reused++ } else shared(row, before)
        if (n == rowIds.size) rowIds = rowIds.copyOf(n * 2)
        rowIds[n] = rowId
        tracks += track
    }

    /**
     * The read, finished. Nothing changed hands back the previous read itself,
     * so the list everything downstream holds is the one it already had.
     */
    fun finish(): TrackGeneration {
        val n = tracks.size
        if (n == previous.size && reused == n) return previous
        return TrackGeneration(if (rowIds.size == n) rowIds else rowIds.copyOf(n), tracks)
    }

    private fun shared(row: Track, before: Track?): Track = row.copy(
        artist = canonical(row.artist, before?.artist),
        album = canonical(row.album, before?.album),
        albumArtist = canonical(row.albumArtist, before?.albumArtist),
        albumId = row.albumId?.let { canonical(it, before?.albumId) },
        coverArtId = row.coverArtId?.let { canonical(it, before?.coverArtId) },
        genre = canonical(row.genre, before?.genre),
        composer = canonical(row.composer, before?.composer),
        year = year(row, before),
    )

    private fun canonical(value: String, before: String?): String =
        strings.getOrPut(value) { before?.takeIf { it == value } ?: value }

    // A year is boxed, and past the small numbers the JVM keeps one box of,
    // so without this each song carries a box of its own.
    private fun year(row: Track, before: Track?): Int? {
        val year = row.year ?: return null
        return years.getOrPut(year) { if (before != null && before.year == year) before.year else row.year }
    }
}

/**
 * Every row of the tracks table, [pageSize] at a time, in rowid order.
 *
 * Keyed on the last rowid seen rather than an offset, so each page starts
 * exactly where the one before ended: no row twice, none skipped, wherever the
 * boundaries fall. Stops between pages once its caller is cancelled — a source
 * switch, or a newer read asked for — instead of finishing a read nobody wants.
 */
suspend fun forEachTrackPage(
    pageSize: Int,
    page: suspend (after: Long, limit: Int) -> List<TrackRow>,
    consume: (List<TrackRow>) -> Unit,
) {
    var after = Long.MIN_VALUE
    while (true) {
        currentCoroutineContext().ensureActive()
        val rows = page(after, pageSize)
        if (rows.isEmpty()) return
        consume(rows)
        if (rows.size < pageSize) return
        after = rows.last().rowId
    }
}
