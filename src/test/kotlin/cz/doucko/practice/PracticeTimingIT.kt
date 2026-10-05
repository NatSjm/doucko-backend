package cz.doucko.practice

import com.jayway.jsonpath.JsonPath
import cz.doucko.TestcontainersConfiguration
import cz.doucko.admin.signedIn
import cz.doucko.content.Files
import cz.doucko.content.SOURCE_FIELDS
import cz.doucko.content.Topics
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
 * NFR-5, the backend share of practice-flow "Check round-trip time": a fixture of 10 published tasks that
 * together cover every MVP answer type and include a two-part task; 20 unmeasured warm-up submissions, then
 * 200 graded submissions in sequence over real HTTP against Postgres, each a valid attempt 1 (wrong) or
 * attempt 2 (correct), so half are correct and half wrong; fresh runs come from replays. Measured at the
 * client from sending `POST …/attempts` to the complete response: p95 under 300 ms. The same measurement
 * against the Docker Compose stack is the frontend's e2e timing test (plan §4.7).
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

    /** Log levels are JVM-global: measure at the configured levels, not at a TRACE left by another context. */
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

    /** One fixture task: its parts, a wrong attempt 1 and a correct attempt 2 (answers JSON). */
    private class Fixture(
        val parts: String,
        val wrong: String,
        val right: String,
    )

    private fun part(
        key: String,
        spec: String,
    ) = """{"key":"$key","label":"$key","spec":$spec}"""

    private val choices = """"options":[{"key":"A","text":"jedna"},{"key":"B","text":"dvě"},{"key":"C","text":"tři"}]"""

    private val fixtures =
        listOf(
            Fixture("[${part("a", """{"type":"INTEGER","accepted":["12"]}""")}]", """{"a":"13"}""", """{"a":"12"}"""),
            Fixture("[${part("a", """{"type":"DECIMAL","accepted":["0.5"]}""")}]", """{"a":"0,7"}""", """{"a":"0,5"}"""),
            Fixture("[${part("a", """{"type":"FRACTION","accepted":["3/4"]}""")}]", """{"a":"1/2"}""", """{"a":"6/8"}"""),
            Fixture("[${part("a", """{"type":"NUMBER_WITH_UNIT","accepted":["24 cm2"]}""")}]", """{"a":"25 cm2"}""", """{"a":"24 cm2"}"""),
            Fixture("[${part("a", """{"type":"CHOICE_SINGLE","accepted":["B"],$choices}""")}]", """{"a":["A"]}""", """{"a":["B"]}"""),
            Fixture(
                "[${part("a", """{"type":"CHOICE_MULTI","accepted":["A","C"],$choices}""")}]",
                """{"a":["A"]}""",
                """{"a":["C","A"]}""",
            ),
            Fixture("[${part("a", """{"type":"TEXT","accepted":["kočka"]}""")}]", """{"a":"pes"}""", """{"a":"Kocka"}"""),
            Fixture(
                "[${part("a", """{"type":"INTEGER","accepted":["12"]}""")},${part("b", """{"type":"DECIMAL","accepted":["0.5"]}""")}]",
                """{"a":"12","b":"3"}""",
                """{"b":"0.5"}""",
            ),
            Fixture(
                "[${part("a", """{"type":"INTEGER","accepted":["999999999999"]}""")}]",
                """{"a":"1 000"}""",
                """{"a":"999 999 999 999"}""",
            ),
            Fixture("[${part("a", """{"type":"TEXT","accepted":["trojúhelník"]}""")}]", """{"a":"čtverec"}""", """{"a":"trojuhelnik"}"""),
        )

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
        val taskIds =
            fixtures.map { fixture ->
                val id: String =
                    JsonPath.read(
                        mvc
                            .adminJson("POST", admin, "/api/admin/tasks", taskJson(sourceId, "parts" to fixture.parts))
                            .andExpect { status { isCreated() } }
                            .andReturn()
                            .response.contentAsString,
                        "$.taskId",
                    )
                mvc.adminJson("PUT", admin, "/api/admin/tasks/$id/status", """{"status":"PUBLISHED"}""").andExpect { status { isOk() } }
                id
            }
        val missionBody = """{"topicId":"${Topics.DECIMALS}","position":1,"taskIds":${taskIds.joinToString(",", "[", "]") { "\"$it\"" }}}"""
        val missionId: String =
            JsonPath.read(
                mvc
                    .adminJson("POST", admin, "/api/admin/missions", missionBody)
                    .andReturn()
                    .response.contentAsString,
                "$.missionId",
            )
        mvc.adminJson("PUT", admin, "/api/admin/missions/$missionId/status", """{"status":"PUBLISHED"}""").andExpect { status { isOk() } }
        val client = HttpClient.newHttpClient()
        val base = "http://localhost:$port/api/child/missions/$missionId"

        fun post(
            path: String,
            body: String,
        ): HttpResponse<String> =
            client.send(
                HttpRequest
                    .newBuilder(URI.create("$base$path"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )

        fun submit(
            taskId: String,
            answers: String,
            expectedState: String,
        ): Long {
            val start = System.nanoTime()
            val response = post("/tasks/$taskId/attempts", """{"answers":$answers}""")
            val elapsed = System.nanoTime() - start
            response.statusCode() shouldBe 200
            JsonPath.read<String>(response.body(), "$.taskState") shouldBe expectedState
            return elapsed
        }

        /** One run of the mission: attempt 1 wrong and attempt 2 correct on every task (20 submissions). */
        fun run(): List<Long> =
            taskIds.zip(fixtures).flatMap { (id, fixture) ->
                listOf(submit(id, fixture.wrong, "UNRESOLVED"), submit(id, fixture.right, "CORRECT_SECOND_TRY"))
            }

        run() // 20 warm-up submissions, not measured
        val times =
            List(10) {
                post("/replay", "").statusCode() shouldBe 200
                run()
            }.flatten().sorted()
        times.size shouldBe 200
        val p95 = times[(times.size * 95 / 100) - 1] / 1_000_000
        println(
            "NFR-5 attempt round trip: p50 ${times[99] / 1_000_000} ms, p95 $p95 ms (200 graded submissions, 10 tasks, every answer type)",
        )
        p95.toInt() shouldBeLessThan 300
    }
}
