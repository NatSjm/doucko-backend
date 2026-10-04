package cz.doucko.checking.domain

import java.util.Locale

/** A unit as written, with its comparison key (case-folded, exponent as `^n`) and its display form (exponent as superscript). */
internal data class UnitOfMeasure(
    val key: String,
    val display: String,
)

/** A NUMBER_WITH_UNIT input: the number and an optional unit (answer-checking "Numbers with units", FR-17). */
internal data class NumberWithUnit(
    val number: ParsedNumber,
    val unit: UnitOfMeasure?,
)

internal object UnitNotation {
    private const val SUPERSCRIPTS = "⁰¹²³⁴⁵⁶⁷⁸⁹"
    private val CARET_EXPONENT = Regex("(.*?)\\^(\\d+)")
    private val PLAIN_EXPONENT = Regex("(.*\\p{L})(\\d+)")

    fun parse(text: String): NumberWithUnit? {
        val unitStart = text.indexOfFirst { it.isLetter() || it == '°' }
        if (unitStart < 0) return NumericNotation.parse(text)?.let { NumberWithUnit(it, null) }
        val numberText = text.substring(0, unitStart).trimEnd { Whitespace.isSpace(it) }
        val unitText = text.substring(unitStart)
        if (unitText.any { Whitespace.isSpace(it) }) return null
        val number = NumericNotation.parse(numberText) ?: return null
        return NumberWithUnit(number, unit(unitText))
    }

    fun unit(text: String): UnitOfMeasure {
        val superscript = text.takeLastWhile { it in SUPERSCRIPTS }
        val (base, exponent) =
            when {
                superscript.isNotEmpty() && superscript.length < text.length -> {
                    text.dropLast(superscript.length) to superscript.map { SUPERSCRIPTS.indexOf(it) }.joinToString("")
                }

                else -> {
                    val caret = CARET_EXPONENT.matchEntire(text)
                    val plain = PLAIN_EXPONENT.matchEntire(text)
                    when {
                        caret != null && caret.groupValues[1].isNotEmpty() -> caret.groupValues[1] to caret.groupValues[2]
                        plain != null -> plain.groupValues[1] to plain.groupValues[2]
                        else -> text to null
                    }
                }
            }
        val key = base.lowercase(Locale.ROOT) + (exponent?.let { "^$it" } ?: "")
        val display = base + (exponent?.map { SUPERSCRIPTS[it - '0'] }?.joinToString("") ?: "")
        return UnitOfMeasure(key, display)
    }
}
