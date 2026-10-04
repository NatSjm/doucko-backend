package cz.doucko.checking.domain

import cz.doucko.checking.AnswerSpec
import cz.doucko.checking.AnswerType
import cz.doucko.checking.RawInput
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.util.Locale
import java.util.ResourceBundle

/**
 * Czech format tips for UNPARSEABLE parts (answer-checking "Format tip for unreadable input"). The text comes
 * from the backend message catalog `messages.properties`, keys `checking.tip.<TYPE>` (NFR-9), read once with
 * `ResourceBundle` when this object initializes, so the module needs no Spring and no check reads a file.
 *
 * A numeric tip's `{0}` is one fixed, deliberately unusual example per type. Only when the part grades that
 * example as not WRONG (it lies in the accepted region) is it replaced by a computed example that lies outside
 * every accepted value plus the tolerance and is graded WRONG before use. There is no list of candidates to
 * skip through, so the example does not act as an oracle over a set of values, and an ungraded example is
 * never returned. When the tip would exceed 120 characters, the short template `checking.tip.short` is used; when no
 * example can be graded WRONG (a degenerate spec), `checking.tip.noexample` is used and nothing is revealed.
 */
internal object FormatTips {
    private const val MAX_LENGTH = 120
    private val TIPS: Map<String, String> =
        ResourceBundle
            .getBundle(
                "messages",
                Locale.ROOT,
                FormatTips::class.java.classLoader,
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES),
            ).let { bundle ->
                (AnswerType.entries.map { it.name } + "short" + "noexample").associateWith { bundle.getString("checking.tip.$it") }
            }

    private val FIXED_INTEGER = BigInteger.valueOf(4827)
    private const val FIXED_FRACTION = "48/29"
    private const val FRACTION_DIGITS = "273951"

    fun tipFor(spec: AnswerSpec): String {
        val template = TIPS.getValue(spec.type.name)
        if (!template.contains("{0}")) return template
        val example = example(spec) ?: return TIPS.getValue("noexample")
        val tip = template.replace("{0}", example)
        return if (tip.length <= MAX_LENGTH) tip else TIPS.getValue("short").replace("{0}", example)
    }

    /**
     * The fixed example, else the computed far one, else the far one in its most compact spelling (a long
     * unit can push the others past the 100-code-point input limit). Null when none is graded WRONG.
     */
    private fun example(spec: AnswerSpec): String? {
        val far = farInteger(spec)
        return sequenceOf(
            { render(spec, FIXED_INTEGER, fixed = true) },
            { render(spec, far, fixed = false) },
            { compact(spec, far) },
        ).map { it() }.firstOrNull { isWrong(spec, it) }
    }

    private fun compact(
        spec: AnswerSpec,
        whole: BigInteger,
    ): String {
        val unit = spec.accepted.firstNotNullOfOrNull { UnitNotation.parse(Whitespace.trim(it))?.unit }
        return if (spec.type == AnswerType.NUMBER_WITH_UNIT && unit != null) "$whole${unit.display}" else whole.toString()
    }

    private fun isWrong(
        spec: AnswerSpec,
        example: String,
    ): Boolean = Grader.grade(spec, RawInput.Text(example))?.correct == false

    /** A whole number larger than every accepted magnitude plus the tolerance. */
    private fun farInteger(spec: AnswerSpec): BigInteger {
        val accepted =
            spec.accepted.mapNotNull { raw ->
                val text = Whitespace.trim(raw)
                when (spec.type) {
                    AnswerType.FRACTION -> FractionNotation.parse(text)?.value
                    AnswerType.NUMBER_WITH_UNIT -> UnitNotation.parse(text)?.number?.value
                    else -> NumericNotation.parse(text)?.value
                }
            }
        val bound =
            accepted.fold(Grader.tolerance(spec)) { acc, value ->
                val candidate = value.abs() + Grader.tolerance(spec)
                if (candidate > acc) candidate else acc
            }
        val ceiling =
            BigDecimal(bound.numerator).divide(BigDecimal(bound.denominator), 0, RoundingMode.CEILING).toBigIntegerExact()
        return ceiling + FIXED_INTEGER
    }

    private fun render(
        spec: AnswerSpec,
        whole: BigInteger,
        fixed: Boolean,
    ): String =
        when (spec.type) {
            AnswerType.INTEGER -> {
                whole.toString()
            }

            AnswerType.DECIMAL -> {
                val decimals = (spec.exactDecimals ?: 2).coerceIn(0, FRACTION_DIGITS.length)
                if (decimals == 0) whole.toString() else "$whole,${FRACTION_DIGITS.take(decimals)}"
            }

            AnswerType.FRACTION -> {
                if (fixed) FIXED_FRACTION else "${whole * BigInteger.TWO + BigInteger.ONE}/2"
            }

            else -> {
                val unit = spec.accepted.firstNotNullOfOrNull { UnitNotation.parse(Whitespace.trim(it))?.unit }
                "$whole,${FRACTION_DIGITS.take(2)}" + (unit?.let { " ${it.display}" } ?: "")
            }
        }
}
