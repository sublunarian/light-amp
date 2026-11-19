package com.sublunar.amp.data

import java.text.Normalizer

/**
 * Each sort key's natural direction — names A→Z, dates and ratings newest or
 * highest first. The persisted `reversed` flag flips it, so re-tapping the selected
 * option always visibly inverts the list.
 */
val AlbumSort.descendingByNature: Boolean
    get() = when (this) {
        AlbumSort.TITLE, AlbumSort.ARTIST -> false
        AlbumSort.YEAR, AlbumSort.DATE_ADDED, AlbumSort.RECENTLY_PLAYED, AlbumSort.RATING -> true
    }

val SongSort.descendingByNature: Boolean
    get() = when (this) {
        SongSort.TITLE, SongSort.ARTIST -> false
        SongSort.DATE_ADDED, SongSort.RECENTLY_PLAYED, SongSort.RATING -> true
    }

val PlaylistSort.descendingByNature: Boolean
    get() = when (this) {
        PlaylistSort.NAME, PlaylistSort.TYPE -> false
        PlaylistSort.DATE_CREATED, PlaylistSort.RECENTLY_PLAYED, PlaylistSort.RECENTLY_UPDATED -> true
    }

/** Leading articles ignored when sorting names, after Navidrome's `IgnoredArticles`. */
private val IGNORED_ARTICLES = listOf("the", "el", "la", "los", "las", "le", "les")

/**
 * Latin letters with no Unicode decomposition (stroked or ligature letters), which
 * NFD can't fold: without this "Øystein" or "Æther" sorts past Z, into "#".
 */
private val FOLD_EXTRAS = mapOf(
    'ø' to "o", 'œ' to "oe", 'æ' to "ae", 'ß' to "ss", 'ł' to "l", 'đ' to "d",
    'ð' to "d", 'þ' to "th", 'ħ' to "h", 'ŧ' to "t", 'ı' to "i", 'ĸ' to "k",
    'ŋ' to "n", 'ə' to "e", 'ʻ' to "", 'ʼ' to "", '’' to "'",
)

private val COMBINING_MARKS = Regex("\\p{Mn}+")

/**
 * Case-insensitive sort key with accents folded onto their base letter, so
 * "Ólafur Arnalds" files under O. NFD plus dropping combining marks does most of
 * it; [FOLD_EXTRAS] covers the rest. Non-Latin scripts are left alone: they have
 * no A–Z home and belong in "#".
 */
fun titleKey(name: String): String {
    val lower = name.trim().lowercase()
    // Almost every title is plain ASCII, and this sits under every library sort.
    if (lower.all { it.code < 0x80 }) return lower
    val decomposed = Normalizer.normalize(lower, Normalizer.Form.NFD)
    val stripped = COMBINING_MARKS.replace(decomposed, "")
    if (stripped.none { it in FOLD_EXTRAS }) return stripped
    return buildString(stripped.length) {
        stripped.forEach { c -> append(FOLD_EXTRAS[c] ?: c.toString()) }
    }
}

/**
 * A sort key that files anything not starting with a letter at the end, where
 * the A–Z strip puts "#" — plain string order would put digits and punctuation first.
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
 * Sort by a key computed **once per element**. `sortedBy { }` runs its selector
 * inside the comparator, n log n times; with [titleKey] (normalisation plus a
 * regex) on a large library that hangs the main thread into an ANR.
 */
internal inline fun <T, K : Comparable<K>> List<T>.sortedByKeyOnce(
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
 * Descending numeric key, ties broken by a string key — both computed once. Most
 * pairs tie (nearly everything has zero plays), so a `thenBy { }` would run on
 * almost every comparison.
 */
private inline fun <T> List<T>.sortedByDescendingThenKey(
    crossinline primary: (T) -> Long,
    crossinline key: (T) -> String,
): List<T> = map { Triple(primary(it), key(it), it) }
    .sortedWith(compareByDescending<Triple<Long, String, T>> { it.first }.thenBy { it.second })
    .map { it.third }

fun sortAlbums(
    albums: List<Album>,
    sort: AlbumSort,
    reversed: Boolean = false,
): List<Album> {
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
        // Highest first, and unrated (0) falls to the bottom on its own.
        AlbumSort.RATING -> albums.sortedByDescendingThenKey(
            primary = { it.rating.toLong() },
            key = { nameKey(it.title) },
        )
    }
    return if (reversed) ordered.reversed() else ordered
}

fun sortSongs(
    tracks: List<Track>,
    sort: SongSort,
    reversed: Boolean = false,
    /** Each album's date added, by album id, for [SongSort.DATE_ADDED]; a song whose album isn't here sorts as oldest. */
    albumAdded: Map<String, Long> = emptyMap(),
): List<Track> {
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
        SongSort.RATING -> tracks.sortedByDescendingThenKey(
            primary = { it.rating.toLong() },
            key = { nameKey(it.title) },
        )
        // Songs carry no date added of their own, so they go by their album's:
        // newest album first, each album whole and in running order.
        SongSort.DATE_ADDED -> tracks
            .map { Triple(it.albumId?.let(albumAdded::get) ?: Long.MIN_VALUE, nameKey(it.album), it) }
            .sortedWith(
                compareByDescending<Triple<Long, String, Track>> { it.first }
                    .thenBy { it.second }
                    // Two albums of one name and date stay apart.
                    .thenBy { it.third.albumId }
                    .thenBy { it.third.discNumber ?: 0 }
                    .thenBy { it.third.trackNumber ?: 0 },
            )
            .map { it.third }
    }
    return if (reversed) ordered.reversed() else ordered
}

fun sortArtists(artists: List<Artist>, reversed: Boolean = false): List<Artist> {
    val ordered = artists.sortedByKeyOnce { sortNameKey(it.name) }
    return if (reversed) ordered.reversed() else ordered
}

fun sortPlaylists(
    playlists: List<Playlist>,
    sort: PlaylistSort,
    reversed: Boolean = false,
    /** When each was last started, by id, for [PlaylistSort.RECENTLY_PLAYED]; one never started sorts last. */
    plays: Map<String, Long> = emptyMap(),
): List<Playlist> {
    val ordered = when (sort) {
        PlaylistSort.TYPE -> {
            // Reversing swaps the groups alone: the titles in each still read A–Z.
            val (smart, normal) = playlists.sortedByKeyOnce { nameKey(it.name) }.partition { it.smart }
            return if (reversed) normal + smart else smart + normal
        }
        PlaylistSort.NAME -> playlists.sortedByKeyOnce { nameKey(it.name) }
        PlaylistSort.DATE_CREATED -> playlists.sortedByDescendingThenKey(
            primary = { it.createdAt },
            key = { nameKey(it.name) },
        )
        PlaylistSort.RECENTLY_PLAYED -> playlists.sortedByDescendingThenKey(
            primary = { plays[it.id] ?: 0L },
            key = { nameKey(it.name) },
        )
        PlaylistSort.RECENTLY_UPDATED -> playlists.sortedByDescendingThenKey(
            primary = { it.updatedAt },
            key = { nameKey(it.name) },
        )
    }
    return if (reversed) ordered.reversed() else ordered
}

/** Whether [PlaylistSort.TYPE] has anything to tell apart: smart playlists and normal ones both. */
fun hasPlaylistTypes(playlists: List<Playlist>): Boolean =
    playlists.any { it.smart } && playlists.any { !it.smart }

fun shuffled(tracks: List<Track>): List<Track> = tracks.shuffled()
