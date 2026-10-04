package cz.doucko.profile

import cz.doucko.profile.domain.DisplayNamePolicy
import cz.doucko.profile.domain.DisplayNamePolicy.Outcome
import cz.doucko.profile.domain.DisplayNamePolicy.Violation
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** child-profile "Display name is validated on first start" (design decision of the store spec). */
class DisplayNamePolicyTest {
    // @trace FR-43
    @Test
    fun `surrounding whitespace is trimmed`() {
        DisplayNamePolicy.check("  $FICTIONAL_NAME  ") shouldBe Outcome.Accepted(FICTIONAL_NAME)
        DisplayNamePolicy.check("\t$FICTIONAL_NAME\n") shouldBe Outcome.Accepted(FICTIONAL_NAME)
    }

    // @trace FR-43
    @Test
    fun `a missing, empty or blank name is refused`() {
        DisplayNamePolicy.check(null) shouldBe Outcome.Refused(Violation.MISSING)
        DisplayNamePolicy.check("") shouldBe Outcome.Refused(Violation.MISSING)
        DisplayNamePolicy.check("    ") shouldBe Outcome.Refused(Violation.MISSING)
    }

    // @trace FR-43
    @Test
    fun `50 code points are accepted and 51 are refused, counted after trimming`() {
        DisplayNamePolicy.check("a".repeat(50)) shouldBe Outcome.Accepted("a".repeat(50))
        DisplayNamePolicy.check("  " + "a".repeat(50) + "  ") shouldBe Outcome.Accepted("a".repeat(50))
        DisplayNamePolicy.check("a".repeat(51)) shouldBe Outcome.Refused(Violation.TOO_LONG)
        // A letter outside the BMP is one code point (two UTF-16 chars).
        val clef = String(Character.toChars(0x1D11E))
        DisplayNamePolicy.check(clef.repeat(50)) shouldBe Outcome.Accepted(clef.repeat(50))
        DisplayNamePolicy.check(clef.repeat(51)) shouldBe Outcome.Refused(Violation.TOO_LONG)
        DisplayNamePolicy.check("ž".repeat(50)) shouldBe Outcome.Accepted("ž".repeat(50))
    }

    // @trace FR-43
    @ParameterizedTest
    @ValueSource(strings = ["Hrd\ninka", "Hrd\tinka", "Hrd\u0000inka", "Hrd\u007Finka"])
    fun `a control character between letters is refused`(name: String) {
        DisplayNamePolicy.check(name) shouldBe Outcome.Refused(Violation.CONTROL_CHARACTER)
    }

    // @trace FR-43
    @Test
    fun `the replacement character of invalid UTF-8 is refused`() {
        // The bytes 0xC3 0x28 decode to U+FFFD followed by '('.
        val decoded = String(byteArrayOf('H'.code.toByte(), 0xC3.toByte(), 0x28), Charsets.UTF_8)
        DisplayNamePolicy.check(decoded) shouldBe Outcome.Refused(Violation.NOT_UTF8)
    }

    // @trace FR-43
    // @trace NFR-3
    @Test
    fun `each message names the variable and the rule, never a value`() {
        for (violation in Violation.entries) {
            val message = DisplayNamePolicy.message(violation)
            message shouldContain "DOUCKO_CHILD_DISPLAY_NAME"
            message shouldNotContain FICTIONAL_NAME
        }
        DisplayNamePolicy.message(Violation.TOO_LONG) shouldContain "50-character"
        DisplayNamePolicy.message(Violation.CONTROL_CHARACTER) shouldContain "control character"
        DisplayNamePolicy.message(Violation.NOT_UTF8) shouldContain "UTF-8"
        DisplayNamePolicy.check(FICTIONAL_NAME).toString() shouldNotContain FICTIONAL_NAME
    }
}
