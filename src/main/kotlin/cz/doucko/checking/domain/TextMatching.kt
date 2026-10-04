package cz.doucko.checking.domain

import java.text.Normalizer
import java.util.Locale

internal object Whitespace {
    /** Any Unicode whitespace or space separator, including the no-break and thin spaces. */
    fun isSpace(c: Char): Boolean = Character.isWhitespace(c) || Character.isSpaceChar(c)

    fun trim(text: String): String = text.trim(::isSpace)
}

/** answer-checking "Short text" (FR-19): NFC, trim, collapse inner whitespace, case-insensitive, diacritics optional. */
internal object TextMatching {
    private val MARKS = Regex("\\p{M}+")

    /** The normalized interpretation: NFC, trimmed, inner whitespace runs collapsed to one space. */
    fun canonical(text: String): String {
        val nfc = Whitespace.trim(Normalizer.normalize(text, Normalizer.Form.NFC))
        val out = StringBuilder(nfc.length)
        var inSpace = false
        for (c in nfc) {
            if (Whitespace.isSpace(c)) {
                if (!inSpace) out.append(' ')
                inSpace = true
            } else {
                out.append(c)
                inSpace = false
            }
        }
        return out.toString()
    }

    fun comparisonKey(
        text: String,
        diacriticsStrict: Boolean,
    ): String {
        val canonical = canonical(text)
        val folded =
            if (diacriticsStrict) {
                canonical
            } else {
                Normalizer.normalize(MARKS.replace(Normalizer.normalize(canonical, Normalizer.Form.NFD), ""), Normalizer.Form.NFC)
            }
        return folded.lowercase(Locale.ROOT)
    }
}
