package cz.doucko.content

import cz.doucko.checking.AnswerType
import cz.doucko.content.domain.In
import cz.doucko.content.domain.OptionDraft
import cz.doucko.content.domain.Spec
import cz.doucko.content.domain.SpecDraft
import cz.doucko.content.domain.SpecRules
import cz.doucko.content.domain.Validation
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** content-authoring "Answer spec authoring" (FR-6, CA-D20): expected values read with the checker's grammar. */
class SpecRulesTest {
    private fun draft(
        type: AnswerType?,
        vararg accepted: String,
        tolerance: String? = null,
        exactDecimals: In<Int> = In.Missing,
        lowestTerms: Boolean? = null,
        unitRequired: Boolean? = null,
        options: List<String>? = null,
        keys: List<String>? = null,
    ) = SpecDraft(
        type = type?.let { In.Ok(it) } ?: In.Missing,
        accepted = In.Ok(accepted.map { In.Ok(it) }),
        tolerance = tolerance?.let { In.Ok(it) } ?: In.Missing,
        exactDecimals = exactDecimals,
        requireLowestTerms = lowestTerms?.let { In.Ok(it) } ?: In.Missing,
        unitRequired = unitRequired?.let { In.Ok(it) } ?: In.Missing,
        options =
            options?.let { texts ->
                In.Ok(texts.mapIndexed { i, t -> In.Ok(OptionDraft(In.Ok(keys?.get(i) ?: ('A' + i).toString()), In.Ok(t))) })
            } ?: In.Missing,
    )

    private fun valid(draft: SpecDraft): Spec {
        val result = SpecRules.validate("parts[0].spec", draft)
        result.shouldBeInstanceOf<Validation.Valid<Spec>>()
        return result.value
    }

    private fun errors(draft: SpecDraft): List<Pair<String, String>> {
        val result = SpecRules.validate("parts[0].spec", draft)
        result.shouldBeInstanceOf<Validation.Invalid>()
        return result.errors.map { it.field to it.code }
    }

    private fun args(draft: SpecDraft) = (SpecRules.validate("parts[0].spec", draft) as Validation.Invalid).errors.single().args

    // @trace FR-6
    @Test
    fun `Czech notation with a no-break space is stored as entered and normalized`() {
        val spec = valid(draft(AnswerType.DECIMAL, "1 250,75"))
        spec.accepted shouldContainExactly listOf("1 250,75")
        SpecRules.normalized(spec) shouldContainExactly listOf("1250.75")
    }

    // @trace FR-6
    @Test
    fun `a decimal dot is accepted`() {
        valid(draft(AnswerType.DECIMAL, "0.5")).accepted shouldContainExactly listOf("0.5")
    }

    // @trace FR-6
    @ParameterizedTest
    @ValueSource(strings = ["1.250,75", "1,250.75", "5,,2", "abc", "5.2.1"])
    fun `unreadable numbers are refused on the value`(value: String) {
        errors(draft(AnswerType.DECIMAL, value)) shouldContainExactly listOf("parts[0].spec.accepted[0]" to "number-unreadable")
    }

    // @trace FR-6
    @ParameterizedTest
    @ValueSource(strings = ["1000000000000", "99999999999999999999", "0,1234567", "-1000000000000"])
    fun `overflowing or over-precise values are refused`(value: String) {
        errors(draft(AnswerType.DECIMAL, value)) shouldContainExactly listOf("parts[0].spec.accepted[0]" to "number-range")
    }

    // @trace FR-6
    @Test
    fun `the largest allowed values are accepted`() {
        valid(draft(AnswerType.DECIMAL, "999 999 999 999", "0,123456", "-999999999999,999999"))
    }

    // @trace FR-6
    @Test
    fun `an integer part with a fractional value suggests DECIMAL`() {
        errors(draft(AnswerType.INTEGER, "12,5")) shouldContainExactly listOf("parts[0].spec.accepted[0]" to "not-whole")
        valid(draft(AnswerType.INTEGER, "12 500"))
    }

