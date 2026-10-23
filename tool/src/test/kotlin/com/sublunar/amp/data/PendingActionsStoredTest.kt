package com.sublunar.amp.data

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.sublunar.amp.data.PendingAction.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

/**
 * The offline queue as the phone stores it, read and written through
 * [PendingActions] itself.
 *
 * The queue is one string, and an install reads whatever an older or a newer
 * build left there. An entry this build can't read must neither be sent nor
 * be lost, nor take the others with it.
 */
class PendingActionsStoredTest {

    /** A phone holding [queue] as its stored offline queue. */
    private class Phone(vararg queue: String) {
        val store = MemoryDataStore(preferencesOf(stringPreferencesKey(KEY) to queue.joinToString(",", "[", "]")))
        val pending = PendingActions(AppSettings(store))
        val stored: String? get() = store.raw(KEY)
    }

    /** A server that says [answer] to every write, and remembers what it was sent. */
    private class Server(private val answer: (String) -> Unit = {}) : MusicServer {
        val sent = mutableListOf<String>()
        private fun take(what: String) {
            sent += what
            answer(what)
        }
        override suspend fun scrobble(songId: String, atMs: Long?, submission: Boolean) = take("play $songId")
        override suspend fun setLiked(target: LikeTarget, id: String, liked: Boolean) =
            take(if (liked) "star $id" else "unstar $id")
        override suspend fun ping() = Unit
        override fun close() = Unit
        override suspend fun getMusicFolders(): List<MusicFolder> = emptyList()
        override suspend fun getAllAlbums(musicFolderId: String?): List<Album> = emptyList()
        override suspend fun getAlbumTracks(albumId: String): List<Track> = emptyList()
        override fun streamUrl(
            track: Track,
            format: StreamFormat,
            timeOffsetSeconds: Int,
            estimateContentLength: Boolean,
            sessionId: String?,
        ) = ""
        override fun coverArtUrl(coverArtId: String?): String? = null
    }

    private suspend fun PendingActions.flush(server: Server) = flush(server, "a") { it }

    @Test
    fun `an entry this build can't read stays where it was through an add`() = runBlocking {
        for (unreadable in listOf(UNKNOWN_KIND, MOVED_TYPE, NOT_AN_OBJECT)) {
            val phone = Phone(PLAY_1, unreadable, STAR_X)
            phone.pending.add(PendingAction(Kind.SCROBBLE, "2", atMs = 9L, sourceId = "a"))
            assertEquals(
                "[$PLAY_1,$unreadable,$STAR_X,{\"kind\":\"SCROBBLE\",\"id\":\"2\",\"atMs\":9,\"sourceId\":\"a\"}]",
                phone.stored,
                unreadable,
            )
        }
    }

    @Test
    fun `the rest is sent around it, and it is left for the build that can`() = runBlocking {
        val phone = Phone(PLAY_1, UNKNOWN_KIND, STAR_X)
        val server = Server()
        assertEquals(emptyList(), phone.pending.flush(server))
        assertEquals(listOf("play 1", "star x"), server.sent)
        assertEquals("[$UNKNOWN_KIND]", phone.stored)
    }

    @Test
    fun `an unanswered write keeps it in its place among the rest`() = runBlocking {
        val phone = Phone(PLAY_1, UNKNOWN_KIND, STAR_X)
        val server = Server { if (it == "star x") throw java.io.IOException("offline") }
        phone.pending.flush(server)
        assertEquals("[$UNKNOWN_KIND,$STAR_X]", phone.stored)
    }

    @Test
    fun `a like that lands replaces what was queued for the same thing, and only that`() = runBlocking {
        val phone = Phone(UNKNOWN_KIND, STAR_X)
        phone.pending.forget(PendingAction(Kind.UNSTAR_SONG, "x", atMs = 9L, sourceId = "a"))
        assertEquals("[$UNKNOWN_KIND]", phone.stored)
    }

