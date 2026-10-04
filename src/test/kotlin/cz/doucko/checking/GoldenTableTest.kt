package cz.doucko.checking

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * answer-checking "Golden table" (FR-23): at least 150 self-authored cases, at least 5 per verdict-producing
 * requirement, each run as its own named test so a regression names the failing case.
 */
class GoldenTableTest {
    private val cases = GoldenTable.cases

    // @trace FR-23
    @Test
    fun `the table has at least 150 cases with unique ids`() {
        cases.size shouldBeGreaterThanOrEqual 150
        cases
            .groupBy { it.id }
            .filterValues { it.size > 1 }
            .keys
            .shouldBeEmpty()
    }

    // @trace FR-23
    @Test
    fun `each of the 12 verdict-producing requirements has at least 5 cases`() {
        val byRequirement = cases.groupingBy { it.requirement }.eachCount()
        byRequirement.keys shouldContainExactlyInAnyOrder GoldenTable.REQUIREMENTS
        byRequirement.filterValues { it < 5 }.keys.shouldBeEmpty()
    }

    // @trace FR-23
    @Test
    fun `the regression example of the spec is a golden case`() {
        val case =
            cases.single {
                it.spec.type == AnswerType.INTEGER &&
                    it.spec.accepted == listOf("1000") &&
                    it.input == RawInput.Text("1 000")
            }
        case.verdict shouldBe Verdict.CORRECT
    }

    // @trace FR-14
    // @trace FR-15
    // @trace FR-16
    // @trace FR-17
    // @trace FR-18
    // @trace FR-19
    // @trace FR-20
    // @trace FR-21
    // @trace FR-23
    @TestFactory
    fun `golden cases`(): List<DynamicTest> =
        cases.map { case ->
            DynamicTest.dynamicTest(case.toString()) {
                val result = AnswerChecker.check(case.spec, case.input)
                withClue(case.toString()) {
                    result.verdict shouldBe case.verdict
                    result.normalized shouldBe case.normalized
                    if (case.verdict == Verdict.UNPARSEABLE) {
                        TipRules.violations(case.spec, result.formatTip).shouldBeEmpty()
                    } else {
                        result.formatTip.shouldBeNull()
                    }
                    AnswerChecker.check(case.spec, case.input) shouldBe result
                }
            }
        }
}