    // @trace FR-6
    @Test
    fun `an exact form the expected value contradicts is refused with the padded value`() {
        val draft = draft(AnswerType.DECIMAL, "0,5", exactDecimals = In.Ok(2))
        errors(draft) shouldContainExactly listOf("parts[0].spec.accepted[0]" to "exact-decimals")
        args(draft) shouldContainExactly listOf(2, "0,50")
        valid(draft(AnswerType.DECIMAL, "0,50", exactDecimals = In.Ok(2))).exactDecimals shouldBe 2
    }

    // @trace FR-6
    @Test
    fun `exact decimals must be 0 to 6 and only on DECIMAL`() {
        errors(draft(AnswerType.DECIMAL, "1", exactDecimals = In.Ok(7))) shouldContainExactly
            listOf("parts[0].spec.exactDecimals" to "exact-decimals-range")
        errors(draft(AnswerType.DECIMAL, "1", exactDecimals = In.Invalid)) shouldContainExactly
            listOf("parts[0].spec.exactDecimals" to "exact-decimals-range")
        errors(draft(AnswerType.INTEGER, "1", exactDecimals = In.Ok(2))) shouldContainExactly
            listOf("parts[0].spec.exactDecimals" to "exact-decimals-type")
    }

    // @trace FR-6
    @ParameterizedTest
    @ValueSource(strings = ["3/0", "1//2", "1/", "/2"])
    fun `invalid fractions are refused`(value: String) {
        errors(draft(AnswerType.FRACTION, value)) shouldContainExactly listOf("parts[0].spec.accepted[0]" to "fraction-unreadable")
    }

    // @trace FR-6
    @Test
    fun `lowest terms contradicting the expected value is refused with the reduced form`() {
        val draft = draft(AnswerType.FRACTION, "2/4", lowestTerms = true)
        errors(draft) shouldContainExactly listOf("parts[0].spec.accepted[0]" to "not-lowest")
        args(draft) shouldContainExactly listOf("1/2")
        valid(draft(AnswerType.FRACTION, "2/4")).requireLowestTerms shouldBe false
    }

    // @trace FR-6
    @Test
    fun `a mixed number is stored as entered and normalized to an improper fraction`() {
        val spec = valid(draft(AnswerType.FRACTION, "1 1/2"))
        spec.accepted shouldContainExactly listOf("1 1/2")
        SpecRules.normalized(spec) shouldContainExactly listOf("3/2")
    }

    // @trace FR-6
    @ParameterizedTest
    @ValueSource(strings = ["24", "cm²"])
    fun `a number with unit needs both, even when the unit is optional`(value: String) {
        errors(draft(AnswerType.NUMBER_WITH_UNIT, value, unitRequired = false)) shouldContainExactly
            listOf("parts[0].spec.accepted[0]" to "unit-both")
    }

    // @trace FR-6
    @Test
    fun `a unit spelling is stored as entered and shown normalized, and is the part's unit`() {
        val spec = valid(draft(AnswerType.NUMBER_WITH_UNIT, "24 cm2"))
        spec.accepted shouldContainExactly listOf("24 cm2")
        SpecRules.normalized(spec) shouldContainExactly listOf("24 cm²")
        SpecRules.unit(spec) shouldBe "cm²"
    }

    // @trace FR-6
    @Test
    fun `accepted values in different units are refused, no conversion`() {
        errors(draft(AnswerType.NUMBER_WITH_UNIT, "24 cm²", "2400 mm²")) shouldContainExactly
            listOf("parts[0].spec.accepted[1]" to "unit-mismatch")
        valid(draft(AnswerType.NUMBER_WITH_UNIT, "24 cm²", "24,0 cm2"))
    }

