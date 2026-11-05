package com.sublunar.amp.data

import android.icu.text.Transliterator

/**
 * Lyrics in another script, spelled out in Latin letters: Android's own ICU
 * transliterator, letter by letter, offline. Not a translation, and Japanese
 * kanji come out as Mandarin pinyin, ICU reading every Han character as Chinese.
 */
object LatinLetters {
    private val anyLatin: Transliterator by lazy { Transliterator.getInstance("Any-Latin") }

    /** Whether any line has a letter outside the Latin script: only then is there anything to offer. */
    fun needed(lines: List<LyricLine>): Boolean = lines.any { line -> line.text.codePoints().anyMatch(::foreign) }

    fun of(lines: List<LyricLine>): List<LyricLine> = lines.map { it.copy(text = anyLatin.transliterate(it.text)) }

    private fun foreign(codePoint: Int): Boolean =
        Character.isLetter(codePoint) && Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.LATIN
}
