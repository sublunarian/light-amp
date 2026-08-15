package com.sublunar.amp.data

import java.text.Normalizer
import kotlin.random.Random

/**
 * List ordering for the library tabs.
 *
 * Each sort key has a "natural" direction — names read A→Z, dates and play counts
 * read newest/most first — and the persisted `reversed` flag flips it. That way
 * re-tapping the selected option in the sort menu always visibly inverts the
 * list, whichever key it is.
 */
val AlbumSort.descendingByNature: Boolean
    get() = when (this) {
        AlbumSort.TITLE, AlbumSort.ARTIST, AlbumSort.RANDOM -> false
        AlbumSort.YEAR, AlbumSort.DATE_ADDED, AlbumSort.RECENTLY_PLAYED,
        AlbumSort.MOST_PLAYED, AlbumSort.RATING -> true
    }

val SongSort.descendingByNature: Boolean
    get() = when (this) {
        SongSort.TITLE, SongSort.ARTIST -> false
        SongSort.DATE_ADDED, SongSort.RECENTLY_PLAYED,
        SongSort.MOST_PLAYED, SongSort.RATING -> true
    }

val ArtistSort.descendingByNature: Boolean
    get() = when (this) {
        ArtistSort.NAME -> false
        ArtistSort.MOST_PLAYED -> true
    }

val PlaylistSort.descendingByNature: Boolean
    get() = when (this) {
        PlaylistSort.NAME -> false
        PlaylistSort.DATE_CREATED, PlaylistSort.RECENTLY_UPDATED -> true
    }

val TagSort.descendingByNature: Boolean
    get() = when (this) {
        TagSort.NAME -> false
        TagSort.SONGS -> true
    }

/**
 * Order a list of tag values — genres, composers.
 *
 * [counts] is a function rather than a map so the name order costs nothing to
 * produce: counting walks every track in the library, and it is only the other
 * order that needs it.
 */
fun sortTags(
    tags: List<String>,
    sort: TagSort,
    reversed: Boolean = false,
    counts: () -> Map<String, Int>,
): List<String> {
    val ordered = when (sort) {
        TagSort.NAME -> tags.sortedBy { nameKey(it) }
        // Ties fall back to the name, so equally-sized genres don't trade places
        // between visits.
        TagSort.SONGS -> counts().let { byTag ->
            tags.sortedWith(
                compareByDescending<String> { byTag[it] ?: 0 }.thenBy { nameKey(it) },
            )
        }
    }
    return if (reversed) ordered.reversed() else ordered
}

/**
 * Leading articles ignored when sorting names, so "The Beatles" files under B.
 * Matches Navidrome's own `IgnoredArticles` default, keeping the app's ordering
 * consistent with the server's.
 */
private val IGNORED_ARTICLES = listOf("the", "el", "la", "los", "las", "le", "les")

/**
 * Latin letters that carry no Unicode decomposition, so NFD can't fold them —
 * a stroked or ligature letter is one codepoint with no combining mark to strip.
 * Without this, "Ólafur" folds fine but "Øystein" or "Æther" would still sort
 * past Z and land in the index's "#" bucket.
 */
private val FOLD_EXTRAS = mapOf(
    'ø' to "o", 'œ' to "oe", 'æ' to "ae", 'ß' to "ss", 'ł' to "l", 'đ' to "d",
    'ð' to "d", 'þ' to "th", 'ħ' to "h", 'ŧ' to "t", 'ı' to "i", 'ĸ' to "k",
    'ŋ' to "n", 'ə' to "e", 'ʻ' to "", 'ʼ' to "", '’' to "'",
)

private val COMBINING_MARKS = Regex("\\p{Mn}+")

/**
 * Case-insensitive sort key with accents folded onto their base letter, so
 * "Ólafur Arnalds" files under O and "Éthiopiques" under E rather than sorting
 * past Z. Decomposing to NFD and dropping the combining marks handles the bulk
 * of it; [FOLD_EXTRAS] covers the letters that don't decompose.
 *
 * Non-Latin scripts are deliberately left alone — they have no sensible A–Z
 * home and belong in the "#" bucket.
 */
