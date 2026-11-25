package com.sublunar.amp.data

/** A track's title split for an album page: the [work] it belongs to, if it names one, and the rest. */
data class WorkTitle(val work: String?, val title: String)

/**
 * A classical album's tracks often each begin with their work:
 * "Goldberg Variations, BWV 988: I. Aria". Where two or more tracks in a row share
 * the part before the first ": ", the album is one of those, and every title of
 * that form is split: the work, said as a heading, and the rest. A work of one
 * track (a Romance after a Concerto) so gets its own heading too. An album with no
 * shared work keeps every title whole, so a lone "Interlude: Rain" stays as it is.
 */
fun workTitles(titles: List<String>): List<WorkTitle> {
    val split = titles.map { title ->
        val at = title.indexOf(": ")
        if (at <= 0 || at + 2 >= title.length) null else title.substring(0, at).trim() to title.substring(at + 2).trim()
    }
    val works = split.indices.any { i -> split[i] != null && split.getOrNull(i + 1)?.first == split[i]!!.first }
    return titles.mapIndexed { index, title ->
        val parts = split[index]
        if (works && parts != null) WorkTitle(parts.first, parts.second) else WorkTitle(null, title)
    }
}
