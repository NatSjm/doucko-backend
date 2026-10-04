package cz.doucko.shared

import com.jayway.jsonpath.JsonPath
import cz.doucko.admin.adapter.`in`.web.AdminSecurityConfiguration
import cz.doucko.admin.signedIn
import cz.doucko.content.ContentWebTestBeans
import cz.doucko.content.Files
import cz.doucko.content.SOURCE_FIELDS
import cz.doucko.content.adminGet
import cz.doucko.content.adminJson
import cz.doucko.content.quote
import cz.doucko.content.taskJson
import cz.doucko.content.uploadSource
import cz.doucko.profile.ProfileWebTestBeans
import cz.doucko.shared.adapter.`in`.web.SecurityConfiguration
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * NFR-3: request content (answers, report notes, any text a child types) never reaches the logs, at
 * any level. [Echo] binds a JSON body like every real child endpoint will, so Spring MVC's DEBUG
 * line `Read "application/json" to [...]` and the TRACE argument dump are exercised. The capture
 * itself is proven by [CaptureControl]. Each case sets `logging.level.root` itself: log levels are
 * JVM-global, and an explicit root keeps one case from carrying over into another.
 */
class RequestContentNotLoggedTest {
    private companion object {
        const val SENTINEL = "PII-SENTINEL-7f3a9c"
        const val ECHO = "/test/echo"
        const val SETTINGS = "/api/child/profile/settings"
    }

    /** Test-only endpoint that binds the request body. */
    @TestConfiguration
    @RestController
    class Echo {
        @PostMapping(ECHO)
        @ResponseStatus(HttpStatus.NO_CONTENT)
        fun echo(
            @RequestBody body: Map<String, Any?>,
        ) {
            check(body.isNotEmpty())
        }
    }

