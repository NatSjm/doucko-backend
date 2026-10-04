package cz.doucko.checking.domain

import java.math.BigDecimal
import java.math.BigInteger

/** A number read with the answer-checking numeric notation: exact value, typed decimal places, normalized text. */
internal data class ParsedNumber(
    val value: Rational,
    val decimals: Int,
    val normalized: String,
)

/**
 * answer-checking "Numeric notation" (FR-14, FR-15): optional single sign (`+`, `-`, U+2212, U+2013) directly
 * before the digits, ASCII digits plain or grouped by 3 with one space, no-break space or thin space between
 * groups, and an optional `,` or `.` followed by at least one digit.
 */
internal object NumericNotation {
    /** Characters allowed between digit groups and around a fraction slash. */
    const val SPACE_CLASS = "[ \u00A0\u2009]"

    /** Hyphen-minus, minus sign U+2212 and en dash U+2013. */
    const val MINUS_SIGNS = "-−–"
    const val INTEGER_DIGITS = "\\d{1,3}(?:$SPACE_CLASS\\d{3})+|\\d+"

    private val PATTERN = Regex("([+\\-\\u2212\\u2013])?($INTEGER_DIGITS)?(?:[.,](\\d+))?")

    fun parse(text: String): ParsedNumber? {
        val match = PATTERN.matchEntire(text) ?: return null
        val sign = match.groupValues[1]
        val integerDigits = match.groupValues[2].filter { it in '0'..'9' }
        val fraction = match.groups[3]?.value
        if (integerDigits.isEmpty() && fraction == null) return null
        val magnitude = BigDecimal(integerDigits.ifEmpty { "0" } + (fraction?.let { ".$it" } ?: ""))
        val signed = if (sign.isNotEmpty() && sign in MINUS_SIGNS) magnitude.negate() else magnitude
        return ParsedNumber(toRational(signed), fraction?.length ?: 0, normalize(signed))
    }

    fun normalize(value: BigDecimal): String = if (value.signum() == 0) "0" else value.stripTrailingZeros().toPlainString()

    private fun toRational(value: BigDecimal): Rational =
        if (value.scale() >= 0) {
            Rational.of(value.unscaledValue(), BigInteger.TEN.pow(value.scale()))
        } else {
            Rational.of(value.unscaledValue() * BigInteger.TEN.pow(-value.scale()), BigInteger.ONE)
        }
}
