package com.sublunar.amp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import com.sublunar.amp.ui.px
import com.sublunar.amp.ui.n
import com.thelightphone.sdk.ui.LightThemeTokens
import com.sublunar.amp.ui.nSp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Letters offered by the index, with "#" collecting everything non-alphabetic. */
private val LETTERS: List<Char> = ('A'..'Z').toList() + '#'

/**
 * The bucket a title belongs to: its first letter, or "#" for anything starting
 * with a digit or symbol. Callers must key on the *sort* name (article-stripped)
 * so "The Beatles" indexes under B, matching the list order.
 */
fun indexLetterOf(sortName: String): Char {
    val first = sortName.firstOrNull()?.uppercaseChar() ?: return '#'
    return if (first in 'A'..'Z') first else '#'
}

/**
 * Vertical A–Z strip for jumping around a long list.
 *
 * Only letters that actually occur are tappable; the rest are dimmed so the strip
 * stays a stable full alphabet rather than jumping around as the list changes.
 * Dragging down the strip scrubs through sections, which is the whole point on a
 * list of hundreds of albums.
 */
@Composable
fun AlphabetIndex(
    /** Bucket letter for each row, in list order, offset by [headerCount]. */
    letters: List<Char>,
    target: ScrollTarget,
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
    /** Rows above the indexed content (play-all rows and the like). */
    headerCount: Int = 0,
    /** Draw the strip Z→A, for a list sorted in descending order. */
    reversed: Boolean = false,
) {
    // The row buckets already match the list's order, so only the strip itself
    // needs flipping — a descending list still wants a working index, just one
    // that reads the way the list does.
    val strip = remember(reversed) { if (reversed) LETTERS.reversed() else LETTERS }
    // First row index per letter, so a tap can jump straight there.
    val firstIndex = remember(letters) {
        buildMap {
            letters.forEachIndexed { i, letter -> putIfAbsent(letter, i + headerCount) }
        }
    }
    if (firstIndex.isEmpty()) return

    fun jumpTo(letter: Char) {
        val destination = firstIndex[letter] ?: return
        scope.launch { target.scrollTo(destination) }
    }

    Box(modifier = modifier.fillMaxHeight().width(px(INDEX_STRIP_PX))) {
    // A hairline showing where in the list you actually are: the strip says
    // where you *can* jump, and without this it never says where you are.
    ScrollMarker(target, letters, strip, headerCount)
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .fillMaxWidth()
            .padding(vertical = n(4))
            .pointerInput(firstIndex) {
                awaitEachGesture {
                    // Map the touch's y position onto the strip; works for a tap
                    // and for a continuous drag without extra state.
                    fun letterAt(y: Float): Char {
                        val slot = (y / size.height * strip.size).toInt()
                        return strip[slot.coerceIn(0, strip.lastIndex)]
                    }
                    val down = awaitFirstDown()
                    var last = letterAt(down.position.y)
                    jumpTo(last)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        val letter = letterAt(change.position.y)
                        if (letter != last) {
                            last = letter
                            jumpTo(letter)
                        }
                        if (!change.pressed) break
                        change.consume()
                    }
                }
            },
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        strip.forEach { letter ->
            AppText(
                text = letter.toString(),
                size = nSp(10),
                lineHeight = nSp(10),
                align = TextAlign.Center,
                dim = letter !in firstIndex,
            )
        }
    }
    }
}

/**
 * Where the list has reached, as a line across the strip.
 *
 * Positioned by *letter*, not by how far down the list you are: the two only
 * agree when every letter has the same number of rows, which no library does —
 * a collection heavy in S would show the line against P for hundreds of rows.
 * The line sits within the current letter's own slot, advancing through it as
 * you scroll that letter's rows, so it always points at the letter you're in.
 */
@Composable
private fun BoxScope.ScrollMarker(
    target: ScrollTarget,
    letters: List<Char>,
    strip: List<Char>,
    headerCount: Int,
) {
    val total = target.totalItems
    if (total == 0 || target.visibleItems >= total || letters.isEmpty()) return

    val row = (target.firstVisibleIndex - headerCount).coerceIn(0, letters.lastIndex)
    val slot = strip.indexOf(letters[row])
    if (slot < 0) return
    // How far through this letter's own rows we are, so the line moves smoothly
    // rather than jumping a whole slot at each boundary.
    val start = letters.indexOfFirst { it == letters[row] }
    val end = letters.indexOfLast { it == letters[row] }
    val within = if (end > start) (row - start).toFloat() / (end - start + 1) else 0f
    val fraction = ((slot + within) / strip.size).coerceIn(0f, 1f)

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(y = maxHeight * fraction)
                .fillMaxWidth()
                .height(px(MARKER_H_PX))
                .background(LightThemeTokens.colors.content),
        )
    }
}

/**
 * Width of the strip, so lists can keep their text clear of it.
 *
 * The letters and the position marker (which spans the lane) centre in it, and
 * the plain scroll bar shares the same lane so it lands on the same axis — see
 * [SCROLLBAR_LANE_PX].
 */
const val INDEX_STRIP_PX = 66
private const val MARKER_H_PX = 3
