package cz.doucko.checking.domain

import java.math.BigInteger

/** A FRACTION input in one of its three notations (answer-checking "Fraction input", FR-16). */
internal sealed interface FractionForm {
    val value: Rational
    val normalized: String

    data class Plain(
        val negative: Boolean,
        val numerator: BigInteger,
        val denominator: BigInteger,
    ) : FractionForm {
        override val value = signed(negative, numerator, denominator)
        override val normalized = text(negative, numerator, denominator)
    }

    data class Mixed(
        val negative: Boolean,
        val whole: BigInteger,
        val numerator: BigInteger,
        val denominator: BigInteger,
    ) : FractionForm {
        override val value = signed(negative, whole * denominator + numerator, denominator)
        override val normalized = text(negative, whole * denominator + numerator, denominator)
    }

    data class Decimal(
        val number: ParsedNumber,
    ) : FractionForm {
        override val value = number.value
        override val normalized = number.normalized
    }

    private companion object {
        fun signed(
            negative: Boolean,
            n: BigInteger,
            d: BigInteger,
        ) = Rational.of(if (negative) n.negate() else n, d)

        fun text(
            negative: Boolean,
            n: BigInteger,
            d: BigInteger,
        ) = (if (negative && n.signum() != 0) "-" else "") + "$n/$d"
    }
}

internal object FractionNotation {
    private const val SPACE = NumericNotation.SPACE_CLASS
    private val DIGITS = Regex("\\d+")
    private val MIXED = Regex("(${NumericNotation.INTEGER_DIGITS})$SPACE+(\\d+)")

    fun parse(text: String): FractionForm? {
        val slashes = text.count { it == '/' }
        if (slashes == 0) return NumericNotation.parse(text)?.let(FractionForm::Decimal)
        if (slashes > 1) return null
        val left = text.substringBefore('/').trimEnd(::isSpace)
        val right = text.substringAfter('/').trimStart(::isSpace)
        if (!DIGITS.matches(right)) return null
        val denominator = BigInteger(right)
        if (denominator.signum() == 0) return null
        val negative = left.isNotEmpty() && left[0] in NumericNotation.MINUS_SIGNS
        val body = if (left.isNotEmpty() && (negative || left[0] == '+')) left.substring(1) else left
        if (DIGITS.matches(body)) return FractionForm.Plain(negative, BigInteger(body), denominator)
        val mixed = MIXED.matchEntire(body) ?: return null
        val whole = BigInteger(mixed.groupValues[1].filter { it in '0'..'9' })
        val numerator = BigInteger(mixed.groupValues[2])
        if (numerator.signum() == 0 || numerator >= denominator) return null
        return FractionForm.Mixed(negative, whole, numerator, denominator)
    }

    /** True when the input is written as a fraction or mixed number whose N and D share a common factor. */
    fun notInLowestTerms(form: FractionForm): Boolean =
        when (form) {
            is FractionForm.Plain -> form.numerator.gcd(form.denominator) > BigInteger.ONE
            is FractionForm.Mixed -> form.numerator.gcd(form.denominator) > BigInteger.ONE
            is FractionForm.Decimal -> false
        }

    /** The same notation with the same numbers and sign (FRACTION parts that do not allow equivalent forms). */
    fun sameForm(
        a: FractionForm,
        b: FractionForm,
    ): Boolean =
        when {
            a is FractionForm.Plain && b is FractionForm.Plain -> {
                a.normalized == b.normalized
            }

            a is FractionForm.Mixed && b is FractionForm.Mixed -> {
                a.negative == b.negative && a.whole == b.whole && a.numerator == b.numerator && a.denominator == b.denominator
            }

            a is FractionForm.Decimal && b is FractionForm.Decimal -> {
                a.value == b.value
            }

            else -> {
                false
            }
        }

    /** A space (U+0020), no-break space (U+00A0) or thin space (U+2009), as allowed around the slash. */
    private fun isSpace(c: Char): Boolean = c.code == 0x20 || c.code == 0xA0 || c.code == 0x2009
}
