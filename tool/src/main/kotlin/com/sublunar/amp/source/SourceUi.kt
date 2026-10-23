package com.sublunar.amp.source

import com.sublunar.amp.data.MusicSource
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen

/**
 * A kind of source's half that only screens read: how it is added, and the
 * Connection row on a source's own page.
 *
 * Kept apart from the rest of [SourceProvider], and reached through a
 * getter, so that loading a provider — which every capability check does —
 * never loads a screen.
 */
interface SourceUi {
    /** How this kind is added, in the order offered. */
    val addEntries: List<AddEntry>

    /** A line beside the add choices saying what this kind covers, where its name alone doesn't. */
    val addNote: String? get() = null

    /** The Connection row on [source]'s own page, or null where there is nothing to connect to. */
    fun connection(source: MusicSource): ConnectionRow?
}

/** What a source's Connection row says under the address, and what pressing it opens. */
class ConnectionRow(
    val subtitle: String? = null,
    /** Null for a row that states where the music comes from and takes no press — TextRow's own convention. */
    val open: ((SealedLightActivity) -> SimpleLightScreen<Unit>)? = null,
)

/** One way to add a source: a row on Add Source, and a choice on the first-run screen. */
class AddEntry(
    val label: String,
    val icon: AddIcon,
    val action: AddAction,
    /** Only ever one: the row goes once a source of this kind exists. */
    val singleton: Boolean = false,
    /** Needs a server of your own. The ones that don't are offered first. */
    val selfHosted: Boolean = true,
)

/** Which icon an add row carries, named here and drawn by the screens. */
enum class AddIcon { ADD, PHONE }

sealed interface AddAction {
    /** A page that asks for what this kind needs and saves the source itself. */
    class OpenScreen(val screen: (SealedLightActivity) -> SimpleLightScreen<Unit>) : AddAction

    /** Nothing to ask: the source is saved and switched to at once. */
    class AddAtOnce(val source: () -> MusicSource) : AddAction
}
