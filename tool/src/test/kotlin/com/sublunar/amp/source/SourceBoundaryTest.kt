package com.sublunar.amp.source

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Each kind of source keeps to its own folder.
 *
 * Nothing outside source/<kind>/ names com.sublunar.amp.source.<kind>, in an
 * import or written out in full. The registry may, to import each kind's
 * provider and nothing else of it, and so may the files in ALLOWED, each
 * saying why and what removes it. Kotlin's `internal` is module-wide and Amp
 * is one module, so this test is the fence. It reads the sources as text, as
 * Light's plugin does, and skips the same comment lines.
 *
 * Nor does a file outside a kind's folder name that kind's SourceKind, beyond
 * KIND_ALLOWED. Testing a source's kind needs no import, so the fence can't
 * see it, and a branch per kind is what the providers replaced.
 */
class SourceBoundaryTest {

    @Test
    fun `nothing outside a protocol's folder names that protocol`() {
        val files = mainTree()
        val found = violations(files, ALLOWED)
        if (found.isNotEmpty()) fail(report(found, fences(files)))
    }

    @Test
    fun `nothing outside a kind's folder names its SourceKind`() {
        val found = kindViolations(mainTree(), KIND_ALLOWED)
        if (found.isNotEmpty()) fail(kindReport(found))
    }

    @Test
    fun `every allowance still matches a crossing`() {
        val files = mainTree()
        val fences = fences(files)
        val stale = stale(ALLOWED, crossings(files, fences)) + stale(KIND_ALLOWED, kindCrossings(files, fences))
        if (stale.isNotEmpty()) {
            fail(
                "These allowances match nothing any more. Delete them, so the list only shrinks:\n" +
                    stale.joinToString("\n") { "  ${it.path} -> ${it.fence} (${it.why})" },
            )
        }
    }

    @Test
    fun `every file declares the package of its folder`() {
        val misfiled = listOf("main" to mainTree(), "test" to testTree()).flatMap { (set, files) ->
            files.mapNotNull { file ->
                val declared = PACKAGE_LINE.find(file.text)?.groupValues?.get(1)
                val expected = packageOf(file.path)
                if (declared == expected) null else "$set: ${file.path} declares $declared, its folder is $expected"
            }
        }
        assertEquals(emptyList(), misfiled)
    }

    @Test
    fun `every protocol folder has its provider in the registry`() {
        val files = mainTree()
        val registry = files.single { it.path == REGISTRY }.text
        val unlisted = fences(files).filter { it.registered }.filterNot { fence ->
            Regex("^import\\s+" + Regex.escape(fence.pkg) + "\\.\\w+Provider\\s*\$", RegexOption.MULTILINE)
                .containsMatchIn(registry)
        }
        assertEquals(emptyList(), unlisted.map { "${it.folder} has no provider imported in $REGISTRY" })
    }