    private fun MockMvc.postWithSentinel(path: String) =
        post("$path?q=$SENTINEL") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"answers":{"a":"$SENTINEL"},"note":"$SENTINEL"}"""
            header("X-Probe", SENTINEL)
        }

    /** admin-access: a submitted password is request content like any other (NFR-3). */
    private fun MockMvc.signInWithSentinel() =
        post("/api/admin/session") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"password":"$SENTINEL"}"""
        }.andExpect { status { isUnauthorized() } }

    /**
     * child-profile (NFR-3, plan §2.1 slice 4): the settings endpoints. A valid change with an extra
     * field, and refusals that carry the sentinel as a locale, an answer mode and a malformed body.
     */
    private fun MockMvc.settingsWithSentinel() {
        for (body in listOf(
            """{"secondaryTaskLocale":"uk","displayName":"$SENTINEL"}""",
            """{"secondaryTaskLocale":"$SENTINEL"}""",
            """{"answerMode":"$SENTINEL"}""",
            """{"answerMode":"GUIDED","x":"$SENTINEL"}""",
            """{"answerMode": "$SENTINEL""",
        )) {
            patch("$SETTINGS?q=$SENTINEL") {
                contentType = MediaType.APPLICATION_JSON
                content = body
                header("X-Probe", SENTINEL)
            }
        }
        get(SETTINGS) { header("X-Probe", SENTINEL) }.andExpect { status { isOk() } }
    }

    /**
     * content-authoring (NFR-3, slice 5): the authoring endpoints take free text (prompts, titles, test
     * answers, search queries). Valid and refused requests carry the sentinel in each.
     */
    private fun MockMvc.contentWithSentinel() {
        val admin = signedIn()
        val upload = uploadSource(admin, Files.pdf(60), "title" to SENTINEL, "grade" to "6", "subject" to "math")
        val sourceId = JsonPath.read<String>(upload.andReturn().response.contentAsString, "$.sourceId")
        uploadSource(admin, Files.pdf(2), "title" to SENTINEL, "grade" to SENTINEL, "subject" to SENTINEL)
        uploadSource(admin, SENTINEL.toByteArray(), *SOURCE_FIELDS, name = SENTINEL)
        val created = adminJson("POST", admin, "/api/admin/tasks", taskJson(sourceId, "prompt" to quote(SENTINEL)))
        val taskId = JsonPath.read<String>(created.andReturn().response.contentAsString, "$.taskId")
        adminJson("POST", admin, "/api/admin/tasks", taskJson(sourceId, "prompt" to quote(SENTINEL), "difficulty" to quote(SENTINEL)))
        adminJson("POST", admin, "/api/admin/tasks", """{"prompt": "$SENTINEL""")
        adminJson("POST", admin, "/api/admin/tasks/$taskId/check", """{"answers":{"a":"$SENTINEL","$SENTINEL":"1"}}""")
        adminJson("PUT", admin, "/api/admin/sources/$sourceId", """{"title":"x","grade":6,"subject":"math","$SENTINEL":1}""")
        adminGet(admin, "/api/admin/tasks", "q" to SENTINEL).andExpect { status { isOk() } }
        adminGet(admin, "/api/admin/tasks/$SENTINEL")
    }

    @Nested
    @WebMvcTest(
        properties = [
            "logging.level.root=TRACE",
            "logging.level.org.springframework.web=TRACE",
            "logging.level.org.springframework.security=TRACE",
            "logging.level.cz.doucko=TRACE",
        ],
    )
    @Import(
        SecurityConfiguration::class,
        AdminSecurityConfiguration::class,
        Echo::class,
        ProfileWebTestBeans::class,
        ContentWebTestBeans::class,
    )
    @ExtendWith(OutputCaptureExtension::class)
    inner class AtTrace {
        @Autowired
        lateinit var mvc: MockMvc

        // @trace NFR-3
        @Test
        fun `a bound body, query and header values stay out of the logs even at TRACE`(output: CapturedOutput) {
            mvc.postWithSentinel(ECHO).andExpect { status { isNoContent() } }
            mvc.postWithSentinel("/api/child/does-not-exist")
            mvc.signInWithSentinel()
            mvc.settingsWithSentinel()
            mvc.contentWithSentinel()
            output.all shouldNotContain SENTINEL
        }
    }

    @Nested
    @WebMvcTest(properties = ["logging.level.root=INFO"])
    @Import(
        SecurityConfiguration::class,
        AdminSecurityConfiguration::class,
        Echo::class,
        ProfileWebTestBeans::class,
        ContentWebTestBeans::class,
    )
    @ExtendWith(OutputCaptureExtension::class)
    inner class AtConfiguredLevels {
        @Autowired
        lateinit var mvc: MockMvc

        // @trace NFR-3
        @Test
        fun `a bound body, query and header values stay out of the logs at the configured levels`(output: CapturedOutput) {
            mvc.postWithSentinel(ECHO).andExpect { status { isNoContent() } }
            mvc.postWithSentinel("/api/child/does-not-exist")
            mvc.signInWithSentinel()
            mvc.settingsWithSentinel()
            mvc.contentWithSentinel()
            output.all shouldNotContain SENTINEL
        }
    }

    /**
     * Negative control: at DEBUG the capture does see a sentinel logged by application code, and the
     * same event from a Spring web or security logger is dropped — the guard, not the capture, keeps
     * the TRACE case clean.
     */
    @Nested
    @WebMvcTest(
        properties = [
            "logging.level.root=DEBUG",
            "logging.level.org.springframework.web=DEBUG",
            "logging.level.org.springframework.security=DEBUG",
        ],
    )
    @Import(
        SecurityConfiguration::class,
        AdminSecurityConfiguration::class,
        Echo::class,
        ProfileWebTestBeans::class,
        ContentWebTestBeans::class,
    )
    @ExtendWith(OutputCaptureExtension::class)
    inner class CaptureControl {
        // @trace NFR-3
        @Test
        fun `the capture sees application DEBUG lines and the guard drops Spring web and security ones`(output: CapturedOutput) {
            LoggerFactory.getLogger("cz.doucko.probe").debug("app $SENTINEL-app")
            LoggerFactory.getLogger("org.springframework.web.servlet.probe").debug("web $SENTINEL-web")
            LoggerFactory.getLogger("org.springframework.security.probe").debug("security $SENTINEL-security")
            LoggerFactory.getLogger("org.springframework.web.servlet.probe").info("info line $SENTINEL-info")
            output.all shouldContain "$SENTINEL-app"
            output.all shouldContain "$SENTINEL-info"
            output.all shouldNotContain "$SENTINEL-web"
            output.all shouldNotContain "$SENTINEL-security"
        }
    }
}
