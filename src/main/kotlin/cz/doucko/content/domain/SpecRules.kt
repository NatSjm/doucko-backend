package cz.doucko.content.domain

import cz.doucko.checking.AnswerChecker
import cz.doucko.checking.AnswerSpec
import cz.doucko.checking.AnswerType
import cz.doucko.checking.CheckResult
import cz.doucko.checking.Verdict
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/**
 * content-authoring "Answer spec authoring" (FR-6, CA-D20). Expected values are read with the checker's
 * own grammar by checking each value against a spec that accepts only that value (the public
 * [AnswerChecker.check]): unreadable input is UNPARSEABLE, and the normalized interpretation the
 * checker returns is the editor's `acceptedNormalized`. Values are stored trimmed, in the admin's
 * notation.
 */
object SpecRules {
    private val NUMERIC = setOf(AnswerType.INTEGER, AnswerType.DECIMAL, AnswerType.FRACTION, AnswerType.NUMBER_WITH_UNIT)
    private val CHOICE = setOf(AnswerType.CHOICE_SINGLE, AnswerType.CHOICE_MULTI)
    private val LIMIT = BigDecimal.TEN.pow(12)
    private val DECIMAL_PART = Regex("[.,](\\d+)$")

    fun validate(
        path: String,
        draft: SpecDraft,
    ): Validation<Spec> {
        val errors = mutableListOf<FieldError>()
        val type = draft.type.orNull()
        if (type == null) errors += FieldError("$path.type", "spec-type")
        val accepted = accepted(path, type, draft, errors)
        val allowEquivalent = flag("$path.allowEquivalent", draft.allowEquivalent, true, errors)
        val requireLowestTerms = flag("$path.requireLowestTerms", draft.requireLowestTerms, false, errors)
        val unitRequired = flag("$path.unitRequired", draft.unitRequired, true, errors)
        val diacriticsStrict = flag("$path.diacriticsStrict", draft.diacriticsStrict, false, errors)
        val tolerance = tolerance("$path.tolerance", type, draft.tolerance, errors)
        val exactDecimals = exactDecimals("$path.exactDecimals", type, draft.exactDecimals, errors)
        val options = options(path, type, draft.options, errors)
        if (type == null || accepted == null || errors.isNotEmpty()) return Validation.Invalid(errors)
        val spec =
            Spec(
                type,
                accepted,
                tolerance,
                exactDecimals,
                allowEquivalent,
                requireLowestTerms,
                unitRequired,
                diacriticsStrict,
                options,
            )
        values(path, spec, errors)
        return if (errors.isEmpty()) Validation.Valid(spec) else Validation.Invalid(errors)
    }

    /** The normalized interpretation of each accepted value (contract `acceptedNormalized`). */
    fun normalized(spec: Spec): List<String> =
        if (spec.type in CHOICE) {
            spec.accepted
        } else {
            spec.accepted.map { value -> self(spec.type, value, spec).normalized ?: value }
        }

    /** NUMBER_WITH_UNIT: the part's unit, shown next to the child's input (normalized display form). */
    fun unit(spec: Spec): String? =
        if (spec.type != AnswerType.NUMBER_WITH_UNIT) null else normalized(spec).firstOrNull()?.substringAfter(' ', "")?.ifEmpty { null }

    private fun accepted(
        path: String,
        type: AnswerType?,
        draft: SpecDraft,
        errors: MutableList<FieldError>,
    ): List<String>? {
        val items = (draft.accepted as? In.Ok)?.value
        if (draft.accepted is In.Invalid) return null.also { errors += FieldError("$path.accepted", "wrong-type") }
        if (items.isNullOrEmpty() && type in CHOICE) return null.also { errors += FieldError("$path.accepted", "choice-correct") }
        if (items == null || items.size !in 1..Limits.ACCEPTED) {
            return null.also { errors += FieldError("$path.accepted", "accepted-count", listOf(Limits.ACCEPTED)) }
        }
        val values =
            items.mapIndexed { j, item ->
                when (item) {
                    is In.Ok -> Text.trim(item.value)
                    is In.Missing -> null.also { errors += FieldError("$path.accepted[$j]", "required") }
                    is In.Invalid -> null.also { errors += FieldError("$path.accepted[$j]", "wrong-type") }
                }
            }
        return if (values.any { it == null }) null else values.filterNotNull()
    }