    @Test
    fun `a later rating replaces one for the same thing this build can't send`() = runBlocking {
        // Kept, the newer build would send the older rating after this one, and it would win.
        val rating = PendingAction(Kind.RATE_SONG, "x", value = 2, atMs = 9L, sourceId = "a")
        val queued = Phone(MOVED_TYPE, PLAY_1)
        queued.pending.add(rating)
        assertEquals("[$PLAY_1,{\"kind\":\"RATE_SONG\",\"id\":\"x\",\"value\":2,\"atMs\":9,\"sourceId\":\"a\"}]", queued.stored)
        val landed = Phone(MOVED_TYPE, PLAY_1)
        landed.pending.forget(rating)
        assertEquals("[$PLAY_1]", landed.stored)
    }

    @Test
    fun `an entry is written back exactly as it was stored`() = runBlocking {
        // Nothing a build writes, but re-encoded the first would throw, and the
        // second turn into 1000.0, which no build reads.
        val odd = """{"kind":"RATE_SONG","id":"z","value":NaN,"sourceId":"b"}"""
        val exponent = """{"kind":"SCROBBLE","id":"e","atMs":1e3,"sourceId":"b"}"""
        val phone = Phone(odd, exponent)
        phone.pending.add(PendingAction(Kind.SCROBBLE, "2", atMs = 9L, sourceId = "a"))
        assertEquals("[$odd,$exponent,{\"kind\":\"SCROBBLE\",\"id\":\"2\",\"atMs\":9,\"sourceId\":\"a\"}]", phone.stored)
    }

    @Test
    fun `a play's time is stored even in the millisecond it was made`() {
        // Left out, it would read back as whenever it was read, and be sent as a play from then.
        // Many times over, so some land in the same millisecond.
        repeat(1_000) {
            val stored = Json.encodeToString(PendingAction.serializer(), PendingAction(Kind.SCROBBLE, "1"))
            assertTrue("\"atMs\":" in stored, stored)
        }
    }

    @Test
    fun `a key this build doesn't know is kept on an entry it leaves alone`() = runBlocking {
        val extra = """{"kind":"SCROBBLE","id":"3","atMs":5,"sourceId":"b","from":"radio"}"""
        val phone = Phone(extra)
        phone.pending.flush(Server())
        phone.pending.add(PendingAction(Kind.STAR_SONG, "y", atMs = 9L, sourceId = "a"))
        assertEquals(
            "[$extra,{\"kind\":\"STAR_SONG\",\"id\":\"y\",\"atMs\":9,\"sourceId\":\"a\"}]",
            phone.stored,
        )
    }

    @Test
    fun `nothing readable sends nothing and leaves the queue as stored`() = runBlocking {
        val phone = Phone(UNKNOWN_KIND, MOVED_TYPE)
        val server = Server()
        phone.pending.flush(server)
        assertEquals(emptyList(), server.sent)
        assertEquals("[$UNKNOWN_KIND,$MOVED_TYPE]", phone.stored)
    }

    @Test
    fun `everything sent leaves nothing stored`() = runBlocking {
        val phone = Phone(PLAY_1, STAR_X)
        phone.pending.flush(Server())
        assertEquals("", phone.stored)
    }

    @Test
    fun `text that isn't a list is no queue`() = runBlocking {
        val store = MemoryDataStore(preferencesOf(stringPreferencesKey(KEY) to "not json"))
        val pending = PendingActions(AppSettings(store))
        pending.forget(PendingAction(Kind.STAR_SONG, "x", atMs = 9L, sourceId = "a"))
        assertEquals("not json", store.raw(KEY))
    }

    private companion object {
        const val KEY = "state.pendingActions"
        const val PLAY_1 = """{"kind":"SCROBBLE","id":"1","atMs":5,"sourceId":"a"}"""
        const val STAR_X = """{"kind":"STAR_SONG","id":"x","atMs":6,"sourceId":"a"}"""

        /** A kind a newer build added. */
        const val UNKNOWN_KIND = """{"kind":"ADD_TO_PLAYLIST","id":"p","value":7,"atMs":7,"sourceId":"a"}"""

        /** A field whose type a newer build moved. */
        const val MOVED_TYPE = """{"kind":"RATE_SONG","id":"x","value":"4.5","atMs":8,"sourceId":"a"}"""

        /** Anything at all, where an entry should be. */
        const val NOT_AN_OBJECT = "\"later\""
    }
}
