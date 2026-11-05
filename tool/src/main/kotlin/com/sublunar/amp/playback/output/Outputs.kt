package com.sublunar.amp.playback.output

import com.sublunar.amp.playback.output.airplay.AirPlayFamily
import com.sublunar.amp.playback.output.dlna.DlnaFamily

/**
 * The outputs this phone offers whatever the source is: speakers on the
 * network.
 *
 * In order of preference: a speaker that takes both is listed once, under
 * the first that can play the source that's on (see OutputFamilies.rows).
 * DLNA comes first because the speaker fetches the music itself — the phone
 * can sleep, hi-res stays hi-res — and AirPlay, where the phone sends the
 * sound, plays what a speaker can't fetch: the phone's own files.
 *
 * Written out by hand, as Providers is: a family is a folder under output/
 * and a line here, and nothing else names it — SourceBoundaryTest fails the
 * build otherwise — so taking one out is that folder and that line.
 */
object Outputs {
    val phone: List<OutputFamily> = listOf(DlnaFamily, AirPlayFamily)
}
