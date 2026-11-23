package com.sublunar.amp.data

import android.icu.text.Transliterator

/**
 * Lyrics in another script, spelled out in Latin letters: Android's own ICU
 * transliterator, letter by letter, offline. Not a translation, and Japanese
 * kanji come out as Mandarin pinyin, ICU reading every Han character as Chinese.
 */
object LatinLetters {
    private val anyLatin: Transliterator by lazy { Transliterator.getInstance("Any-Latin") }

    /**
     * Whether any word is written in another script: only then is there anything
     * to offer. A word, not a letter: lyrics typed out by hand can hide a Cyrillic
     * е or Greek ο among Latin letters, and those words read as Latin already.
     */
    fun needed(lines: List<LyricLine>): Boolean = lines.any { line -> words(line.text).any(::foreignWord) }

    fun of(lines: List<LyricLine>): List<LyricLine> = lines.map { it.copy(text = anyLatin.transliterate(it.text)) }

    /** Runs of letters, with the marks some scripts set between them. */
    private fun words(text: String): List<List<Int>> =
        text.codePoints().toArray().fold(mutableListOf(mutableListOf<Int>())) { words, c ->
            if (Character.isLetter(c) || isMark(c)) words.last() += c else if (words.last().isNotEmpty()) words += mutableListOf<Int>()
            words
        }

    /** Two letters or more, none of them Latin: one stray letter could be a look-alike. */
    private fun foreignWord(word: List<Int>): Boolean {
        val letters = word.filter(Character::isLetter)
        return letters.size >= 2 && letters.none { Character.UnicodeScript.of(it) == Character.UnicodeScript.LATIN }
    }

    private fun isMark(c: Int): Boolean = when (Character.getType(c).toByte()) {
        Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true
        else -> false
    }
}
