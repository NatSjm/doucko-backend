package cz.doucko.admin

import cz.doucko.admin.domain.AdminPasswordPolicy
import cz.doucko.admin.domain.AdminPasswordPolicy.Violation
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/** admin-access "Admin password policy": 12 code points to 72 UTF-8 bytes (NFR-16, OQ-23, OQ-30). */
class AdminPasswordPolicyTest {
    private fun violation(value: String?) = AdminPasswordPolicy.violation(value)

    // @trace NFR-16
    @Test
    fun `missing or empty is refused`() {
        violation(null) shouldBe Violation.MISSING
        violation("") shouldBe Violation.MISSING
    }

    // @trace NFR-16
    @Test
    fun `eleven ASCII characters are too short and twelve are accepted`() {
        violation("a".repeat(11)) shouldBe Violation.TOO_SHORT
        violation("a".repeat(12)) shouldBe null
    }

    // @trace NFR-16
    @Test
    fun `characters are counted, not bytes`() {
        violation("привет") shouldBe Violation.TOO_SHORT
        violation("žluťoučkýkůň") shouldBe null
    }

    // @trace NFR-16
    @Test
    fun `more than 72 UTF-8 bytes is refused and exactly 72 is accepted`() {
        violation("a".repeat(72)) shouldBe null
        violation("a".repeat(73)) shouldBe Violation.TOO_LONG
        violation("ž".repeat(37)) shouldBe Violation.TOO_LONG
    }

    // @trace NFR-16
    @Test
    fun `characters outside the BMP count once`() {
        // 12 code points, 24 UTF-16 chars, 48 bytes: accepted.
        violation("😀".repeat(12)) shouldBe null
        violation("😀".repeat(11)) shouldBe Violation.TOO_SHORT
    }

    // @trace NFR-16
    @Test
    fun `messages name ADMIN_PASSWORD and the limit`() {
        AdminPasswordPolicy.message(Violation.MISSING) shouldContain "ADMIN_PASSWORD"
        AdminPasswordPolicy.message(Violation.TOO_SHORT) shouldContain "ADMIN_PASSWORD"
        AdminPasswordPolicy.message(Violation.TOO_SHORT) shouldContain "12"
        AdminPasswordPolicy.message(Violation.TOO_LONG) shouldContain "ADMIN_PASSWORD"
        AdminPasswordPolicy.message(Violation.TOO_LONG) shouldContain "72"
        AdminPasswordPolicy.message(Violation.TOO_LONG) shouldNotContain "12-character"
    }
}
