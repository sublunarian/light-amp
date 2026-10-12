package com.sublunar.amp

import com.thelightphone.sdk.LightJob
import com.thelightphone.sdk.LightJobHandler
import com.thelightphone.sdk.LightJobResult

/**
 * Periodic library refresh. Runs while the tool's process is alive; on a fresh
 * process (no booted app) it no-ops, since the next launch syncs anyway.
 */
@LightJob("library-sync")
val librarySyncJob: LightJobHandler = { _, _ ->
    // Bulk work, by the same test as everything else that is: on a metered
    // link outside Make it Hurt it waits. Left alone, this walked the whole
    // album index every half hour of a cellular listening session, against
    // the stream being played — the launch sync's floor did not cover it.
    //
    // Asked of the system rather than of the last callback: a queue held for
    // Wi-Fi no longer re-reads the link while it waits (see Downloader.drain),
    // and a callback that missed Wi-Fi arriving is caught here — the fresh
    // look moves the rules, and the rules start the queue again.
    if (App.isReady && App.rulesNow().mayMoveHeavyBytes) {
        runCatching { App.library.sync() }
        // The one background moment a download server that stopped answering
        // is asked again. Awaited, so the job holds the process while it asks.
        runCatching { App.downloader.wakeParked() }
    }
    LightJobResult.Success(outputFilePath = null, message = null)
}
