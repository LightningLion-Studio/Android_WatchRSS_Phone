package com.lightningstudio.watchrss.phone.data.note

import java.text.BreakIterator
import java.util.Locale

/**
 * Title for a note saved without one: the first three words of the first non-blank
 * plain-text line, kept as written (original spacing and punctuation between them).
 * Word boundaries come from the platform word iterator (ICU on Android, which segments
 * Chinese by dictionary); tokens without a letter or digit are skipped. Where the
 * iterator has no CJK dictionary and returns a whole ideograph run as one token, the
 * run is split into two-character words so the title still stays short.
 */
internal fun deriveNoteTitle(plainText: String, maxWords: Int = 3): String {
    val line = plainText.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return ""
    val words = wordRanges(line).take(maxWords).toList()
    if (words.isEmpty()) return line.takeTitleCodePoints()
    return line.substring(words.first().first, words.last().last + 1).takeTitleCodePoints()
}

/** The shared limit is 80 Unicode code points, never UTF-16 code units. */
private fun String.takeTitleCodePoints(): String =
    substring(0, offsetByCodePoints(0, minOf(MAX_DERIVED_TITLE, codePointCount(0, length))))

private fun wordRanges(line: String): Sequence<IntRange> = sequence {
    val iterator = BreakIterator.getWordInstance(Locale.CHINESE).apply { setText(line) }
    var from = iterator.first()
    var to = iterator.next()
    while (to != BreakIterator.DONE) {
        val token = line.substring(from, to)
        if (token.any(Char::isLetterOrDigit)) {
            if (token.length > UNSEGMENTED_RUN && token.all(::isIdeograph)) {
                for (start in from until to step 2) yield(start until minOf(start + 2, to))
            } else {
                yield(from until to)
            }
        }
        from = to
        to = iterator.next()
    }
}

private fun isIdeograph(c: Char) = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN

private const val UNSEGMENTED_RUN = 4
private const val MAX_DERIVED_TITLE = 80
