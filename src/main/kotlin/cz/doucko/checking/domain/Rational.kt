package cz.doucko.checking.domain

import java.math.BigInteger

/** An exact rational number in lowest terms with a positive denominator. No floating point anywhere (answer-checking). */
internal class Rational private constructor(
    val numerator: BigInteger,
    val denominator: BigInteger,
) : Comparable<Rational> {
    operator fun plus(other: Rational): Rational =
        of(numerator * other.denominator + other.numerator * denominator, denominator * other.denominator)

    operator fun minus(other: Rational): Rational =
        of(numerator * other.denominator - other.numerator * denominator, denominator * other.denominator)

    fun abs(): Rational = if (numerator.signum() < 0) Rational(numerator.negate(), denominator) else this

    override fun compareTo(other: Rational): Int = (numerator * other.denominator).compareTo(other.numerator * denominator)

    override fun equals(other: Any?): Boolean = other is Rational && numerator == other.numerator && denominator == other.denominator

    override fun hashCode(): Int = 31 * numerator.hashCode() + denominator.hashCode()

    override fun toString(): String = "$numerator/$denominator"

    companion object {
        val ZERO = Rational(BigInteger.ZERO, BigInteger.ONE)

        fun of(
            numerator: BigInteger,
            denominator: BigInteger,
        ): Rational {
            require(denominator.signum() != 0) { "zero denominator" }
            val sign = if (denominator.signum() < 0) BigInteger.ONE.negate() else BigInteger.ONE
            val n = numerator * sign
            val d = denominator * sign
            val g = n.gcd(d)
            return Rational(n / g, d / g)
        }

        /** True when `|value - target| <= tolerance`, computed exactly. */
        fun within(
            value: Rational,
            target: Rational,
            tolerance: Rational,
        ): Boolean = (value - target).abs() <= tolerance
    }
}