    @Test
    fun `the fence catches each way of naming a protocol`() {
        val files = PROVIDERS + listOf(
            KtFile(
                "source/jellyfin/JellyfinClient.kt",
                lines("package com.sublunar.amp.source.jellyfin", "", "import com.sublunar.amp.source.plex.PlexClient"),
            ),
            KtFile("data/Bar.kt", lines("package com.sublunar.amp.data", "", "import com.sublunar.amp.source.plex.*")),
            KtFile(
                "ui/screens/Foo.kt",
                lines(
                    "package com.sublunar.amp.ui.screens",
                    "",
                    "fun foo() {",
                    "    val c = com.sublunar.amp.source.plex.PlexClient(\"\")",
                    "}",
                ),
            ),
            KtFile(
                REGISTRY,
                lines(
                    "package com.sublunar.amp.source",
                    "",
                    "import com.sublunar.amp.source.plex.PlexProvider",
                    "import com.sublunar.amp.source.plex.PlexClient",
                ),
            ),
            KtFile("ui/Misfiled.kt", lines("package com.sublunar.amp.source.plex")),
        )
        val caught = listOf(
            "data/Bar.kt:3",
            "source/Providers.kt:4",
            "source/jellyfin/JellyfinClient.kt:3",
            "ui/Misfiled.kt:1",
            "ui/screens/Foo.kt:4",
        )
        assertEquals(caught, violations(files, emptyList()).map { "${it.path}:${it.line}" }.sorted())

        val bar = Allowance("data/Bar.kt", "plex", "a test")
        assertEquals(caught - "data/Bar.kt:3", violations(files, listOf(bar)).map { "${it.path}:${it.line}" }.sorted())
        val elsewhere = Allowance("data/Bar.kt", "jellyfin", "a test")
        assertEquals(caught, violations(files, listOf(elsewhere)).map { "${it.path}:${it.line}" }.sorted())
        assertEquals(listOf(elsewhere), stale(listOf(bar, elsewhere), crossings(files, fences(files))))

        val kinds = PROVIDERS + listOf(
            KtFile("source/plex/PlexDtos.kt", lines("package com.sublunar.amp.source.plex", "", "val k = SourceKind.SUBSONIC")),
            KtFile(
                "ui/screens/Foo.kt",
                lines("package com.sublunar.amp.ui.screens", "", "fun plex(s: MusicSource) = s.kind == SourceKind.PLEX"),
            ),
            KtFile("data/Baz.kt", lines("package com.sublunar.amp.data", "", "val k = com.sublunar.amp.data.SourceKind.JELLYFIN")),
            KtFile(REGISTRY, lines("package com.sublunar.amp.source", "", "val k = SourceKind.LOCAL")),
        )
        assertEquals(
            listOf("data/Baz.kt:3", "source/Providers.kt:3", "source/plex/PlexDtos.kt:3", "ui/screens/Foo.kt:3"),
            kindViolations(kinds, emptyList()).map { "${it.path}:${it.line}" }.sorted(),
        )
        assertEquals(
            listOf("data/Baz.kt:3", "source/Providers.kt:3", "source/plex/PlexDtos.kt:3"),
            kindViolations(kinds, listOf(Allowance("ui/screens/Foo.kt", "plex", "a test")))
                .map { "${it.path}:${it.line}" }.sorted(),
        )
    }

    @Test
    fun `the fence lets through what it should`() {
        val files = PROVIDERS + listOf(
            KtFile(
                "source/plex/companion/PlexGdm.kt",
                lines("package com.sublunar.amp.source.plex.companion", "", "import com.sublunar.amp.source.plex.PlexPlayer"),
            ),
            KtFile(
                REGISTRY,
                lines(
                    "package com.sublunar.amp.source",
                    "",
                    "import com.sublunar.amp.source.local.LocalProvider",
                    "import com.sublunar.amp.source.plex.PlexProvider",
                ),
            ),
            KtFile(
                "ui/screens/Notes.kt",
                lines(
                    "package com.sublunar.amp.ui.screens",
                    "",
                    "import com.sublunar.amp.source.PendingServer",
                    "import com.sublunar.amp.source.plexus.Thing",
                    "",
                    "// Plex's sign-in is in com.sublunar.amp.source.plex, for SourceKind.PLEX",
                    "/**",
                    " * See com.sublunar.amp.source.plex.PlexClient, which SourceKind.PLEX builds.",
                    " */",
                    "/* com.sublunar.amp.source.plex */",
                    "val all = SourceKind.entries",
                ),
            ),
        )
        assertEquals(emptyList(), violations(files, emptyList()).map { "${it.path}:${it.line}" })
        assertEquals(emptyList(), kindViolations(files, emptyList()).map { "${it.path}:${it.line}" })
    }

    /** A Kotlin file, by its path below com/sublunar/amp. */
    private data class KtFile(val path: String, val text: String)

