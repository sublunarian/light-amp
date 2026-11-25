package com.sublunar.amp.data

/** A track's title split for an album page: the [work] it belongs to, if it names one, and the rest. */
data class WorkTitle(val work: String?, val title: String)

/**
 * A classical album's tracks often each begin with their work:
 * "Goldberg Variations, BWV 988: I. Aria". Where two or more tracks in a row share
 * the part before the first ": ", that part is the work, said once as a heading,
 * and each track keeps the rest. A title with a colon of its own, alone, stays whole.
 */
fun workTitles(titles: List<String>): List<WorkTitle> {
    val split = titles.map { title ->
        val at = title.indexOf(": ")
        if (at <= 0) null else title.substring(0, at).trim() to title.substring(at + 2).trim()
    }
    return titles.mapIndexed { index, title ->
        val parts = split[index]
        val shared = parts != null && parts.second.isNotEmpty() &&
            (split.getOrNull(index - 1)?.first == parts.first || split.getOrNull(index + 1)?.first == parts.first)
        if (shared) WorkTitle(parts!!.first, parts.second) else WorkTitle(null, title)
    }
}
