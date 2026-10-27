package com.sublunar.amp.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Which of a Plex server's addresses the source moves to when the one in use
 * goes quiet, and back to when a better one answers again. The case that
 * started it: linked at home on the LAN address, then a morning on cellular
 * where only the public one could answer.
 */
class SourceRouteTest {

    private val lan = "https://192-0-2-10.abc.plex.direct:32400"
    private val docker = "https://198-51-100-1.abc.plex.direct:32400"
    private val public = "https://203-0-113-10.abc.plex.direct:32400"
    private val relay = "https://203-0-113-20.abc.plex.direct:8443"

    private var source: MusicSource? = MusicSource(
        id = "p1",
        kind = SourceKind.PLEX,
        name = "Test Plex",
        baseUrl = lan,
        token = "t",
        machineIdentifier = "m1",
        connections = listOf(lan, docker, public, relay),
    )

    /** What plex.tv lists now; null is plex.tv not answering. */
    private var plexTv: List<String>? = null
    private var plexTvAsks = 0

    /** The addresses that answer from wherever the phone is. */
    private var answering = setOf<String>()
    private val probed = mutableListOf<List<String>>()

    private var wifi = false
    private var clock = 0L
    private val saved = mutableListOf<String>()

    private val route = SourceRoute(
        source = { source },
        advertised = { plexTvAsks++; plexTv },
        firstAnswering = { uris, _ -> probed += uris; uris.firstOrNull { it in answering } },
        save = { _, _, baseUrl, connections ->
            saved += baseUrl
            source = source?.copy(baseUrl = baseUrl, connections = connections)
        },
        onLan = { wifi },
        nowMs = { clock },
        log = {},
    )

    @Test
    fun `off Wi-Fi a silent LAN address gives way to the public one`() = runBlocking {
        answering = setOf(public, relay)
        assertTrue(route.recover())
        assertEquals(public, source?.baseUrl)
        // Every address, in rank: the ranking, not the order they answer in,
        // decides between public and relay.
        assertEquals(listOf(lan, docker, public, relay), probed.single())
    }

    @Test
    fun `plex tv's list is the one tried, and kept`() = runBlocking {
        val moved = "https://203-0-113-11.abc.plex.direct:32400"
        plexTv = listOf(lan, moved, relay)
        answering = setOf(moved, relay)
        assertTrue(route.recover())
        assertEquals(moved, source?.baseUrl)
        assertEquals(listOf(lan, moved, relay), source?.connections)
    }

    @Test
    fun `the stored list serves when plex tv can't answer`() = runBlocking {
        plexTv = null
        answering = setOf(public)
        assertTrue(route.recover())
        assertEquals(public, source?.baseUrl)
    }

    @Test
    fun `a LAN address that answers after all is kept`() = runBlocking {
        answering = setOf(lan, public)
        assertFalse(route.recover())
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `nothing answering saves nothing, unless plex tv's list is new`() = runBlocking {
        answering = emptySet()
        assertFalse(route.recover())
        assertTrue(saved.isEmpty())

        clock += SourceRoute.DIRECTORY_EVERY_MS
        plexTv = listOf(lan, public)
        assertFalse(route.recover())
        assertEquals(lan, source?.baseUrl)
        assertEquals(listOf(lan, public), source?.connections)
    }

    @Test
    fun `plex tv is asked at most once a minute`() = runBlocking {
        plexTv = listOf(lan, public)
        repeat(5) { route.recover(); clock += 10_000 }
        assertEquals(1, plexTvAsks)
        clock = SourceRoute.DIRECTORY_EVERY_MS
        route.recover()
        assertEquals(2, plexTvAsks)
    }

    @Test
    fun `home again on Wi-Fi it moves back to the LAN address`() = runBlocking {
        source = source?.copy(baseUrl = public)
        wifi = true
        answering = setOf(lan, public)
        assertTrue(route.upgrade())
        assertEquals(lan, source?.baseUrl)
        // Only what ranks above the address in use is asked.
        assertEquals(listOf(lan, docker), probed.single())
    }

    @Test
    fun `off Wi-Fi it doesn't probe for the LAN address at all`() = runBlocking {
        source = source?.copy(baseUrl = public)
        wifi = false
        answering = setOf(lan, public)
        assertFalse(route.upgrade())
        assertTrue(probed.isEmpty())
    }

    @Test
    fun `on its best address already there is nothing to move up to`() = runBlocking {
        wifi = true
        answering = setOf(lan)
        assertFalse(route.upgrade())
        assertTrue(probed.isEmpty())
    }

    @Test
    fun `better addresses still silent leave it where it is`() = runBlocking {
        source = source?.copy(baseUrl = relay)
        wifi = true
        answering = setOf(relay)
        assertFalse(route.upgrade())
        assertEquals(relay, source?.baseUrl)
        assertEquals(listOf(lan, docker, public), probed.single())
    }

    @Test
    fun `an address typed in rather than listed is left alone`() = runBlocking {
        source = source?.copy(baseUrl = "https://music.example.com")
        wifi = true
        answering = setOf(lan)
        assertFalse(route.upgrade())
        assertTrue(probed.isEmpty())
    }

    @Test
    fun `a source with only the one address is never touched`() = runBlocking {
        source = MusicSource(id = "s1", kind = SourceKind.SUBSONIC, name = "Navidrome", baseUrl = "https://nd.example.com")
        answering = setOf(public)
        wifi = true
        assertFalse(route.recover())
        assertFalse(route.upgrade())
        assertTrue(probed.isEmpty())
        assertEquals(0, plexTvAsks)
    }

    @Test
    fun `no source, nothing to do`() = runBlocking {
        source = null
        assertFalse(route.recover())
        assertNull(source)
    }

    /** A route over the phone's own settings, with [meanwhile] written while the addresses are probed. */
    private fun storedRoute(settings: AppSettings, meanwhile: suspend () -> Unit) = SourceRoute(
        source = { settings.activeSource.first() },
        advertised = { null },
        firstAnswering = { uris, _ -> meanwhile(); uris.firstOrNull { it == public } },
        save = settings::setSourceAddress,
        onLan = { false },
        log = {},
    )

    @Test
    fun `an edit made while the addresses were probed stands`() = runBlocking {
        val settings = MemoryDataStore().settings()
        settings.saveSource(source!!)
        assertTrue(storedRoute(settings) { settings.setStreamFormat("p1", StreamFormat.MP3) }.recover())
        val stored = settings.sources.first().single()
        assertEquals(public, stored.baseUrl)
        assertEquals(StreamFormat.MP3.id, stored.streamFormatId)
    }

    @Test
    fun `a source removed while the addresses were probed stays removed`() = runBlocking {
        val settings = MemoryDataStore().settings()
        settings.saveSource(source!!)
        storedRoute(settings) { settings.removeSource("p1") }.recover()
        assertTrue(settings.sources.first().isEmpty())
    }

    @Test
    fun `an address a re-link chose meanwhile is kept`() = runBlocking {
        val settings = MemoryDataStore().settings()
        settings.saveSource(source!!)
        storedRoute(settings) { settings.saveSource(source!!.copy(baseUrl = relay, token = "t2")) }.recover()
        val stored = settings.sources.first().single()
        assertEquals(relay, stored.baseUrl)
        assertEquals("t2", stored.token)
    }
}
