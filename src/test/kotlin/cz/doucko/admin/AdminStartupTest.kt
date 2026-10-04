package cz.doucko.admin

import cz.doucko.admin.application.AdminCredentials
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension

/** admin-access "Admin password policy": a bad ADMIN_PASSWORD stops the context before any request is served (NFR-16). */
@ExtendWith(OutputCaptureExtension::class)
class AdminStartupTest {
    private fun start(password: String) =
        ApplicationContextRunner()
            .withUserConfiguration(AdminCredentials::class.java)
            .withPropertyValues("ADMIN_PASSWORD=$password")

    private fun Throwable.allText(): String = generateSequence(this) { it.cause }.joinToString("\n") { it.toString() }

    // @trace NFR-16
    @ParameterizedTest
    @CsvSource(
        "Kocka-na-pl, 12-character minimum",
        "привет, 12-character minimum",
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa, 72-byte maximum",
        "žžžžžžžžžžžžžžžžžžžžžžžžžžžžžžžžžžžžž, 72-byte maximum",
    )
    fun `a password outside the policy refuses to start, naming the limit and never the value`(
        password: String,
        limit: String,
        output: CapturedOutput,
    ) {
        start(password).run { context ->
            val failure = context.startupFailure.shouldNotBeNull()
            failure.allText() shouldContain "ADMIN_PASSWORD"
            failure.allText() shouldContain limit
            failure.allText() shouldNotContain password
        }
        output.all shouldNotContain password
    }

    // @trace NFR-16
    @ParameterizedTest
    @CsvSource("Kocka-na-plo", "žluťoučkýkůň", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
    fun `a password inside the policy starts and signs in`(password: String) {
        start(password).run { context ->
            context.startupFailure shouldBe null
            context.getBean(AdminCredentials::class.java).matches(password) shouldBe true
        }
    }

    // @trace NFR-16
    @org.junit.jupiter.api.Test
    fun `an empty password refuses to start`() {
        start("").run { context ->
            context.startupFailure.shouldNotBeNull().allText() shouldContain "ADMIN_PASSWORD"
        }
    }
}