fun titleKey(name: String): String {
    val lower = name.trim().lowercase()
    // Fast path: nothing outside ASCII can need folding, and almost every title is
    // plain ASCII. Skipping the normaliser and the regex here matters because this
    // sits under the library sorts.
    if (lower.all { it.code < 0x80 }) return lower
    val decomposed = Normalizer.normalize(lower, Normalizer.Form.NFD)
    val stripped = COMBINING_MARKS.replace(decomposed, "")
    if (stripped.none { it in FOLD_EXTRAS }) return stripped
    return buildString(stripped.length) {
        stripped.forEach { c -> append(FOLD_EXTRAS[c] ?: c.toString()) }
    }
}

/**
 * A sort key that files anything not starting with a letter at the end.
 *
 * Lexicographically, digits and punctuation come *before* letters — so "2112"
 * and "[Led Zeppelin IV]" opened every list, while the A–Z strip puts "#" after
 * Z and a tap on it jumped backwards. This prefixes the bucket, so the list
 * agrees with the strip beside it.
 */
fun nameKey(name: String): String = bucketed(titleKey(name))

/** [nameKey] over [sortName], for the lists that file "The Beatles" under B. */
fun sortNameKey(name: String): String = bucketed(sortName(name))

private fun bucketed(key: String): String =
    if (key.firstOrNull()?.isLetter() == true) "0$key" else "1$key"

/** [titleKey] with any leading article dropped, so "The Beatles" files under B. */
fun sortName(name: String): String {
    val lower = titleKey(name)
    for (article in IGNORED_ARTICLES) {
        val prefix = "$article "
        if (lower.startsWith(prefix)) {
            val rest = lower.removePrefix(prefix).trim()
            // Don't reduce a name that is *only* an article to nothing.
            if (rest.isNotEmpty()) return rest
        }
    }
    return lower
}


/**
 * Sort by a key computed **once per element**.
 *
 * `sortedBy { }` evaluates its selector inside the comparator, so an O(n log n)
 * sort calls it hundreds of thousands of times on a library this size. With
 * [titleKey] — Unicode normalisation plus a regex — that was enough to hang the
 * main thread and trip "Amp isn't responding". Decorating first makes it n.
 */
private inline fun <T, K : Comparable<K>> List<T>.sortedByKeyOnce(
    selector: (T) -> K,
): List<T> = map { selector(it) to it }.sortedBy { it.first }.map { it.second }

/** As [sortedByKeyOnce], for a primary key with tie-breakers. */
private inline fun <T> List<T>.sortedByKeysOnce(
    crossinline primary: (T) -> String,
    crossinline secondary: (T) -> Long,
    crossinline tertiary: (T) -> String,
): List<T> = map { Triple(primary(it), secondary(it), tertiary(it)) to it }
    .sortedWith(
        compareBy({ it.first.first }, { it.first.second }, { it.first.third }),
    )
    .map { it.second }


/**
 * Descending numeric key, ties broken by a string key — both computed once.
 *
 * The tie-breaker matters more than it looks: sorting by play count or date added
 * leaves most pairs tied (nearly everything has zero plays), so a `thenBy { }`
 * lambda ends up running on almost every comparison rather than rarely.
 */
private inline fun <T> List<T>.sortedByDescendingThenKey(
    crossinline primary: (T) -> Long,
    crossinline key: (T) -> String,
): List<T> = map { Triple(primary(it), key(it), it) }
    .sortedWith(compareByDescending<Triple<Long, String, T>> { it.first }.thenBy { it.second })
    .map { it.third }

