package com.sublunar.amp.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What every kind of source can do, with every kind of answer about what its
 * server has — the whole table at once, so a change to any one of them shows
 * as the row it moved rather than as a feature quietly appearing or going.
 *
 * Some of these mean nothing for their kind (an original download of the
 * phone's own files), and are pinned anyway: they are what the screens read.
 */
class CapabilitySnapshotTest {

    private val featureSets = listOf(
        "never asked" to null,
        "nothing missing" to ServerFeatures(),
        "no probes" to ServerFeatures(missing = ServerFeatures.ALL),
        "no copies" to ServerFeatures(missing = listOf(ServerFeatures.KEEP_COPIES)),
        "neither" to ServerFeatures(missing = ServerFeatures.ALL + ServerFeatures.KEEP_COPIES),
    )

    private fun source(kind: SourceKind, features: ServerFeatures? = null) =
        MusicSource(id = "s", kind = kind, name = "Source", features = features)

    /**
     * likes · ratings · radio · empty playlists · downloads ·
     * refused · may download · original download · server scan · libraries,
     * then what it streams | what it downloads.
     */
    private fun row(source: MusicSource): String {
        val flags = listOf(
            source.supportsLikes,
            source.supportsRatings,
            source.supportsRadio,
            source.supportsEmptyPlaylists,
            source.supportsDownloads,
            source.downloadsRefused,
            source.mayDownload,
            source.supportsOriginalDownload,
            source.supportsServerScan,
            source.supportsLibraries,
        ).joinToString("") { if (it) "Y" else "." }
        return "$flags ${ids(source.streamFormats)} | ${ids(source.downloadFormats)}"
    }

    private fun ids(formats: List<StreamFormat>) = formats.joinToString(" ") { it.id }.ifEmpty { "-" }

    @Test
    fun `what every kind can do`() {
        val snapshot = SourceKind.entries.flatMap { kind ->
            featureSets.map { (label, features) -> "$kind, $label: ${row(source(kind, features))}" }
        }
        assertEquals(
            """
            SUBSONIC, never asked: YYYYY.YYYY mp3 opus flac raw | mp3 opus flac raw
            SUBSONIC, nothing missing: YYYYY.YYYY mp3 opus flac raw | mp3 opus flac raw
            SUBSONIC, no probes: Y..YY.Y..Y mp3 opus flac raw | mp3 opus flac
            SUBSONIC, no copies: YYYYYY.YYY mp3 opus flac raw | mp3 opus flac raw
            SUBSONIC, neither: Y..YYY...Y mp3 opus flac raw | mp3 opus flac
            PLEX, never asked: .YY.Y.YYYY raw mp3 | raw mp3
            PLEX, nothing missing: .YY.Y.YYYY raw mp3 | raw mp3
            PLEX, no probes: .YY.Y.YYYY raw mp3 | raw mp3
            PLEX, no copies: .YY.YY.YYY raw mp3 | raw mp3
            PLEX, neither: .YY.YY.YYY raw mp3 | raw mp3
            JELLYFIN, never asked: Y.YYY.YYYY mp3 opus flac raw | mp3 opus flac raw
            JELLYFIN, nothing missing: Y.YYY.YYYY mp3 opus flac raw | mp3 opus flac raw
            JELLYFIN, no probes: Y.YYY.YYYY mp3 opus flac raw | mp3 opus flac raw
            JELLYFIN, no copies: Y.YYYY.YYY mp3 opus flac raw | mp3 opus flac raw
            JELLYFIN, neither: Y.YYYY.YYY mp3 opus flac raw | mp3 opus flac raw
            LOCAL, never asked: ...Y...Y.. - | -
            LOCAL, nothing missing: ...Y...Y.. - | -
            LOCAL, no probes: ...Y...Y.. - | -
            LOCAL, no copies: ...Y.Y.Y.. - | -
            LOCAL, neither: ...Y.Y.Y.. - | -
            """.trimIndent(),
            snapshot.joinToString("\n"),
        )
    }

    /**
     * A stored format id, read for each kind: what isn't one falls back to
     * the default, and what the kind can't serve to the original. Null is
     * only possible for downloads, where it means never chosen.
     */
    @Test
    fun `a stored format reads as one the kind can serve`() {
        val stored = listOf(null, "mp3", "opus", "flac", "raw", "bogus")
        val expected = mapOf(
            SourceKind.SUBSONIC to "opus mp3 opus flac raw opus",
            SourceKind.PLEX to "raw mp3 raw raw raw raw",
            SourceKind.JELLYFIN to "opus mp3 opus flac raw opus",
            SourceKind.LOCAL to "opus mp3 opus flac raw opus",
        )
        for (kind in SourceKind.entries) {
            val source = source(kind)
            val download = stored.joinToString(" ") { source.copy(downloadFormatId = it).downloadFormat.id }
            assertEquals(expected[kind], download, "$kind downloads")
            for (id in stored.filterNotNull()) {
                val reads = source.copy(downloadFormatId = id).downloadFormat
                assertEquals(reads, source.copy(streamFormatId = id).wifiFormat, "$kind Wi-Fi $id")
                assertEquals(reads, source.copy(cellularFormatId = id).cellularFormat, "$kind cellular $id")
            }
        }
    }

    @Test
    fun `cellular follows Wi-Fi until it is chosen`() {
        val source = source(SourceKind.SUBSONIC).copy(streamFormatId = "flac")
        assertEquals(StreamFormat.FLAC, source.cellularFormat)
        assertEquals(StreamFormat.MP3, source.copy(cellularFormatId = "mp3").cellularFormat)
    }

    @Test
    fun `whether a chosen download quality can be met`() {
        val noProbes = ServerFeatures(missing = ServerFeatures.ALL)
        val noCopies = ServerFeatures(missing = listOf(ServerFeatures.KEEP_COPIES))
        assertFalse(source(SourceKind.SUBSONIC, noProbes).copy(downloadFormatId = "raw").downloadFormatAvailable)
        // Plex reads Opus as the original, which it can always hand over.
        assertTrue(source(SourceKind.PLEX).copy(downloadFormatId = "opus").downloadFormatAvailable)
        assertFalse(source(SourceKind.JELLYFIN, noCopies).downloadFormatAvailable)
        assertTrue(source(SourceKind.LOCAL).downloadFormatAvailable)
    }

    @Test
    fun `what is downloaded without asking`() {
        assertEquals(OfflineMode.MANUAL, source(SourceKind.SUBSONIC).offlineMode)
        assertEquals(OfflineMode.MANUAL, source(SourceKind.SUBSONIC).copy(offlineModeName = "bogus").offlineMode)
        assertEquals(OfflineMode.ALL, source(SourceKind.SUBSONIC).copy(offlineModeName = "ALL").offlineMode)
    }
}
