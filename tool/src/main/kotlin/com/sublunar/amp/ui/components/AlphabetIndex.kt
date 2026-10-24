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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import com.sublunar.amp.ui.px
import com.sublunar.amp.ui.pxSp
import com.thelightphone.sdk.ui.LightThemeTokens
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
 * Vertical A–Z strip for jumping around a long list. Only letters that occur are
 * tappable, the rest dimmed, so the strip stays a stable full alphabet. Dragging
 * down it scrubs through sections.
 */
@Composable
fun AlphabetIndex(
    /** Bucket letter for each row, in list order, offset by [headerCount]. */
    letters: List<Char>,
    target: ScrollTarget,
    modifier: Modifier = Modifier,
    /** Rows above the indexed content (play-all rows and the like). */
    headerCount: Int = 0,
    /** Draw the strip Z→A, for a list sorted in descending order. */
    reversed: Boolean = false,
) {
    // The row buckets already follow the list's order, so only the strip flips.
    val strip = remember(reversed) { if (reversed) LETTERS.reversed() else LETTERS }
    val scope = rememberCoroutineScope()
    val rows = remember(letters) { LetterRows(letters) }
    // First row index per letter, so a tap can jump straight there.
    val firstIndex = remember(letters, headerCount) { rows.first.mapValues { it.value + headerCount } }
    if (firstIndex.isEmpty()) return

    fun jumpTo(letter: Char) {
        val destination = firstIndex[letter] ?: return
        scope.launch { target.scrollTo(destination) }
    }

    Box(modifier = modifier.fillMaxHeight().width(px(INDEX_STRIP_PX))) {
        // The strip says where you can jump, this line where you are.
        ScrollMarker(target, letters, rows, strip, headerCount)
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth()
                .padding(vertical = px(10))
                .pointerInput(firstIndex) {
                    awaitEachGesture {
                        // Maps the touch's height onto the strip, for a tap and a drag alike.
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
                    size = pxSp(26),
                    lineHeight = pxSp(26),
                    align = TextAlign.Center,
                    dim = letter !in firstIndex,
                )
            }
        }
    }
}

/** Each letter's first and last row, in the list's own indices. */
private class LetterRows(letters: List<Char>) {
    val first = HashMap<Char, Int>()
    val last = HashMap<Char, Int>()

    init {
        letters.forEachIndexed { i, letter ->
            first.putIfAbsent(letter, i)
            last[letter] = i
        }
    }
}

/**
 * Where the list has reached, as a line across the strip. Positioned by letter, not
 * by how far down the list it is (the two only agree when every letter has as many
 * rows), advancing through the current letter's slot as its rows scroll by.
 */
@Composable
private fun BoxScope.ScrollMarker(
    target: ScrollTarget,
    letters: List<Char>,
    rows: LetterRows,
    strip: List<Char>,
    headerCount: Int,
) {
    val total = target.totalItems
    if (total == 0 || target.visibleItems >= total || letters.isEmpty()) return

    val row = (target.firstVisibleIndex - headerCount).coerceIn(0, letters.lastIndex)
    val letter = letters[row]
    val slot = strip.indexOf(letter)
    if (slot < 0) return
    val start = rows.first.getValue(letter)
    val end = rows.last.getValue(letter)
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

/** Width of the strip, so lists keep their text clear of it. The scroll bar shares the lane. */
const val INDEX_STRIP_PX = 66
private const val MARKER_H_PX = 3

