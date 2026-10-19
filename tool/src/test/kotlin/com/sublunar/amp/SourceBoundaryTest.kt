package com.sublunar.amp

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * No shared file names a kind of source: what Subsonic, Plex, Jellyfin and the
 * phone's own music each do is said in that kind's own files, and everything
 * else asks its provider.
 *
 * A scan of the source text rather than of imports, because until the
 * protocols move into folders of their own most of them share the `data`
 * package with the core, and no import rule can tell the two apart there. The
 * folders' own fence replaces this once they move.
 *
 * Comments are skipped, as Light's plugin skips them, so a KDoc may still say
 * which kind something is about. The allowances below are the places that may
 * name one anyway, each with why — and each must still match, so the list can
 * only shrink.
 */
class SourceBoundaryTest {

    private enum class Kind { SUBSONIC, PLEX, JELLYFIN, LOCAL }

    /** What naming each kind looks like: its SourceKind, its classes, its provider package. */
    private val names = mapOf(
        Kind.SUBSONIC to Regex("""\bSourceKind\.SUBSONIC\b|\bSubsonic[A-Z]\w*|\bcom\.sublunar\.amp\.source\.subsonic\b"""),
        Kind.PLEX to Regex("""\bSourceKind\.PLEX\b|\bPlex[A-Z]\w*|\bcom\.sublunar\.amp\.source\.plex\b"""),
        Kind.JELLYFIN to Regex("""\bSourceKind\.JELLYFIN\b|\bJellyfin[A-Z]\w*|\bcom\.sublunar\.amp\.source\.jellyfin\b"""),
        Kind.LOCAL to Regex(
            """\bSourceKind\.LOCAL\b|\bLocal(Library|Playlists|Provider|PhoneLibrary|Ui)\b|\bcom\.sublunar\.amp\.source\.local\b""",
        ),
    )

    /**
     * The kind a file belongs to, if any: its provider's folder, and until they
     * move there, the protocol's own files in `data` and its sign-in screen.
     */
    private fun ownerOf(path: String): Kind? = when {
        path.startsWith("source/subsonic/") || path.startsWith("data/Subsonic") -> Kind.SUBSONIC
        path.startsWith("source/plex/") || path.startsWith("data/Plex") -> Kind.PLEX
        path.startsWith("source/jellyfin/") || path.startsWith("data/Jellyfin") -> Kind.JELLYFIN
        path.startsWith("source/local/") -> Kind.LOCAL
        path == "data/LocalLibrary.kt" || path == "data/LocalPlaylists.kt" -> Kind.LOCAL
        path == "ui/screens/ServerScreen.kt" -> Kind.SUBSONIC
        path == "ui/screens/PlexLinkScreen.kt" -> Kind.PLEX
        path == "ui/screens/JellyfinLinkScreen.kt" -> Kind.JELLYFIN
        else -> null
    }

    private class Allowance(val path: String, val symbol: Regex, val why: String)

    private val allowances = listOf(
        Allowance(
            "source/Providers.kt",
            Regex("""(Subsonic|Plex|Jellyfin|Local)Provider|com\.sublunar\.amp\.source\.(subsonic|plex|jellyfin|local)"""),
            "the one list of kinds",
        ),
        Allowance("data/AppSettings.kt", Regex("""SourceKind\.SUBSONIC"""), "the server from before sources existed"),
        Allowance("playback/PlaybackController.kt", Regex("""Plex\w*"""), "casting to Plex players, until it has an output of its own"),
        Allowance("ui/screens/OutputScreen.kt", Regex("""Plex(Player|Volume)"""), "casting to Plex players, until it has an output of its own"),
    )

    private class Mention(val path: String, val line: Int, val symbol: String, val text: String) {
        override fun toString() = "$path:$line: $symbol in: ${text.trim()}"
    }

    private val root = File("src/main/kotlin/com/sublunar/amp")

    /** The line as code: nothing for a comment line, and a trailing comment cut off. */
    private fun codeOf(line: String): String? {
        val trimmed = line.trimStart()
        if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) return null
        return line.substringBefore("//")
    }

    /** Every place a file names a kind it doesn't belong to. */
    private fun mentions(): List<Mention> {
        assertTrue(root.isDirectory, "run from the tool module: ${root.absolutePath}")
        val files = root.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList()
        assertTrue(files.size > 50, "only ${files.size} files under ${root.absolutePath}")
        return files.sortedBy { it.path }.flatMap { file ->
            val path = file.relativeTo(root).invariantSeparatorsPath
            val owner = ownerOf(path)
            file.readLines().withIndex().flatMap { (index, line) ->
                val code = codeOf(line) ?: return@flatMap emptyList()
                names.filterKeys { it != owner }.values.flatMap { name ->
                    name.findAll(code).map { Mention(path, index + 1, it.value, line) }.toList()
                }
            }
        }
    }

    private fun Allowance.covers(mention: Mention) = mention.path == path && symbol.matches(mention.symbol)

    @Test
    fun `no shared file names a kind of source`() {
        val all = mentions()
        assertEquals(emptyList(), all.filter { m -> allowances.none { it.covers(m) } }.map { it.toString() })
    }

    @Test
    fun `every allowance is still needed`() {
        val all = mentions()
        val stale = allowances.filter { a -> all.none { a.covers(it) } }
        assertEquals(emptyList(), stale.map { "remove this allowance: ${it.path} ${it.symbol} (${it.why})" })
    }
}
