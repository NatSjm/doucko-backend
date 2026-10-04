package cz.doucko.admin

import cz.doucko.TestcontainersConfiguration
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
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

/** admin-access over a real port: the cookies Tomcat writes, and the password nowhere in output, responses or the database. */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension::class)
class AdminSessionIT {
    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var jdbc: JdbcTemplate

    private val http = HttpClient.newHttpClient()

    private fun send(
        method: String,
        path: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse<String> =
        http.send(
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .apply { if (body != null) header("Content-Type", "application/json") }
                .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun HttpResponse<*>.setCookie(name: String) =
        headers()
            .allValues("Set-Cookie")
            .firstOrNull { it.startsWith("$name=") }
            ?.split(';')
            ?.map { it.trim() }

    // @trace NFR-15
    @Test
    fun `the session cookie is HttpOnly, SameSite=Strict and scoped to the admin API`() {
        val response = send("POST", "/api/admin/session", """{"password":"$PASSWORD"}""")
        response.statusCode() shouldBe 204
        val session = response.setCookie("SESSION").shouldNotBeNull()
        session shouldContain "HttpOnly"
        session shouldContain "SameSite=Strict"
        session shouldContain "Path=/api/admin"
        val xsrf = response.setCookie("XSRF-TOKEN").shouldNotBeNull()
        xsrf shouldNotContain "HttpOnly"
        xsrf shouldContain "Path=/"
        xsrf shouldContain "SameSite=Strict"
    }

    // @trace NFR-1
    @Test
    fun `sign-in, check and sign-out over HTTP`() {
        val signIn = send("POST", "/api/admin/session", """{"password":"$PASSWORD"}""")
        val session = signIn.setCookie("SESSION").shouldNotBeNull().first()
        val xsrf = signIn.setCookie("XSRF-TOKEN").shouldNotBeNull().first()
        val cookies = "$session; $xsrf"
        send("GET", "/api/admin/session", headers = mapOf("Cookie" to cookies)).statusCode() shouldBe 204
        send("DELETE", "/api/admin/session", headers = mapOf("Cookie" to cookies)).statusCode() shouldBe 403
        send(
            "DELETE",
            "/api/admin/session",
            headers = mapOf("Cookie" to cookies, "X-XSRF-TOKEN" to xsrf.substringAfter('=')),
        ).statusCode() shouldBe 204
        send("GET", "/api/admin/session", headers = mapOf("Cookie" to cookies)).statusCode() shouldBe 401
    }

    // @trace NFR-1
    // @trace NFR-3
    @Test
    fun `the password is found in no log line, response or table`(output: CapturedOutput) {
        val responses =
            listOf(
                send("POST", "/api/admin/session", """{"password":"$PASSWORD"}"""),
                send("POST", "/api/admin/session", """{"password":"${PASSWORD}9"}"""),
                send("POST", "/api/admin/session", """{"password":"""),
            )
        responses.map { it.statusCode() } shouldBe listOf(204, 401, 401)
        for (response in responses) {
            response.body() shouldNotContain PASSWORD
            response.headers().map().toString() shouldNotContain PASSWORD
        }
        output.all shouldNotContain PASSWORD
        val tables = jdbc.queryForList("select table_name from information_schema.tables where table_schema = 'public'", String::class.java)
        tables shouldNotContain "admin"
        for (table in tables) {
            jdbc.queryForList("select cast(t as text) from \"$table\" t", String::class.java).forEach { it shouldNotContain PASSWORD }
        }
    }
}
