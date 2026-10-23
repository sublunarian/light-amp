package com.sublunar.amp.data

import java.io.File

/**
 * Track (and album) ids that name a file on this phone, whichever source made
 * them.
 *
 * The id is the file's own path behind [ID_PREFIX], so it is stable across
 * rescans without a database of its own, and playback can turn any track back
 * into a file without a lookup. Core rather than the phone library's own,
 * because playback, covers and downloads all need to tell such a track from a
 * server's, whatever source is being browsed.
 *
 * Stored: these ids are in every phone library's database, in the saved queue
 * and in the m3u8 playlists' mapping, so the prefix never changes.
 */
object PhoneFiles {

    /** Marks an id as a path on this phone rather than something from a server. */
    const val ID_PREFIX = "file:"

    fun idFor(file: File): String = ID_PREFIX + file.absolutePath

    fun pathOf(id: String): String? =
        if (id.startsWith(ID_PREFIX)) id.removePrefix(ID_PREFIX) else null

    fun fileOf(id: String): File? = pathOf(id)?.let(::File)?.takeIf { it.isFile }

    fun isPhoneFile(id: String): Boolean = id.startsWith(ID_PREFIX)
}
