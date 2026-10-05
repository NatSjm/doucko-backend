package cz.doucko.practice

import com.jayway.jsonpath.JsonPath
import cz.doucko.TestcontainersConfiguration
import cz.doucko.admin.signedIn
import cz.doucko.content.Files
import cz.doucko.content.SOURCE_FIELDS
import cz.doucko.content.adminJson
import cz.doucko.content.taskJson
import cz.doucko.content.uploadSource
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * NFR-5, the backend share of "Check round-trip time": graded submissions over real HTTP against Postgres,
 * measured at the client from sending `POST …/attempts` to the complete response, p95 under 300 ms after 20
 * unmeasured warm-ups. Half are correct, half wrong; between submissions the task's attempts are cleared
 * (unmeasured) so each one is a valid attempt 1. The frontend's e2e test measures the Compose stack.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class PracticeTimingIT {
    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @LocalServerPort
    var port = 0

    /**
     * Log levels are JVM-global: a context of another test class (PracticeLogCaptureIT sets TRACE) can leave
     * them raised, which would measure logging instead of the endpoint. Measure at the configured levels.
     */
    @BeforeEach
    fun atConfiguredLogLevels() {
        configuredLogLevels(javaClass.classLoader)
    }

    @AfterEach
    fun clean() {
        for (table in listOf("problem_report", "opened_hint", "attempt", "practice_run", "mission", "task_image", "task", "source")) {
            jdbc.update("DELETE FROM $table")
        }
    }

    // @trace NFR-5
    @Test
    fun `the attempt round trip stays under 300 ms at p95`() {
        val admin = mvc.signedIn()
        val sourceId: String =
            JsonPath.read(
                mvc
                    .uploadSource(admin, Files.pdf(120), *SOURCE_FIELDS)
                    .andReturn()
                    .response.contentAsString,
                "$.sourceId",
            )
        val parts =
            """[{"key":"a","label":"a","spec":{"type":"INTEGER","accepted":["12"]}},""" +
                """{"key":"b","label":"b","spec":{"type":"DECIMAL","accepted":["0.5"]}}]"""
        val taskId: String =
            JsonPath.read(
                mvc
                    .adminJson("POST", admin, "/api/admin/tasks", taskJson(sourceId, "parts" to parts))
                    .andReturn()
                    .response.contentAsString,
                "$.taskId",
            )
        mvc.adminJson("PUT", admin, "/api/admin/tasks/$taskId/status", """{"status":"PUBLISHED"}""")
        val missionId: String =
            JsonPath.read(
                mvc
                    .adminJson(
                        "POST",
                        admin,
                        "/api/admin/missions",
                        """{"topicId":"6a000000-0000-4000-8000-000000000002","position":1,"taskIds":["$taskId"]}""",
                    ).andReturn()
                    .response.contentAsString,
                "$.missionId",
            )
        mvc.adminJson("PUT", admin, "/api/admin/missions/$missionId/status", """{"status":"PUBLISHED"}""")
        val client = HttpClient.newHttpClient()
        val uri = URI.create("http://localhost:$port/api/child/missions/$missionId/tasks/$taskId/attempts")

        fun submit(correct: Boolean): Long {
            val body = if (correct) """{"answers":{"a":"12","b":"0,5"}}""" else """{"answers":{"a":"12","b":"0,7"}}"""
            val request =
                HttpRequest
                    .newBuilder(
                        uri,
                    ).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build()
            val start = System.nanoTime()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            val elapsed = System.nanoTime() - start
            response.statusCode() shouldBe 200
            jdbc.update("DELETE FROM attempt")
            return elapsed
        }
        repeat(20) { submit(it % 2 == 0) }
        val times = List(200) { submit(it % 2 == 0) }.sorted()
        val p95 = times[(times.size * 95 / 100) - 1] / 1_000_000
        println("NFR-5 attempt round trip: p50 ${times[99] / 1_000_000} ms, p95 $p95 ms (200 graded submissions)")
        p95.toInt() shouldBeLessThan 300
    }
}