    private fun flag(
        field: String,
        value: In<Boolean>,
        default: Boolean,
        errors: MutableList<FieldError>,
    ): Boolean =
        when (value) {
            is In.Ok -> value.value
            is In.Missing -> default
            is In.Invalid -> default.also { errors += FieldError(field, "wrong-type") }
        }

    private fun tolerance(
        field: String,
        type: AnswerType?,
        value: In<String>,
        errors: MutableList<FieldError>,
    ): String? {
        val text =
            when (value) {
                is In.Missing -> return null
                is In.Invalid -> return null.also { errors += FieldError(field, "tolerance") }
                is In.Ok -> Text.trim(value.value).ifEmpty { return null }
            }
        if (type != null && type !in NUMERIC) return null.also { errors += FieldError(field, "tolerance-type") }
        val read = self(AnswerType.DECIMAL, text)
        if (read.verdict == Verdict.UNPARSEABLE || BigDecimal(read.normalized).signum() < 0) {
            return null.also { errors += FieldError(field, "tolerance") }
        }
        return text
    }

    private fun exactDecimals(
        field: String,
        type: AnswerType?,
        value: In<Int>,
        errors: MutableList<FieldError>,
    ): Int? {
        if (value is In.Missing) return null
        if (type != null && type != AnswerType.DECIMAL) return null.also { errors += FieldError(field, "exact-decimals-type") }
        val decimals = value.orNull()
        if (decimals == null || decimals !in 0..Limits.DECIMALS) return null.also { errors += FieldError(field, "exact-decimals-range") }
        return decimals
    }

    private fun options(
        path: String,
        type: AnswerType?,
        value: In<List<In<OptionDraft>>>,
        errors: MutableList<FieldError>,
    ): List<ChoiceOption> {
        val field = "$path.options"
        if (type != null && type !in CHOICE) {
            if (value is In.Invalid || (value is In.Ok && value.value.isNotEmpty())) errors += FieldError(field, "options-type")
            return emptyList()
        }
        if (type == null) return emptyList()
        val items =
            when (value) {
                is In.Ok -> value.value
                is In.Missing -> emptyList()
                is In.Invalid -> return emptyList<ChoiceOption>().also { errors += FieldError(field, "wrong-type") }
            }
        if (items.size !in Limits.OPTIONS_MIN..Limits.OPTIONS_MAX) errors += FieldError(field, "options-count")
        val seen = mutableSetOf<String>()
        return items.mapIndexedNotNull { i, item ->
            val option = (item as? In.Ok)?.value ?: return@mapIndexedNotNull null.also { errors += FieldError("$field[$i]", "wrong-type") }
            val expected = ('A' + i).toString()
            if (option.key.orNull() != expected) errors += FieldError("$field[$i].key", "option-key", listOf(expected))
            val text = Fields.required("$field[$i].text", option.text, Limits.OPTION_TEXT, errors)
            if (text != null && !seen.add(Text.trim(text).lowercase())) {
                errors += FieldError("$field[$i].text", "option-duplicate")
            }
            text?.let { ChoiceOption(expected, it) }
        }
    }

    /** Per-value rules of the part's type; [spec] already has a valid type, count and options. */
    private fun values(
        path: String,
        spec: Spec,
        errors: MutableList<FieldError>,
    ) {
        val seenKeys = mutableSetOf<String>()
        var firstUnit: String? = null
        spec.accepted.forEachIndexed { j, value ->
            val field = "$path.accepted[$j]"
            val error: FieldError? =
                when (spec.type) {
                    AnswerType.INTEGER, AnswerType.DECIMAL -> {
                        number(field, spec, value)
                    }

                    AnswerType.FRACTION -> {
                        fraction(field, spec, value)
                    }

                    AnswerType.NUMBER_WITH_UNIT -> {
                        numberWithUnit(field, value) ?: run {
                            val unit = unitOf(self(AnswerType.NUMBER_WITH_UNIT, value).normalized!!)
                            if (j == 0) {
                                firstUnit = unit
                                null
                            } else {
                                val first = spec.accepted.first()
                                val shown = self(AnswerType.NUMBER_WITH_UNIT, value, accepted = first).normalized
                                if (firstUnit != null && shown != null && unitOf(shown) != firstUnit) {
                                    FieldError(field, "unit-mismatch", listOf(firstUnit!!))
                                } else {
                                    null
                                }
                            }
                        }
                    }

                    AnswerType.CHOICE_SINGLE, AnswerType.CHOICE_MULTI -> {
                        when {
                            spec.options.none { it.key == value } -> FieldError(field, "choice-unknown-key")
                            !seenKeys.add(value) -> FieldError(field, "duplicate")
                            else -> null
                        }
                    }

                    AnswerType.TEXT -> {
                        when {
                            value.isEmpty() -> FieldError(field, "text-answer-empty")
                            Text.codePoints(value) > Limits.TEXT_ANSWER -> FieldError(field, "too-long", listOf(Limits.TEXT_ANSWER))
                            else -> null
                        }
                    }
                }
            if (error != null) errors += error
        }
    }

