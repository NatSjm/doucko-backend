package cz.doucko.practice

import com.jayway.jsonpath.JsonPath
import cz.doucko.TestcontainersConfiguration
import cz.doucko.admin.signedIn
import cz.doucko.content.Files
import cz.doucko.content.SOURCE_FIELDS
import cz.doucko.content.adminJson
import cz.doucko.content.taskJson
import cz.doucko.content.uploadSource
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/**
 * NFR-3 (plan §2.1, extended by slice 7): a full attempt, an unreadable submission, a hint, a problem report
 * and an HQ read, against Postgres with the web, JDBC, transaction, Hibernate, driver and application loggers
 * at TRACE. Neither the submitted answers, the report note nor the display name may appear in any log line.
 * The capture itself is proven by an application TRACE line that does appear.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "logging.level.cz.doucko=TRACE",
        "logging.level.org.springframework.web=TRACE",
        "logging.level.org.springframework.jdbc=TRACE",
        "logging.level.org.springframework.transaction=TRACE",
        "logging.level.org.springframework.security=TRACE",
        "logging.level.org.hibernate=TRACE",
        "logging.level.org.postgresql=TRACE",
    ],
)
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension::class)
class PracticeLogCaptureIT {
    private companion object {
        const val ANSWER = "PII-ANSWER-5b1e"
        const val NOTE = "PII-NOTE-c94d"
        const val DISPLAY_NAME = "Hrdinka"
    }

    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @AfterEach
    fun clean() {
        configuredLogLevels(javaClass.classLoader)
        jdbc.cleanPractice()
        for (table in listOf("mission", "task_image", "task", "source")) {
            jdbc.update("DELETE FROM $table")
        }
    }

    // @trace NFR-3
    @Test
    fun `answers, report notes and the display name never reach the logs, even at TRACE`(output: CapturedOutput) {
        val admin = mvc.signedIn()
        val sourceId: String =
            JsonPath.read(
                mvc
                    .uploadSource(admin, Files.pdf(120), *SOURCE_FIELDS)
                    .andReturn()
                    .response.contentAsString,
                "$.sourceId",
            )
        val text = """[{"key":"a","label":"a","spec":{"type":"TEXT","accepted":["kočka"]}}]"""
        val taskId: String =
            JsonPath.read(
                mvc
                    .adminJson("POST", admin, "/api/admin/tasks", taskJson(sourceId, "parts" to text, "hints" to """["Zvíře."]"""))
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
        val base = "/api/child/missions/$missionId/tasks/$taskId"

        fun post(
            path: String,
            body: String,
        ) = mvc.post(path) {
            contentType = MediaType.APPLICATION_JSON
            content = body
        }
        post("$base/attempts", """{"answers":{"a":""}}""").andExpect { jsonPath("$.status") { value("UNPARSEABLE") } }
        post("$base/attempts", """{"answers":{"a":"$ANSWER"},"x":"$ANSWER"}""").andExpect { status { isOk() } }
        post("$base/attempts", """{"answers":{"b":"$ANSWER"}}""")
        mvc.post("$base/hints").andExpect { status { isOk() } }
        post("$base/reports", """{"reason":"OTHER","note":"$NOTE"}""").andExpect { status { isCreated() } }
        post("$base/reports", """{"reason":"$NOTE","note":"$NOTE"}""")
        mvc.get("/api/child/hq").andExpect { jsonPath("$.displayName") { value(DISPLAY_NAME) } }
        mvc.get(base).andExpect { status { isOk() } }
        jdbc.queryForObject("SELECT count(*) FROM attempt WHERE parts::text LIKE ?", Int::class.java, "%$ANSWER%") shouldBe 1
        LoggerFactory.getLogger("cz.doucko.probe").trace("capture probe {}", "visible-5b1e")

        output.all shouldContain "visible-5b1e"
        output.all shouldNotContain ANSWER
        output.all shouldNotContain NOTE
        output.all shouldNotContain DISPLAY_NAME
    }
}
