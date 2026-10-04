package cz.doucko.profile

import cz.doucko.TestcontainersConfiguration
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * The settings endpoints over real HTTP against Postgres 16: no cookie, no CSRF header, and the stored
 * row changes only on a valid request (store spec child-profile, FR-43).
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension::class)
class ProfileSettingsIT {
    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var jdbc: JdbcTemplate

    private val http = HttpClient.newHttpClient()

    private fun send(
        method: String,
        body: String? = null,
    ): HttpResponse<String> =
        http.send(
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/api/child/profile/settings"))
                .header("Content-Type", "application/json")
                .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun row() =
        jdbc.queryForMap("select secondary_task_locale, answer_mode from child_profile").let {
            it["secondary_task_locale"] to it["answer_mode"]
        }

    @BeforeEach
    fun clear() {
        jdbc.update("update child_profile set secondary_task_locale = null, answer_mode = 'DIRECT'")
    }

    // @trace FR-43
    @Test
    fun `the first start created exactly one profile with defaults`() {
        jdbc.queryForObject("select count(*) from child_profile", Int::class.java) shouldBe 1
        val get = send("GET")
        get.statusCode() shouldBe 200
        get.body() shouldBe """{"secondaryTaskLocale":null,"answerMode":"DIRECT"}"""
    }

    // @trace FR-43
    @Test
    fun `set and clear are stored, a refusal stores nothing`() {
        send("PATCH", """{"secondaryTaskLocale":"uk"}""").statusCode() shouldBe 200
        row() shouldBe ("uk" to "DIRECT")
        send("PATCH", """{"secondaryTaskLocale":null,"answerMode":"GUIDED"}""").statusCode() shouldBe 422
        row() shouldBe ("uk" to "DIRECT")
        send("PATCH", """{"secondaryTaskLocale":null,"answerMode":null}""").statusCode() shouldBe 400
        row() shouldBe ("uk" to "DIRECT")
        send("PATCH", """{"secondaryTaskLocale":null}""").statusCode() shouldBe 200
        row() shouldBe (null to "DIRECT")
    }

    // @trace FR-43
    @Test
    fun `an invalid patch is problem+json, never a raw 500`() {
        for (body in listOf("""{"answerMode": """, "[]", """{"secondaryTaskLocale":5}""", """{"answerMode":"direct"}""")) {
            val response = send("PATCH", body)
            response.statusCode() shouldBe 400
            response
                .headers()
                .firstValue("Content-Type")
                .orElse("")
                .startsWith("application/problem+json") shouldBe true
        }
    }

    // @trace NFR-3
    @Test
    fun `serving and refusing settings logs neither the display name nor the payloads`(output: CapturedOutput) {
        val sentinel = "PII-SENTINEL-it-${System.nanoTime()}"
        send("GET")
        send("PATCH", """{"secondaryTaskLocale":"uk","note":"$sentinel"}""")
        send("PATCH", """{"secondaryTaskLocale":"$sentinel"}""")
        send("PATCH", """{"answerMode":"$sentinel""")
        output.all shouldNotContain sentinel
        output.all shouldNotContain FICTIONAL_NAME
    }
}
