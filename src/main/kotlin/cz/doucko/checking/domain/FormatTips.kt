package cz.doucko.checking.domain

import cz.doucko.checking.AnswerSpec
import cz.doucko.checking.AnswerType
import cz.doucko.checking.RawInput
import java.util.Locale
import java.util.ResourceBundle

/**
 * Czech format tips for UNPARSEABLE parts (answer-checking "Format tip for unreadable input"). The text comes
 * from the backend message catalog `messages.properties`, keys `checking.tip.<TYPE>` (NFR-9), read with
 * `ResourceBundle` so the module needs no Spring. A numeric tip's `{0}` is filled with the first example
 * that the same part grades WRONG, so it is readable and never reveals an accepted answer.
 */
internal object FormatTips {
    private val TIPS: Map<AnswerType, String> =
        ResourceBundle
            .getBundle(
                "messages",
                Locale.ROOT,
                FormatTips::class.java.classLoader,
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES),
            ).let { bundle -> AnswerType.entries.associateWith { bundle.getString("checking.tip.${it.name}") } }

    private val DECIMAL_EXAMPLES = listOf("5,2", "3,75", "12,4", "0,6", "-8,25", "987,65")
    private val NUMBER_EXAMPLES =
        mapOf(
            AnswerType.INTEGER to listOf("12", "305", "48", "-7", "9999", "-271828"),
            AnswerType.FRACTION to listOf("3/4", "2/5", "5/7", "1 1/3", "-11/13", "9876/5"),
            AnswerType.NUMBER_WITH_UNIT to listOf("5,2", "3,75", "12", "-48", "987,65", "-271828"),
        )
    private const val FRACTION_DIGITS = "253197"

    fun tipFor(spec: AnswerSpec): String {
        val template = TIPS.getValue(spec.type)
        return if (template.contains("{0}")) template.replace("{0}", example(spec)) else template
    }

    private fun example(spec: AnswerSpec): String {
        val candidates =
            when (spec.type) {
                AnswerType.DECIMAL -> {
                    decimalExamples(spec.exactDecimals)
                }

                AnswerType.NUMBER_WITH_UNIT -> {
                    val unit = spec.accepted.firstNotNullOfOrNull { UnitNotation.parse(Whitespace.trim(it))?.unit }
                    NUMBER_EXAMPLES.getValue(spec.type).map { if (unit == null) it else "$it ${unit.display}" }
                }

                else -> {
                    NUMBER_EXAMPLES.getValue(spec.type)
                }
            }
        return candidates.firstOrNull { Grader.grade(spec, RawInput.Text(it))?.correct == false } ?: candidates.last()
    }

    private fun decimalExamples(exactDecimals: Int?): List<String> =
        if (exactDecimals == null) {
            DECIMAL_EXAMPLES
        } else {
            listOf("5", "3", "12", "-8", "47", "-806").map { whole ->
                if (exactDecimals == 0) whole else whole + "," + FRACTION_DIGITS.take(exactDecimals)
            }
        }
}