    private data class Fence(val name: String, val folder: String, val pkg: String, val registered: Boolean)

    private data class Crossing(val path: String, val line: Int, val fence: String, val code: String)

    private class Allowance(val path: String, val fence: String, val why: String) {
        fun covers(crossing: Crossing) = crossing.path == path && crossing.fence == fence
    }

    private companion object {
        const val ROOT_PACKAGE = "com.sublunar.amp"
        const val REGISTRY = "source/Providers.kt"

        /** Fences outside source/. Step 3 adds Fence("dlna", "playback/output/dlna/", "com.sublunar.amp.playback.output.dlna", registered = false). */
        val EXTRA_FENCES: List<Fence> = emptyList()

        /** Files outside a protocol's folder that may name its package, each with why and what removes it. */
        val ALLOWED: List<Allowance> = listOf(
            Allowance(
                "playback/PlaybackController.kt",
                "plex",
                "Plex Companion casting; leaves PlaybackController with the cast session (step 3)",
            ),
            Allowance(
                "ui/screens/OutputScreen.kt",
                "plex",
                "lists Plex players as outputs; goes behind the Output contract (step 3)",
            ),
        )

        /** Files outside a kind's folder that may name its SourceKind, each with why and what removes it. */
        val KIND_ALLOWED: List<Allowance> = listOf(
            Allowance(
                "data/AppSettings.kt",
                "subsonic",
                "the one server from before sources existed was a Subsonic server, and stands in until a source is read. Stays: it is what older installs stored",
            ),
        )

        val PACKAGE_LINE = Regex("^package\\s+([\\w.]+)", RegexOption.MULTILINE)

        /** One provider file per kind, each naming its own SourceKind: the folders a synthetic tree is fenced by. */
        val PROVIDERS: List<KtFile> = listOf("subsonic", "plex", "jellyfin", "local").map { kind ->
            val name = kind.replaceFirstChar { it.uppercase() }
            KtFile(
                "source/$kind/${name}Provider.kt",
                lines("package $ROOT_PACKAGE.source.$kind", "", "object ${name}Provider {", "    val kind = SourceKind.${kind.uppercase()}", "}"),
            )
        }

        fun lines(vararg line: String) = line.joinToString("\n")

        fun mainTree(): List<KtFile> = tree("main").also { files ->
            assertTrue(files.size >= 80 && files.any { it.path == "App.kt" }, "only ${files.size} main files, or no App.kt")
        }

        fun testTree(): List<KtFile> = tree("test").also { files ->
            assertTrue(files.any { it.path == "source/SourceBoundaryTest.kt" }, "this test isn't among the ${files.size} test files read")
        }

        /** Every .kt file under src/<set>/kotlin/com/sublunar/amp, read from the module or from the repository root. */
        fun tree(set: String): List<KtFile> {
            val below = "src/$set/kotlin/com/sublunar/amp"
            val root = listOf(below, "tool/$below").map { File(it) }.firstOrNull { it.isDirectory }
                ?: fail("No $below under ${File("").absolutePath}: this test reads the sources and must not pass without them.")
            return root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .map { KtFile(it.relativeTo(root).invariantSeparatorsPath, it.readText()) }
                .sortedBy { it.path }
                .toList()
        }

        /** The package a file in [path]'s folder declares. */
        fun packageOf(path: String): String {
            val folder = path.substringBeforeLast('/', "")
            return if (folder.isEmpty()) ROOT_PACKAGE else ROOT_PACKAGE + "." + folder.replace('/', '.')
        }

        /** Every folder directly under source/ is a fence, its subfolders part of it, and so is each of EXTRA_FENCES. */
        fun fences(files: List<KtFile>): List<Fence> =
            files.map { it.path.split('/') }
                .filter { it.size >= 3 && it[0] == "source" }
                .map { it[1] }
                .distinct()
                .sorted()
                .map { Fence(it, "source/$it/", "$ROOT_PACKAGE.source.$it", registered = true) } + EXTRA_FENCES

        /** Each line Light's plugin reads as code, by number and trimmed: all but the comment lines it skips. */
        fun codeLines(file: KtFile): List<Pair<Int, String>> =
            file.text.lines().mapIndexedNotNull { index, line ->
                val code = line.trim()
                if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) null else index + 1 to code
            }