fun sortAlbums(albums: List<Album>, sort: AlbumSort, reversed: Boolean = false): List<Album> {
    val ordered = when (sort) {
        AlbumSort.TITLE -> albums.sortedByKeyOnce { nameKey(it.title) }
        AlbumSort.ARTIST -> albums.sortedByKeysOnce(
            primary = { sortNameKey(it.artist) },
            secondary = { (it.year ?: 0).toLong() },
            tertiary = { nameKey(it.title) },
        )
        // Full release date where the server provides one, so releases from the
        // same year still order correctly.
        AlbumSort.YEAR -> albums.sortedByDescendingThenKey(
            primary = { if (it.releaseDate != 0L) it.releaseDate else (it.year ?: 0) * 10_000L },
            key = { nameKey(it.title) },
        )
        AlbumSort.DATE_ADDED -> albums.sortedByDescendingThenKey(
            primary = { it.createdMs },
            key = { nameKey(it.title) },
        )
        AlbumSort.RECENTLY_PLAYED -> albums.sortedByDescendingThenKey(
            primary = { it.lastPlayedMs },
            key = { nameKey(it.title) },
        )
        AlbumSort.MOST_PLAYED -> albums.sortedByDescendingThenKey(
            primary = { it.playCount.toLong() },
            key = { nameKey(it.title) },
        )
        // Highest first, and unrated (0) falls to the bottom on its own.
        AlbumSort.RATING -> albums.sortedByDescendingThenKey(
            primary = { it.rating.toLong() },
            key = { nameKey(it.title) },
        )
        // Shuffled by a seed that only changes when the library does, so the
        // order is stable while you browse it — a fresh shuffle on every
        // recomposition would move rows under a scrolling finger.
        AlbumSort.RANDOM -> albums.shuffled(Random(albums.size.toLong() * 31 + albums.firstOrNull()?.id.hashCode()))
    }
    return if (reversed) ordered.reversed() else ordered
}

fun sortSongs(tracks: List<Track>, sort: SongSort, reversed: Boolean = false): List<Track> {
    val ordered = when (sort) {
        SongSort.TITLE -> tracks.sortedByKeyOnce { nameKey(it.title) }
        SongSort.ARTIST -> tracks
            .map { Triple(sortNameKey(it.artist), nameKey(it.album), it) }
            .sortedWith(
                compareBy(
                    { it.first },
                    { it.second },
                    { it.third.discNumber ?: 0 },
                    { it.third.trackNumber ?: 0 },
                ),
            )
            .map { it.third }
        SongSort.RECENTLY_PLAYED -> tracks.sortedByDescendingThenKey(
            primary = { it.lastPlayedMs },
            key = { nameKey(it.title) },
        )
        SongSort.MOST_PLAYED -> tracks.sortedByDescendingThenKey(
            primary = { it.playCount.toLong() },
            key = { nameKey(it.title) },
        )
        SongSort.RATING -> tracks.sortedByDescendingThenKey(
            primary = { it.rating.toLong() },
            key = { nameKey(it.title) },
        )
        // Songs carry no per-track "added" date of their own; the album they
        // belong to does, so fall back to album grouping for a stable order.
        SongSort.DATE_ADDED -> tracks
            .map { nameKey(it.album) to it }
            .sortedWith(
                compareBy(
                    { it.first },
                    { it.second.discNumber ?: 0 },
                    { it.second.trackNumber ?: 0 },
                ),
            )
            .map { it.second }
    }
    return if (reversed) ordered.reversed() else ordered
}

fun sortArtists(artists: List<Artist>, sort: ArtistSort, reversed: Boolean = false): List<Artist> {
    val ordered = when (sort) {
        ArtistSort.NAME -> artists.sortedByKeyOnce { sortNameKey(it.name) }
        ArtistSort.MOST_PLAYED -> artists.sortedWith(
            compareByDescending<Artist> { it.playCount }.thenBy { sortNameKey(it.name) }
        )
    }
    return if (reversed) ordered.reversed() else ordered
}

fun sortPlaylists(
    playlists: List<Playlist>,
    sort: PlaylistSort,
    reversed: Boolean = false,
): List<Playlist> {
    val ordered = when (sort) {
        PlaylistSort.NAME -> playlists.sortedByKeyOnce { nameKey(it.name) }
        PlaylistSort.DATE_CREATED -> playlists.sortedByDescendingThenKey(
            primary = { it.createdAt },
            key = { nameKey(it.name) },
        )
        PlaylistSort.RECENTLY_UPDATED -> playlists.sortedByDescendingThenKey(
            primary = { it.updatedAt },
            key = { nameKey(it.name) },
        )
    }
    return if (reversed) ordered.reversed() else ordered
}

fun shuffled(tracks: List<Track>): List<Track> = tracks.shuffled()
