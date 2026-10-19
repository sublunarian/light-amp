package com.sublunar.amp.source

import com.sublunar.amp.data.MusicSource
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen

/**
 * A kind of source's half that only screens read: how it is added.
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
}

/** One way to add a source: a row on Add Source, and a choice on the first-run screen. */
class AddEntry(
    val label: String,
    val icon: AddIcon,
    val action: AddAction,
    /** Only ever one: the row goes once a source of this kind exists. */
    val singleton: Boolean = false,
)

/** Which icon an add row carries, named here and drawn by the screens. */
enum class AddIcon { ADD, PHONE }

sealed interface AddAction {
    /** A page that asks for what this kind needs and saves the source itself. */
    class OpenScreen(val screen: (SealedLightActivity) -> SimpleLightScreen<Unit>) : AddAction

    /** Nothing to ask: the source is saved and switched to at once. */
    class AddAtOnce(val source: () -> MusicSource) : AddAction
}