        /** Every line, outside a fence's folder, that [naming] finds in. */
        fun crossings(files: List<KtFile>, fences: List<Fence>, naming: (Fence) -> Regex): List<Crossing> {
            val named = fences.map { it to naming(it) }
            return files.flatMap { file ->
                codeLines(file).flatMap { (number, code) ->
                    named.filter { (fence, regex) -> !file.path.startsWith(fence.folder) && regex.containsMatchIn(code) }
                        .map { (fence, _) -> Crossing(file.path, number, fence.name, code) }
                }
            }
        }

        /** Lines naming a fence's package: an import, a wildcard, an alias, a name written out in full, a package line. */
        fun crossings(files: List<KtFile>, fences: List<Fence>): List<Crossing> =
            crossings(files, fences) { Regex("(?<![\\w.])" + Regex.escape(it.pkg) + "(?!\\w)") }

        /** Lines naming a kind's SourceKind constant, for each fence that is a kind of source. */
        fun kindCrossings(files: List<KtFile>, fences: List<Fence>): List<Crossing> =
            crossings(files, fences.filter { it.registered }) { Regex("(?<!\\w)SourceKind\\." + it.name.uppercase() + "(?!\\w)") }

        fun violations(files: List<KtFile>, allowed: List<Allowance>): List<Crossing> {
            val fences = fences(files)
            val providerImports = fences.filter { it.registered }.associate {
                it.name to Regex("import\\s+" + Regex.escape(it.pkg) + "\\.\\w+Provider")
            }
            return crossings(files, fences)
                .filterNot { c -> c.path == REGISTRY && providerImports[c.fence]?.matches(c.code) == true }
                .filterNot { c -> allowed.any { it.covers(c) } }
        }

        fun kindViolations(files: List<KtFile>, allowed: List<Allowance>): List<Crossing> =
            kindCrossings(files, fences(files)).filterNot { c -> allowed.any { it.covers(c) } }

        fun stale(allowed: List<Allowance>, found: List<Crossing>): List<Allowance> =
            allowed.filter { a -> found.none { a.covers(it) } }

        fun listing(found: List<Crossing>): String =
            found.joinToString("\n") { "  ${it.path}:${it.line} -> ${it.fence}\n      ${it.code}" }

        fun report(found: List<Crossing>, fences: List<Fence>): String {
            val reached = fences.filter { fence -> found.any { it.fence == fence.name } }
            val only = reached.joinToString("\n") { fence ->
                "Only ${fence.folder} may name ${fence.pkg}" +
                    if (fence.registered) ", and $REGISTRY may import its provider." else "."
            }
            return "Protocol folders are fenced, and ${found.size} " +
                (if (found.size == 1) "line reaches" else "lines reach") + " into one from outside:\n\n" +
                listing(found) + "\n\n" + only + "\n" +
                "Reach a protocol through Providers, move what two protocols share up to\n" +
                "com.sublunar.amp.source, or, for a known leftover, add an Allowance to\n" +
                "SourceBoundaryTest.ALLOWED saying why it is there and what removes it."
        }

        fun kindReport(found: List<Crossing>): String =
            "Shared code names a kind of source, in ${found.size} " +
                (if (found.size == 1) "line" else "lines") + ":\n\n" +
                listing(found) + "\n\n" +
                "Ask the source's provider (Providers.of) instead of testing its kind, or, for a\n" +
                "known leftover, add an Allowance to SourceBoundaryTest.KIND_ALLOWED saying why\n" +
                "it is there and what removes it."
    }
}