    // @trace FR-6
    @Test
    fun `single choice options are lettered and the marked option is the answer`() {
        val spec = valid(draft(AnswerType.CHOICE_SINGLE, "B", options = listOf("3", "4", "5")))
        spec.options.map { it.key to it.text } shouldContainExactly listOf("A" to "3", "B" to "4", "C" to "5")
        spec.accepted shouldContainExactly listOf("B")
    }

    // @trace FR-6
    @Test
    fun `invalid single choice specs name the problem`() {
        errors(draft(AnswerType.CHOICE_SINGLE, "A", options = listOf("4"))) shouldContain ("parts[0].spec.options" to "options-count")
        errors(draft(AnswerType.CHOICE_SINGLE, options = listOf("3", "4"))) shouldContainExactly
            listOf("parts[0].spec.accepted" to "choice-correct")
        errors(draft(AnswerType.CHOICE_SINGLE, "A", options = listOf("4", " 4 "))) shouldContainExactly
            listOf("parts[0].spec.options[1].text" to "option-duplicate")
        errors(draft(AnswerType.CHOICE_SINGLE, "A", options = listOf("x", " "))) shouldContainExactly
            listOf("parts[0].spec.options[1].text" to "required")
        errors(draft(AnswerType.CHOICE_SINGLE, "E", options = listOf("3", "4"))) shouldContainExactly
            listOf("parts[0].spec.accepted[0]" to "choice-unknown-key")
        errors(draft(AnswerType.CHOICE_SINGLE, "A", options = listOf("3", "4"), keys = listOf("A", "C"))) shouldContainExactly
            listOf("parts[0].spec.options[1].key" to "option-key")
    }

    // @trace FR-6
    @Test
    fun `single choice may list several correct options, multi choice one set`() {
        valid(draft(AnswerType.CHOICE_SINGLE, "A", "D", options = listOf("1", "2", "3", "4"))).accepted shouldContainExactly
            listOf("A", "D")
        valid(draft(AnswerType.CHOICE_MULTI, "A", "C", options = listOf("1", "2", "3", "4"))).accepted shouldContainExactly
            listOf("A", "C")
        errors(draft(AnswerType.CHOICE_MULTI, options = listOf("1", "2"))) shouldContainExactly
            listOf("parts[0].spec.accepted" to "choice-correct")
        errors(draft(AnswerType.CHOICE_MULTI, "A", "A", options = listOf("1", "2"))) shouldContainExactly
            listOf("parts[0].spec.accepted[1]" to "duplicate")
    }

    // @trace FR-6
    @Test
    fun `text answers are 1 to 100 code points`() {
        valid(draft(AnswerType.TEXT, "pravý úhel", "pravý")).accepted shouldContainExactly listOf("pravý úhel", "pravý")
        valid(draft(AnswerType.TEXT, "ž".repeat(100)))
        errors(draft(AnswerType.TEXT, "   ")) shouldContainExactly listOf("parts[0].spec.accepted[0]" to "text-answer-empty")
        errors(draft(AnswerType.TEXT, "a".repeat(101))) shouldContainExactly listOf("parts[0].spec.accepted[0]" to "too-long")
    }

    // @trace FR-6
    @ParameterizedTest
    @ValueSource(strings = ["-0,1", "abc"])
    fun `tolerance must be a number of at least 0`(tolerance: String) {
        errors(draft(AnswerType.DECIMAL, "1", tolerance = tolerance)) shouldContainExactly listOf("parts[0].spec.tolerance" to "tolerance")
    }

    // @trace FR-6
    @Test
    fun `tolerance is offered only for numeric parts`() {
        valid(draft(AnswerType.DECIMAL, "1", tolerance = "0,05")).tolerance shouldBe "0,05"
        errors(draft(AnswerType.TEXT, "a", tolerance = "1")) shouldContainExactly listOf("parts[0].spec.tolerance" to "tolerance-type")
    }

