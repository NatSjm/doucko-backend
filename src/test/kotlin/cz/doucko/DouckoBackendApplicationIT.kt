package cz.doucko

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** The whole application against Postgres 16, on a real port where the servlet container's error handling runs. */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension::class)
class DouckoBackendApplicationIT {
    @Autowired
    lateinit var mvc: MockMvc

    @LocalServerPort
    var port: Int = 0

    private val http = HttpClient.newHttpClient()

    /** Sends [rawPath] as written (no normalisation), so the security firewall sees it. */
    private fun get(
        rawPath: String,
        accept: String = "application/json",
    ): HttpResponse<String> =
        http.send(
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$rawPath"))
                .header("Accept", accept)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun `context starts against Postgres 16 with Flyway applied`() {
    }

    // @trace NFR-2
    @Test
    fun `only the health endpoint of actuator is reachable`() {
        mvc.get("/actuator/health").andExpect { status { isOk() } }
        for (path in listOf("/actuator/modulith", "/actuator/info", "/actuator/env", "/actuator/httpexchanges")) {
            mvc.get(path).andExpect { status { isNotFound() } }
        }
    }

    // @trace NFR-9
    @ParameterizedTest
    @CsvSource(
        "/error, application/json, 404, Resource not found",
        "/error, text/html, 404, Resource not found",
        "/api/child/does-not-exist, text/html, 404, Resource not found",
        "/api//child/does-not-exist, application/json, 400, Bad request",
        "/api/child/x;jsessionid=1, application/json, 400, Bad request",
        "/api/child/%2e%2e/admin, application/json, 400, Bad request",
    )
    fun `container-level errors are problem+json from the catalog, never Boot's error page`(
        path: String,
        accept: String,
        status: Int,
        title: String,
    ) {
        val response = get(path, accept)
        response.statusCode() shouldBe status
        response.headers().firstValue("Content-Type").orElse("") shouldStartWith "application/problem+json"
        response.body() shouldContain "\"title\":\"$title\""
        for (leak in listOf("\"trace\"", "\"exception\"", "\"timestamp\"", "Whitelabel", "<html")) response.body() shouldNotContain leak
    }

    // @trace NFR-3
    @Test
    fun `startup mints and logs no generated password`(output: CapturedOutput) {
        get("/actuator/health").statusCode() shouldBe 200
        output.all shouldNotContain "generated security password"
    }
}
