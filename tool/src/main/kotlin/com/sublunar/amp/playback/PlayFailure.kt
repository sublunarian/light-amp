package com.sublunar.amp.playback

/** What went wrong when the player failed on a track, as far as it changes what happens next — see [playFailure]. */
internal enum class PlayFailure {
    /** A file on the phone that wouldn't open or decode: nothing about the link or the server can change it. */
    FILE,

    /** Audio that arrived and wouldn't decode: as final as a bad file. */
    FORMAT,

    /** The app's own rule at the door, or no door yet: the server was never asked, so there is nothing to blame on it. */
    UNASKED,

    /** The server answered with an error of its own (5xx), which its other address for the same file may not give. */
    SERVER_ERROR,

    /** Any other bad status: the server is there, and said no to this track. */
    BAD_STATUS,

    /** No answer at all: the link or the server went. */
    NO_ANSWER,
}

/**
 * Sort a player failure. [fromFile]: the row played from the phone.
 * [networkError]: the player failed fetching, not decoding. [badHttpStatus]:
 * the player says the server answered with an error status, and [status] is
 * that status as the door heard it. [doorRefused]: the door turned the fetch
 * away, isn't up, or was never given the stream.
 */
internal fun playFailure(
    fromFile: Boolean,
    networkError: Boolean,
    badHttpStatus: Boolean,
    status: Int?,
    doorRefused: Boolean,
): PlayFailure = when {
    fromFile -> PlayFailure.FILE
    !networkError -> PlayFailure.FORMAT
    doorRefused -> PlayFailure.UNASKED
    badHttpStatus && status in 500..599 -> PlayFailure.SERVER_ERROR
    badHttpStatus -> PlayFailure.BAD_STATUS
    else -> PlayFailure.NO_ANSWER
}
