package com.sublunar.amp.source.local

import com.sublunar.amp.data.LocalLibrary
import com.sublunar.amp.data.LocalPlaylists
import com.sublunar.amp.data.MusicServer
import com.sublunar.amp.data.MusicSource
import com.sublunar.amp.data.ServerFeatures
import com.sublunar.amp.data.SourceKind
import com.sublunar.amp.data.StreamFormat
import com.sublunar.amp.source.AddAction
import com.sublunar.amp.source.AddEntry
import com.sublunar.amp.source.AddIcon
import com.sublunar.amp.source.ConnectionRow
import com.sublunar.amp.source.PhoneAccess
import com.sublunar.amp.source.PhoneLibrary
import com.sublunar.amp.source.PhonePlaylists
import com.sublunar.amp.source.PhoneScan
import com.sublunar.amp.source.SourceCapabilities
import com.sublunar.amp.source.SourceProvider
import com.sublunar.amp.source.SourceUi

/**
 * The phone's own music folder — the same files the Light Music app plays.
 *
 * No field on a MusicSource is this kind's own: there is nothing to sign in
 * to and nowhere to point.
 */
object LocalProvider : SourceProvider {
    override val kind = SourceKind.LOCAL

    /** The phone's own music is never transcoded at all. */
    override val streamFormats: List<StreamFormat> get() = emptyList()

    /**
     * There is no server behind it, so everything that lives on one — likes,
     * ratings, radio, a scan to ask for — is unavailable, and downloading
     * means nothing when the audio is already here. Playlists are the
     * phone's own m3u8 files, which can start empty. The original download
     * reads as possible only because there is nothing to narrow: no screen
     * offers a download here.
     */
    override fun capabilities(features: ServerFeatures?) = SourceCapabilities(
        likes = false,
        ratings = false,
        radio = false,
        emptyPlaylists = true,
        downloads = false,
        originalDownload = true,
        serverScan = false,
        libraries = false,
    )

    /** There is no server behind the phone's own music at all. */
    override fun createClient(source: MusicSource): MusicServer? = null

    override val ui: SourceUi get() = LocalUi

    // A getter: LocalLibrary asks LightOS about access, and loading this
    // provider must never get as far as that.
    override val phoneLibrary: PhoneLibrary get() = LocalPhoneLibrary
}

/** The phone's music folder and its playlists — see [LocalLibrary] and [LocalPlaylists]. */
internal object LocalPhoneLibrary : PhoneLibrary {
    override val folderLabel: String get() = LocalLibrary.FOLDER

    override val permission: String get() = LocalLibrary.PERMISSION

    override suspend fun access(): PhoneAccess = LocalLibrary.access()

    override suspend fun scan(onProgress: (Int) -> Unit): PhoneScan = LocalLibrary.scan(onProgress)

    override val playlists: PhonePlaylists get() = LocalPlaylists
}

private object LocalUi : SourceUi {
    // Only ever one: it is the phone, and there is only one phone.
    override val addEntries = listOf(
        AddEntry("Music on This Phone", AddIcon.PHONE, AddAction.AddAtOnce { MusicSource.local() }, singleton = true),
    )

    /** Nothing to connect to: the music is on the phone. */
    override fun connection(source: MusicSource): ConnectionRow? = null
}
