package com.sublunar.amp

import com.sublunar.amp.data.noteOom
import com.thelightphone.sdk.LightJob
import com.thelightphone.sdk.LightJobHandler
import com.thelightphone.sdk.LightJobResult

/**
 * Periodic library refresh, enqueued under the same key as App.SYNC_JOB_KEY.
 * A no-op in a process with no booted app: the next launch syncs anyway.
 */
@LightJob("library-sync")
val librarySyncJob: LightJobHandler = { _, _ ->
    // Bulk work, so on a metered link outside Make it Hurt it waits rather
    // than walk the album index against the stream being played. Asked of the
    // system, not the last callback: a callback that missed Wi-Fi arriving is
    // caught here, and the fresh rules restart a download queue held for Wi-Fi.
    if (App.isReady && App.rulesNow().mayMoveHeavyBytes) {
        runCatching { App.librarySync.syncUnlessRecent() }.noteOom("background sync")
        // The one background moment a download server that stopped answering
        // is asked again. Awaited, so the job holds the process while it asks.
        runCatching { App.downloader.wakeParked() }
    }
    LightJobResult.Success(outputFilePath = null, message = null)
}
