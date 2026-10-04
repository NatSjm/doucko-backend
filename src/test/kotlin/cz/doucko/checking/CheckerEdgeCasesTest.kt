package cz.doucko.checking

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * Safety nets for specs and input shapes the API layer refuses first (design.md, Error handling), and the
 * computed format-tip example (review F01, F02, F04, F07, F08).
 */
class CheckerEdgeCasesTest {
    private fun tip(spec: AnswerSpec): String {
        val result = AnswerChecker.check(spec, "x?")
        result.verdict shouldBe Verdict.UNPARSEABLE
        TipRules.violations(spec, result.formatTip).shouldBeEmpty()
        return result.formatTip!!
    }

    // @trace FR-14
    @Test
    fun `an input shape that does not match the part type is unparseable`() {
        val choice = AnswerSpec(AnswerType.CHOICE_SINGLE, listOf("A"), options = listOf("A", "B"))
        AnswerChecker.check(choice, "A").verdict shouldBe Verdict.UNPARSEABLE
        AnswerChecker.check(AnswerSpec(AnswerType.TEXT, listOf("pes")), RawInput.Selection(listOf("pes"))).verdict shouldBe
            Verdict.UNPARSEABLE
    }

    // @trace FR-21
    @Test
    fun `a negative or unreadable tolerance means exact comparison`() {
        for (tolerance in listOf("-1", "abc", " 0,5 ")) {
            val spec = AnswerSpec(AnswerType.DECIMAL, listOf("3"), tolerance = tolerance)
            AnswerChecker.check(spec, "3").verdict shouldBe Verdict.CORRECT
        }
        AnswerChecker.check(AnswerSpec(AnswerType.DECIMAL, listOf("3"), tolerance = "-1"), "3,4").verdict shouldBe Verdict.WRONG
        AnswerChecker.check(AnswerSpec(AnswerType.DECIMAL, listOf("3"), tolerance = " 0,5 "), "3,4").verdict shouldBe Verdict.CORRECT
    }

    // @trace FR-15
    @Test
    fun `exactDecimals outside 0 to 6 never throws`() {
        tip(AnswerSpec(AnswerType.DECIMAL, listOf("1"), exactDecimals = -3))
        tip(AnswerSpec(AnswerType.DECIMAL, listOf("1"), exactDecimals = 9))
    }

    // @trace FR-14
    @Test
    fun `the fixed example is replaced only when the part accepts it`() {
        tip(AnswerSpec(AnswerType.INTEGER, listOf("1"))) shouldContain "třeba 4827."
        tip(AnswerSpec(AnswerType.INTEGER, listOf("4827"))) shouldNotContain "třeba 4827."
        tip(AnswerSpec(AnswerType.DECIMAL, listOf("4827"), exactDecimals = 0)) shouldNotContain "třeba 4827."
        tip(AnswerSpec(AnswerType.FRACTION, listOf("48/29"))) shouldNotContain "48/29"
        tip(AnswerSpec(AnswerType.FRACTION, listOf("-3 1/2"), tolerance = "100000"))
        tip(AnswerSpec(AnswerType.NUMBER_WITH_UNIT, listOf("4827.27 m"))) shouldContain " m."
        tip(AnswerSpec(AnswerType.NUMBER_WITH_UNIT, listOf("4827,27"), unitRequired = false))
        tip(AnswerSpec(AnswerType.INTEGER, listOf("not a number")))
    }

    // @trace FR-14
    @Test
    fun `a tip with a long unit falls back to the short template`() {
        val unit = "j".repeat(70)
        val tip = tip(AnswerSpec(AnswerType.NUMBER_WITH_UNIT, listOf("7 $unit")))
        tip shouldContain "Zkus třeba"
        (tip.length <= 120) shouldBe true
    }

    // @trace FR-14
    @Test
    fun `a spec where no example fits gets a tip without an example and never throws`() {
        val spec = AnswerSpec(AnswerType.NUMBER_WITH_UNIT, listOf("1 " + "j".repeat(98)))
        val result = AnswerChecker.check(spec, "x?")
        result.verdict shouldBe Verdict.UNPARSEABLE
        result.formatTip!! shouldContain "Napiš"
        result.formatTip shouldNotContain "třeba"
    }
}
