package com.sublunar.amp.playback.output

import com.sublunar.amp.playback.output.dlna.DlnaFamily

/**
 * The outputs this phone offers whatever the source is: speakers on the
 * network.
 *
 * Written out by hand, as Providers is: a family is a folder under output/
 * and a line here, and nothing else names it — SourceBoundaryTest fails the
 * build otherwise — so taking one out is that folder and that line.
 */
object Outputs {
    val phone: List<OutputFamily> = listOf(DlnaFamily)
}
