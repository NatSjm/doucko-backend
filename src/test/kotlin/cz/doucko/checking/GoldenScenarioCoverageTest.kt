package cz.doucko.checking

import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * answer-checking "Golden table size and coverage": every input named in a scenario of the 12
 * verdict-producing requirements is a golden case. The inputs are read from the pinned store spec
 * (`contract/openspec/specs/answer-checking/spec.md`): every backticked token on a scenario's WHEN line,
 * with `<U+XXXX>` expanded. A `{A, B}` token is a selection; a bare option key is a one-key selection.
 */
class GoldenScenarioCoverageTest {
    private val spec = File("contract/openspec/specs/answer-checking/spec.md").readText()
    private val token = Regex("`([^`]*)`")
    private val codePoint = Regex("<U\\+([0-9A-Fa-f]{4})>")

    private fun expand(s: String) =
        codePoint.replace(s) { m ->
            m.groupValues[1]
                .toInt(16)
                .toChar()
                .toString()
        }

    /** WHEN-line tokens per verdict-producing requirement. */
    private fun scenarioInputs(): List<Pair<String, String>> {
        val sections = spec.split(Regex("(?m)^### Requirement: ")).drop(1)
        return sections
            .map { it.substringBefore('\n').trim() to it }
            .filter { (name, _) -> name in GoldenTable.REQUIREMENTS }
            .flatMap { (name, body) ->
                body
                    .lines()
                    .filter { it.startsWith("- **WHEN**") }
                    .flatMap { line -> token.findAll(line).map { name to expand(it.groupValues[1]) }.toList() }
            }
    }

    private fun present(input: String): Boolean =
        GoldenTable.cases.any { case ->
            when (val raw = case.input) {
                is RawInput.Text -> {
                    raw.value == input
                }

                is RawInput.Selection -> {
                    input == raw.keys.joinToString(", ", "{", "}") ||
                        (raw.keys.size == 1 && input == raw.keys.single())
                }
            }
        }

    /**
     * WHEN-line tokens that describe a generated input or name a verdict rather than spell an input: the
     * long inputs of "Input length and exact arithmetic" (10,000 letters `a`, 500 digits `9`, `1` followed by
     * 99 or 100 zeros) and `UNPARSEABLE` in "Every golden-table tip follows the rules". Each maps to the
     * golden cases that cover it, which must exist.
     */
    private val described =
        mapOf(
            "a" to listOf("LA-01"),
            "9" to listOf("LA-02"),
            "1" to listOf("LA-03", "LA-04"),
            "UNPARSEABLE" to listOf("FT-01"),
        )

    // @trace FR-23
    @Test
    fun `every scenario input of the verdict-producing requirements is a golden case`() {
        val inputs = scenarioInputs()
        inputs.shouldNotBeEmpty()
        inputs
            .filterNot { (_, input) ->
                present(input) || described[input]?.all { id ->
                    GoldenTable.cases.any { it.id == id }
                } == true
            }.joinToString(" | ") shouldBe
            ""
    }
}