    private fun number(
        field: String,
        spec: Spec,
        value: String,
    ): FieldError? {
        val read = self(AnswerType.DECIMAL, value)
        if (read.verdict == Verdict.UNPARSEABLE) return FieldError(field, "number-unreadable")
        val number = BigDecimal(read.normalized)
        val decimals = decimals(value)
        if (outOfRange(number, decimals)) return FieldError(field, "number-range")
        if (spec.type == AnswerType.INTEGER && number.stripTrailingZeros().scale() > 0) return FieldError(field, "not-whole")
        val exact = spec.exactDecimals
        if (spec.type == AnswerType.DECIMAL && exact != null && decimals != exact) {
            val example = number.setScale(exact, RoundingMode.HALF_UP).toPlainString().replace('.', ',')
            return FieldError(field, "exact-decimals", listOf(exact, example))
        }
        return null
    }

    private fun fraction(
        field: String,
        spec: Spec,
        value: String,
    ): FieldError? {
        val read = self(AnswerType.FRACTION, value, lowestTerms = true)
        val normalized = read.normalized ?: return FieldError(field, "fraction-unreadable")
        if ('/' in normalized) {
            val numerator = BigInteger(normalized.substringBefore('/'))
            val denominator = BigInteger(normalized.substringAfter('/'))
            if (numerator.abs() >= LIMIT.toBigInteger() * denominator) return FieldError(field, "number-range")
            if (spec.requireLowestTerms && read.verdict == Verdict.WRONG) {
                val gcd = numerator.gcd(denominator)
                val n = numerator / gcd
                val d = denominator / gcd
                return FieldError(field, "not-lowest", listOf(if (d == BigInteger.ONE) "$n" else "$n/$d"))
            }
        } else if (outOfRange(BigDecimal(normalized), decimals(value))) {
            return FieldError(field, "number-range")
        }
        return null
    }

    private fun numberWithUnit(
        field: String,
        value: String,
    ): FieldError? {
        val read = self(AnswerType.NUMBER_WITH_UNIT, value)
        return when {
            read.verdict == Verdict.UNPARSEABLE -> {
                FieldError(field, if (value.any(Char::isDigit)) "unit-unreadable" else "unit-both")
            }

            read.verdict == Verdict.WRONG -> {
                FieldError(field, "unit-both")
            }

            else -> {
                val number = read.normalized!!.substringBefore(' ')
                val numberText = value.takeWhile { !it.isLetter() && it != '°' }.trim()
                if (outOfRange(BigDecimal(number), decimals(numberText))) FieldError(field, "number-range") else null
            }
        }
    }

    private fun outOfRange(
        number: BigDecimal,
        decimals: Int,
    ) = number.abs() >= LIMIT || decimals > Limits.DECIMALS

    /** Decimal places as typed (`0,50` has 2). */
    private fun decimals(value: String): Int =
        DECIMAL_PART
            .find(value)
            ?.groupValues
            ?.get(1)
            ?.length ?: 0

    private fun unitOf(normalized: String) = normalized.substringAfter(' ', "")

    /** Checks [value] against a spec of [type] that accepts only [accepted] (default: the value itself). */
    private fun self(
        type: AnswerType,
        value: String,
        base: Spec? = null,
        accepted: String = value,
        lowestTerms: Boolean = false,
    ): CheckResult {
        val spec =
            base?.toChecker()?.copy(accepted = listOf(accepted), tolerance = null)
                ?: AnswerSpec(type, listOf(accepted), requireLowestTerms = lowestTerms, unitRequired = true)
        return AnswerChecker.check(spec, value)
    }
}
