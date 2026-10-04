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

    /** Sends a GET with [host] as the Host header over a raw socket (HttpClient refuses to set Host). */
    private fun getWithHost(
        host: String,
        rawPath: String = "/api/child/does-not-exist",
    ): Pair<Int, String> =
        java.net.Socket("localhost", port).use { socket ->
            socket.getOutputStream().write(
                "GET $rawPath HTTP/1.1\r\nHost: $host\r\nAccept: application/json\r\nConnection: close\r\n\r\n".toByteArray(),
            )
            val text = socket.getInputStream().readAllBytes().toString(Charsets.UTF_8)
            text.substringAfter(' ').substringBefore(' ').toInt() to text
        }

    @Test
    fun `context starts against Postgres 16 with Flyway applied`() {
    }

    // @trace NFR-2
    @ParameterizedTest
    @CsvSource("localhost, 404", "127.0.0.1, 404", "192.168.1.20:8081, 404", "backend:8080, 404")
    fun `the backend answers localhost, IPv4 addresses and its Compose name`(
        host: String,
        status: Int,
    ) {
        getWithHost(host).first shouldBe status
    }

    // @trace NFR-2
    @ParameterizedTest
    @CsvSource("evil.example", "127.0.0.1.evil.example", "localhost.evil.example", "backend.evil.example")
    fun `any other Host is refused as problem+json, so a rebound DNS name cannot reach the API`(host: String) {
        val (status, text) = getWithHost(host)
        status shouldBe 400
        text shouldContain "application/problem+json"
        text shouldContain "\"title\":\"Bad request\""
    }

    // @trace NFR-2
    @Test
    fun `only the health endpoint of actuator is reachable`() {
        mvc.get("/actuator/health").andExpect { status { isOk() } }
        for (path in listOf(
            "/actuator",
            "/actuator/",
            "/actuator/modulith",
            "/actuator/info",
            "/actuator/env",
            "/actuator/httpexchanges",
        )) {
            mvc.get(path).andExpect { status { isNotFound() } }
        }
        // The discovery root lists no endpoints and is an ordinary undeclared path: 404 problem+json.
        val root = get("/actuator")
        root.statusCode() shouldBe 404
        root.headers().firstValue("Content-Type").orElse("") shouldStartWith "application/problem+json"
    }

    // @trace NFR-3
    @Test
    fun `a malformed request line is refused without logging its target or query`(output: CapturedOutput) {
        val secret = "SEKRET-${System.nanoTime()}"
        val status =
            java.net.Socket("localhost", port).use { socket ->
                // '{' is not allowed in a request target, so Tomcat rejects the line before Spring sees it.
                socket.getOutputStream().write(
                    "GET /api/child/x?token=$secret{ HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray(),
                )
                socket
                    .getInputStream()
                    .readAllBytes()
                    .toString(Charsets.UTF_8)
                    .substringAfter(' ')
                    .substringBefore(' ')
                    .toInt()
            }
        status shouldBe 400
        output.all shouldNotContain secret
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
