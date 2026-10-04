package cz.doucko.admin

import cz.doucko.admin.application.AdminCredentials
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test

/** admin-access: the full value is compared, and only a BCrypt hash is kept (NFR-1, NFR-16). */
class AdminCredentialsTest {
    private val p72 = "Kocka-na-plote-42-".repeat(4)

    // @trace NFR-1
    @Test
    fun `an input sharing the first 72 bytes is refused and exactly the password signs in`() {
        p72.toByteArray().size shouldBe 72
        val credentials = AdminCredentials(p72)
        credentials.matches(p72 + "x") shouldBe false
        credentials.matches(p72) shouldBe true
    }

    // @trace NFR-1
    @Test
    fun `missing, empty and oversized inputs never sign in`() {
        val credentials = AdminCredentials("Kocka-na-plote-42")
        credentials.matches(null) shouldBe false
        credentials.matches("") shouldBe false
        credentials.matches("a".repeat(1_048_576)) shouldBe false
    }

    // @trace NFR-1
    @Test
    fun `only a BCrypt hash is held`() {
        val credentials = AdminCredentials("Kocka-na-plote-42")
        credentials.hash shouldStartWith "$2"
        credentials.hash shouldNotContain "Kocka-na-plote-42"
        credentials.toString() shouldNotContain "Kocka-na-plote-42"
    }
}
