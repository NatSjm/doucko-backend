package cz.doucko.checking

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.math.BigInteger

/** Property tests for the checker (store spec answer-checking): notation variants, determinism, totality. */
class AnswerCheckerPropertyTest {
    private val config = PropTestConfig(seed = 20261004L)
    private val separators = listOf(" ", " ", " ")

    private fun grouped(
        digits: String,
        separator: () -> String,
    ): String {
        val head = digits.length % 3
        val groups = mutableListOf<String>()
        if (head > 0) groups += digits.take(head)
        groups += digits.drop(head).chunked(3)
        return groups.mapIndexed { i, g -> if (i == 0) g else separator() + g }.joinToString("")
    }

    // @trace FR-14
    @Test
    fun `any integer in grouped, signed or padded form is correct with its plain normalized value`(): Unit =
        runBlocking {
            checkAll(config, Arb.long(), Arb.element(separators), Arb.element(listOf("", "+", "-", "−", "–"))) { n, sep, sign ->
                val magnitude = BigInteger.valueOf(n).abs()
                val negative = sign == "-" || sign == "−" || sign == "–"
                val expected = if (negative) magnitude.negate() else magnitude
                val spec = AnswerSpec(AnswerType.INTEGER, listOf(expected.toString()))
                val input = "  " + sign + grouped(magnitude.toString()) { sep } + " "
                val result = AnswerChecker.check(spec, input)
                result.verdict shouldBe Verdict.CORRECT
                result.normalized shouldBe expected.toString()
            }
        }

    // @trace FR-15
    @Test
    fun `decimal comma, decimal dot and trailing zeros are the same value`(): Unit =
        runBlocking {
            checkAll(config, Arb.long(-1_000_000L..1_000_000L), Arb.int(1..6), Arb.int(0..3)) { units, scale, zeros ->
                val value = java.math.BigDecimal.valueOf(units, scale)
                val plain = value.stripTrailingZeros().toPlainString().let { if (value.signum() == 0) "0" else it }
                val spec = AnswerSpec(AnswerType.DECIMAL, listOf(plain))
                val typed = value.toPlainString() + "0".repeat(zeros)
                for (input in listOf(typed, typed.replace('.', ','))) {
                    val result = AnswerChecker.check(spec, input)
                    result.verdict shouldBe Verdict.CORRECT
                    result.normalized shouldBe plain
                }
            }
        }

    // @trace FR-16
    @Test
    fun `a scaled fraction is correct when equivalents are allowed and wrong when they are not`(): Unit =
        runBlocking {
            checkAll(config, Arb.int(1..50), Arb.int(1..50), Arb.int(2..9)) { n, d, k ->
                val accepted = "$n/$d"
                val scaled = "${n * k}/${d * k}"
                AnswerChecker.check(AnswerSpec(AnswerType.FRACTION, listOf(accepted)), scaled).verdict shouldBe Verdict.CORRECT
                AnswerChecker
                    .check(AnswerSpec(AnswerType.FRACTION, listOf(accepted), allowEquivalent = false), scaled)
                    .verdict shouldBe Verdict.WRONG
                AnswerChecker.check(AnswerSpec(AnswerType.FRACTION, listOf(accepted)), "${n + d}/$d").verdict shouldBe Verdict.WRONG
            }
        }

    // @trace FR-19
    @Test
    fun `text ignores case and whitespace runs`(): Unit =
        runBlocking {
            val words = Arb.list(Arb.element(listOf("pravý", "úhel", "Žirafa", "kůň", "trojúhelník")), 1..4)
            checkAll(config, words, Arb.element(listOf(" ", "  ", " ", "\t"))) { parts, gap ->
                val accepted = parts.joinToString(" ")
                val input = " " + parts.joinToString(gap) { it.uppercase() } + gap
                AnswerChecker.check(AnswerSpec(AnswerType.TEXT, listOf(accepted)), input).verdict shouldBe Verdict.CORRECT
                AnswerChecker
                    .check(AnswerSpec(AnswerType.TEXT, listOf(accepted), diacriticsStrict = true), input)
                    .verdict shouldBe Verdict.CORRECT
            }
        }

    // @trace FR-14
    // @trace FR-17
    // @trace FR-19
    @Test
    fun `any input gives a deterministic, well-formed result without throwing`(): Unit =
        runBlocking {
            val alphabet = "0123456789 ,.+-/^²³°  −cmkgAaŽž"
            val specs =
                listOf(
                    AnswerSpec(AnswerType.INTEGER, listOf("12")),
                    AnswerSpec(AnswerType.DECIMAL, listOf("3.5"), tolerance = "0.1", exactDecimals = 1),
                    AnswerSpec(AnswerType.FRACTION, listOf("3/4"), requireLowestTerms = true),
                    AnswerSpec(AnswerType.FRACTION, listOf("1 1/2"), allowEquivalent = false),
                    AnswerSpec(AnswerType.NUMBER_WITH_UNIT, listOf("24 cm²")),
                    AnswerSpec(AnswerType.TEXT, listOf("Žirafa")),
                )
            val input = Arb.list(Arb.element(alphabet.toList()), 0..30)
            checkAll(config, Arb.element(specs), input) { spec, chars ->
                val raw = chars.joinToString("")
                val first = AnswerChecker.check(spec, raw)
                AnswerChecker.check(spec, raw) shouldBe first
                if (first.verdict == Verdict.UNPARSEABLE) {
                    first.normalized.shouldBeNull()
                    TipRules.violations(spec, first.formatTip).shouldBeEmpty()
                } else {
                    first.normalized.shouldNotBeNull()
                    first.formatTip.shouldBeNull()
                }
            }
        }

    // @trace FR-14
    @Test
    fun `a tip example never reveals an accepted answer`(): Unit =
        runBlocking {
            checkAll(
                config,
                Arb.int(-20..20),
                Arb.int(0..2),
                Arb.element(listOf(AnswerType.INTEGER, AnswerType.DECIMAL, AnswerType.FRACTION, AnswerType.NUMBER_WITH_UNIT)),
            ) { n, scale, type ->
                val accepted =
                    java.math.BigDecimal
                        .valueOf(n.toLong(), scale)
                        .toPlainString() + (if (type == AnswerType.NUMBER_WITH_UNIT) " m²" else "")
                val spec =
                    AnswerSpec(type, listOf(accepted), tolerance = listOf(null, "1", "0,5", "1000000", "99999999")[Math.floorMod(n, 5)])
                val result = AnswerChecker.check(spec, "x")
                result.verdict shouldBe Verdict.UNPARSEABLE
                TipRules.violations(spec, result.formatTip).shouldBeEmpty()
            }
        }
}
