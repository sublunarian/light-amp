package com.sublunar.amp.data

/** What the simplified layout's library home can list. Stored by name: never rename one. */
enum class HomeCategory(val title: String, val onByDefault: Boolean) {
    PLAYLISTS("Playlists", true),
    ARTISTS("Artists", true),
    ALBUMS("Albums", true),
    SONGS("Songs", true),
    GENRES("Genres", false),
    COMPILATIONS("Compilations", false),
    COMPOSERS("Composers", false),
    FAVORITES("Favorites", false),
    DOWNLOADED("Downloaded", false),
}

/**
 * The library home's categories in the order chosen, each shown or not. [order]
 * holds every category exactly once, so one switched off keeps its place for
 * when it is switched back on.
 */
data class HomeLayout(val order: List<HomeCategory>, val shown: Set<HomeCategory>) {

    /** What the home list shows, in its order. */
    val listed: List<HomeCategory> get() = order.filter { it in shown }

    fun toggled(category: HomeCategory): HomeLayout =
        copy(shown = if (category in shown) shown - category else shown + category)

    /** [category] taken out and put back at [index] of the order. */
    fun moved(category: HomeCategory, index: Int): HomeLayout {
        val rest = order - category
        return copy(order = rest.toMutableList().apply { add(index.coerceIn(0, rest.size), category) })
    }

    /** Every category in order, a switched-off one marked with a leading `-`: `PLAYLISTS,-GENRES,…`. */
    fun encode(): String = order.joinToString(",") { if (it in shown) it.name else "-${it.name}" }

    companion object {
        val DEFAULT = HomeLayout(HomeCategory.entries, HomeCategory.entries.filterTo(mutableSetOf()) { it.onByDefault })

        /**
         * [encode]'s text read back. A name this build doesn't know is skipped and a
         * repeat counts once; a category the text doesn't name (one added since it
         * was written) goes at the end, as its default has it.
         */
        fun decode(raw: String): HomeLayout {
            if (raw.isBlank()) return DEFAULT
            val order = mutableListOf<HomeCategory>()
            val shown = mutableSetOf<HomeCategory>()
            for (part in raw.split(',')) {
                val name = part.trim()
                val on = !name.startsWith("-")
                val category = HomeCategory.entries.firstOrNull { it.name == name.removePrefix("-") } ?: continue
                if (category in order) continue
                order += category
                if (on) shown += category
            }
            for (category in HomeCategory.entries) {
                if (category in order) continue
                order += category
                if (category.onByDefault) shown += category
            }
            return HomeLayout(order, shown)
        }
    }
}
