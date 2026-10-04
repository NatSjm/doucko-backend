package cz.doucko.checking.domain

import cz.doucko.checking.AnswerSpec
import cz.doucko.checking.AnswerType
import cz.doucko.checking.RawInput

/** A readable input's outcome: CORRECT or WRONG, with its normalized interpretation. */
internal data class Graded(
    val correct: Boolean,
    val normalized: String,
)

/**
 * Applies the answer-checking rules of the part's type. Returns null for input those rules cannot read
 * (UNPARSEABLE). An accepted value the checker cannot read never matches.
 */
internal object Grader {
    /** Free-text inputs longer than this many code points after trimming are UNPARSEABLE. */
    const val MAX_CODE_POINTS = 100

    fun grade(
        spec: AnswerSpec,
        input: RawInput,
    ): Graded? {
        if (spec.type == AnswerType.CHOICE_SINGLE || spec.type == AnswerType.CHOICE_MULTI) {
            return choice(spec, (input as? RawInput.Selection)?.keys ?: return null)
        }
        val text = Whitespace.trim((input as? RawInput.Text)?.value ?: return null)
        if (text.isEmpty() || text.codePointCount(0, text.length) > MAX_CODE_POINTS) return null
        return when (spec.type) {
            AnswerType.INTEGER, AnswerType.DECIMAL -> number(spec, text)
            AnswerType.FRACTION -> fraction(spec, text)
            AnswerType.NUMBER_WITH_UNIT -> numberWithUnit(spec, text)
            else -> text(spec, text)
        }
    }

    private fun tolerance(spec: AnswerSpec): Rational =
        spec.tolerance
            ?.let { NumericNotation.parse(Whitespace.trim(it))?.value }
            ?.takeIf { it >= Rational.ZERO } ?: Rational.ZERO

    private fun acceptedNumbers(spec: AnswerSpec): List<Rational> =
        spec.accepted.mapNotNull { NumericNotation.parse(Whitespace.trim(it))?.value }

    private fun number(
        spec: AnswerSpec,
        text: String,
    ): Graded? {
        val number = NumericNotation.parse(text) ?: return null
        val tolerance = tolerance(spec)
        val formOk = spec.type != AnswerType.DECIMAL || spec.exactDecimals == null || number.decimals == spec.exactDecimals
        val correct = formOk && acceptedNumbers(spec).any { Rational.within(number.value, it, tolerance) }
        return Graded(correct, number.normalized)
    }

    private fun fraction(
        spec: AnswerSpec,
        text: String,
    ): Graded? {
        val form = FractionNotation.parse(text) ?: return null
        val accepted = spec.accepted.mapNotNull { FractionNotation.parse(Whitespace.trim(it)) }
        val correct =
            if (spec.allowEquivalent) {
                val tolerance = tolerance(spec)
                !(spec.requireLowestTerms && FractionNotation.notInLowestTerms(form)) &&
                    accepted.any { Rational.within(form.value, it.value, tolerance) }
            } else {
                accepted.any { FractionNotation.sameForm(form, it) }
            }
        return Graded(correct, form.normalized)
    }

    private fun numberWithUnit(
        spec: AnswerSpec,
        text: String,
    ): Graded? {
        val input = UnitNotation.parse(text) ?: return null
        val accepted = spec.accepted.mapNotNull { UnitNotation.parse(Whitespace.trim(it)) }
        val partUnit = accepted.firstOrNull()?.unit
        val tolerance = tolerance(spec)
        val unitOk =
            if (input.unit == null) {
                !spec.unitRequired
            } else {
                partUnit != null && input.unit.key == partUnit.key
            }
        val correct = unitOk && accepted.any { Rational.within(input.number.value, it.number.value, tolerance) }
        val shownUnit =
            when {
                input.unit == null -> null
                partUnit != null && input.unit.key == partUnit.key -> partUnit.display
                else -> input.unit.display
            }
        return Graded(correct, input.number.normalized + (shownUnit?.let { " $it" } ?: ""))
    }

    private fun choice(
        spec: AnswerSpec,
        keys: List<String>,
    ): Graded? {
        val selected = keys.distinct()
        if (selected.isEmpty() || selected.any { it !in spec.options }) return null
        if (spec.type == AnswerType.CHOICE_SINGLE && selected.size > 1) return null
        val normalized = spec.options.filter { it in selected }.joinToString(",")
        val correct =
            if (spec.type == AnswerType.CHOICE_SINGLE) {
                selected.single() in spec.accepted
            } else {
                selected.toSet() == spec.accepted.toSet()
            }
        return Graded(correct, normalized)
    }

    private fun text(
        spec: AnswerSpec,
        text: String,
    ): Graded {
        val key = TextMatching.comparisonKey(text, spec.diacriticsStrict)
        val correct = spec.accepted.any { TextMatching.comparisonKey(it, spec.diacriticsStrict) == key }
        return Graded(correct, TextMatching.canonical(text))
    }
}
