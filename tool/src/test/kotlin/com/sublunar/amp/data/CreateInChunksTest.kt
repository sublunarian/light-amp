package com.sublunar.amp.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

/** A playlist saved from a long queue goes to the server a hundred songs at a time — see [createInChunks]. */
class CreateInChunksTest {

    /**
     * A server that names its new playlist [madeId], refuses the append numbered
     * [failAppend], if any, and takes [perRequest] ids a request.
     */
    private class Server(
        private val madeId: String? = "p",
        private val failAppend: Int? = null,
        private val perRequest: Int = PLAYLIST_IDS_PER_REQUEST,
    ) : MusicServer {
        val created = mutableListOf<List<String>>()
        val appended = mutableListOf<List<String>>()

        override suspend fun createPlaylist(name: String, songIds: List<String>): String? {
            created += songIds
            return madeId
        }

        override suspend fun addToPlaylist(id: String, songIds: List<String>) {
            if (appended.size + 1 == failAppend) throw java.io.IOException("reset")
            appended += songIds
        }

        override suspend fun playlistIdsPerRequest() = perRequest

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

    private fun ids(n: Int) = (1..n).map { "s$it" }

    @Test
    fun `a short list is one create, as before`() = runBlocking {
        val server = Server()
        assertEquals(WriteResult.Saved, createInChunks(server, "Mix", ids(100)))
        assertEquals(listOf(ids(100)), server.created)
        assertEquals(emptyList(), server.appended)
    }

    @Test
    fun `a long list is created with the first hundred and the rest appended in order`() = runBlocking {
        val server = Server()
        assertEquals(WriteResult.Saved, createInChunks(server, "Mix", ids(250)))
        assertEquals(listOf(ids(100)), server.created)
        assertEquals(listOf(100, 100, 50), (server.created + server.appended).map { it.size })
        assertEquals(ids(250), (server.created + server.appended).flatten())
    }

    @Test
    fun `an append that fails says how many songs went in`() = runBlocking {
        val result = createInChunks(Server(failAppend = 2), "Mix", ids(250))
        assertEquals(WriteResult.Unreachable("Only 200 of 250 songs saved. $UNREACHABLE"), result)
    }

    @Test
    fun `a playlist the server didn't name can't be appended to, and says so`() = runBlocking {
        val result = createInChunks(Server(madeId = null), "Mix", ids(150))
        assertEquals(
            WriteResult.Refused("Only 100 of 150 songs saved. The server didn't say which playlist it made"),
            result,
        )
        assertEquals(WriteResult.Saved, createInChunks(Server(madeId = null), "Mix", ids(100)))
    }

    @Test
    fun `a create that fails is the plain failure`() = runBlocking {
        val server = object : MusicServer by Server() {
            override suspend fun createPlaylist(name: String, songIds: List<String>): String? =
                throw java.io.IOException("reset")
        }
        assertEquals(WriteResult.Unreachable(UNREACHABLE), createInChunks(server, "Mix", ids(250)))
    }

    @Test
    fun `a server that takes form posts gets a library in a few requests`() = runBlocking {
        val server = Server(perRequest = 5_000)
        assertEquals(WriteResult.Saved, createInChunks(server, "Everything", ids(12_000)))
        assertEquals(listOf(5_000, 5_000, 2_000), (server.created + server.appended).map { it.size })
        assertEquals(ids(12_000), (server.created + server.appended).flatten())
    }
}