    // @trace FR-6
    @Test
    fun `accepted answers are 1 to 10 and a type is required`() {
        errors(draft(AnswerType.INTEGER)) shouldContainExactly listOf("parts[0].spec.accepted" to "accepted-count")
        errors(draft(AnswerType.INTEGER, *Array(11) { "$it" })) shouldContainExactly listOf("parts[0].spec.accepted" to "accepted-count")
        errors(draft(null, "1")) shouldContainExactly listOf("parts[0].spec.type" to "spec-type")
        errors(SpecDraft(type = In.Ok(AnswerType.DECIMAL))) shouldContainExactly listOf("parts[0].spec.accepted" to "accepted-count")
    }

    // @trace FR-6
    @Test
    fun `fraction and unit values obey the numeric bounds`() {
        errors(draft(AnswerType.FRACTION, "1000000000000/1")) shouldContainExactly listOf("parts[0].spec.accepted[0]" to "number-range")
        errors(draft(AnswerType.FRACTION, "0,1234567")) shouldContainExactly listOf("parts[0].spec.accepted[0]" to "number-range")
        valid(draft(AnswerType.FRACTION, "0,5", "-3/4"))
        errors(draft(AnswerType.NUMBER_WITH_UNIT, "1000000000000 m")) shouldContainExactly
            listOf("parts[0].spec.accepted[0]" to "number-range")
        errors(draft(AnswerType.NUMBER_WITH_UNIT, "2,,4 m")) shouldContainExactly listOf("parts[0].spec.accepted[0]" to "unit-unreadable")
        val zero = draft(AnswerType.FRACTION, "0/5", lowestTerms = true)
        errors(zero) shouldContainExactly listOf("parts[0].spec.accepted[0]" to "not-lowest")
        args(zero) shouldContainExactly listOf("0")
    }

    // @trace FR-6
    @Test
    fun `wrong JSON types inside a spec are refused on their path`() {
        val base = draft(AnswerType.DECIMAL, "1")
        errors(base.copy(accepted = In.Invalid)) shouldContainExactly listOf("parts[0].spec.accepted" to "wrong-type")
        errors(base.copy(accepted = In.Ok(listOf(In.Missing)))) shouldContainExactly listOf("parts[0].spec.accepted[0]" to "required")
        errors(base.copy(accepted = In.Ok(listOf(In.Invalid)))) shouldContainExactly listOf("parts[0].spec.accepted[0]" to "wrong-type")
        errors(base.copy(unitRequired = In.Invalid)) shouldContainExactly listOf("parts[0].spec.unitRequired" to "wrong-type")
        errors(base.copy(tolerance = In.Invalid)) shouldContainExactly listOf("parts[0].spec.tolerance" to "tolerance")
        valid(base.copy(tolerance = In.Ok("  "))).tolerance shouldBe null
        errors(base.copy(options = In.Invalid)) shouldContainExactly listOf("parts[0].spec.options" to "options-type")
        val choice = draft(AnswerType.CHOICE_MULTI, "A", options = listOf("1", "2"))
        errors(choice.copy(options = In.Invalid)) shouldContain ("parts[0].spec.options" to "wrong-type")
        errors(choice.copy(options = In.Ok(listOf(In.Invalid, In.Invalid)))) shouldContain ("parts[0].spec.options[0]" to "wrong-type")
        errors(choice.copy(options = In.Missing)) shouldContain ("parts[0].spec.options" to "options-count")
        valid(draft(AnswerType.TEXT, "Praha").copy(diacriticsStrict = In.Ok(true))).diacriticsStrict shouldBe true
        valid(draft(AnswerType.FRACTION, "1/2").copy(allowEquivalent = In.Ok(false))).allowEquivalent shouldBe false
    }

    // @trace FR-6
    @Test
    fun `options on a non-choice part are refused`() {
        errors(draft(AnswerType.INTEGER, "1", options = listOf("a", "b"))) shouldContainExactly
            listOf("parts[0].spec.options" to "options-type")
    }
}
