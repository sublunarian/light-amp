package com.sublunar.amp.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals

class SyncProgressTextTest {

    @Test
    fun `album progress reads as a count`() {
        assertEquals("Syncing… 120 of 1,419 albums", syncProgressText("Albums 120/1419"))
        assertEquals("Syncing… 0 of 61 albums", syncProgressText("Albums 0/61"))
    }

    @Test
    fun `any other phase is said as it is`() {
        assertEquals("Syncing… fetching albums", syncProgressText("Fetching albums"))
        assertEquals("Syncing… connecting", syncProgressText("Connecting"))
    }

    @Test
    fun `no phase yet is just syncing`() {
        assertEquals("Syncing…", syncProgressText(""))
    }
}
