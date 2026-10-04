package cz.doucko.content

import cz.doucko.TestcontainersConfiguration
import cz.doucko.admin.PASSWORD
import cz.doucko.content.application.FileStoragePort
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import java.nio.file.Files as NioFiles

/**
 * NFR-17 over a real port: Tomcat parses the multipart body, so the 50 MB container limit and the 5 MB
 * image limit answer 413 problem+json the client actually receives (no connection reset), and nothing is
 * stored.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UploadLimitsIT {
    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var storage: FileStoragePort

    private val http = HttpClient.newHttpClient()

    private fun signIn(): Pair<String, String> {
        val response =
            http.send(
                HttpRequest
                    .newBuilder(URI.create("http://localhost:$port/api/admin/session"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"password":"$PASSWORD"}"""))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )

        fun cookie(name: String) =
            response
                .headers()
                .allValues("Set-Cookie")
                .first { it.startsWith("$name=") }
                .substringBefore(';')
        val session = cookie("SESSION")
        val xsrf = cookie("XSRF-TOKEN")
        return "$session; $xsrf" to xsrf.substringAfter('=')
    }

    private fun multipart(
        path: String,
        file: ByteArray,
        fileName: String,
        fields: Map<String, String> = emptyMap(),
    ): HttpResponse<String> {
        val (cookies, xsrf) = signIn()
        val boundary = "----doucko-${UUID.randomUUID()}"
        val body = ByteArrayOutputStream()

        fun write(text: String) = body.write(text.toByteArray(Charsets.UTF_8))
        fields.forEach { (k, v) -> write("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n") }
        write(
            "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\nContent-Type: application/octet-stream\r\n\r\n",
        )
        body.write(file)
        write("\r\n--$boundary--\r\n")
        return http.send(
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .header("Content-Type", "multipart/form-data; boundary=$boundary")
                .header("Cookie", cookies)
                .header("X-XSRF-TOKEN", xsrf)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
    }

    private fun storedFiles(): Long {
        val root = storage.locate("sources/x").parent.parent
        return if (NioFiles.exists(root)) NioFiles.walk(root).use { s -> s.filter { NioFiles.isRegularFile(it) }.count() } else 0
    }

    // @trace NFR-17
    @Test
    fun `a 51 MB PDF is refused with 413 problem+json and nothing is stored`() {
        val before = storedFiles()
        val sources = jdbc.queryForObject("SELECT count(*) FROM source", Int::class.java)
        val oversized = "%PDF-1.4\n".toByteArray() + ByteArray(51 * 1024 * 1024)
        val response =
            multipart("/api/admin/sources", oversized, "big.pdf", mapOf("title" to "Velký", "grade" to "6", "subject" to "math"))
        response.statusCode() shouldBe 413
        response.headers().firstValue("Content-Type").orElse("") shouldStartWith "application/problem+json"
        response.body() shouldContain "Only PDF files up to 50 MB are allowed."
        storedFiles() shouldBe before
        jdbc.queryForObject("SELECT count(*) FROM source", Int::class.java) shouldBe sources
    }

    // @trace NFR-17
    // @trace FR-1
    @Test
    fun `a valid PDF is accepted over HTTP`() {
        val response =
            multipart("/api/admin/sources", Files.pdf(3), "sesit.pdf", mapOf("title" to "Sešit", "grade" to "6", "subject" to "math"))
        response.statusCode() shouldBe 201
        response.body() shouldContain "\"pageCount\":3"
    }

    // @trace NFR-17
    // @trace FR-4
    @Test
    fun `a 6 MB JPG is refused with 413 problem+json`() {
        val source =
            multipart("/api/admin/sources", Files.pdf(3), "sesit.pdf", mapOf("title" to "Sešit", "grade" to "6", "subject" to "math"))
        val sourceId = Regex("\"sourceId\":\"([^\"]+)\"").find(source.body())!!.groupValues[1]
        val taskId = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO task (id, prompt, prompt_folded, topic_id, grade, difficulty, modality, status, source_id, page, task_no,
                              parts, hints, translations)
            VALUES (?, 'Úloha', 'uloha', ?, 6, 1, 'GRAPHIC', 'DRAFT', ?, 1, '1', '[]', '[]', '{}')
            """.trimIndent(),
            taskId,
            Topics.DECIMALS,
            UUID.fromString(sourceId),
        )
        val before = storedFiles()
        val response = multipart("/api/admin/tasks/$taskId/images", Files.jpgOfSize(6 * 1024 * 1024), "big.jpg")
        response.statusCode() shouldBe 413
        response.body() shouldContain "Only PNG or JPG files up to 5 MB are allowed."
        storedFiles() shouldBe before
    }
}
