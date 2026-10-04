package cz.doucko.shared

import cz.doucko.shared.adapter.`in`.web.SecurityConfiguration
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * NFR-3: request content (answers, report notes, any text a child types) never reaches the logs.
 * [Echo] binds a JSON body like every real child endpoint will, so Spring MVC's DEBUG line
 * `Read "application/json" to [...]` is exercised; [AtDebug] proves the capture sees that leak.
 * Each case sets `logging.level.root` itself: log levels are JVM-global, and an explicit root keeps
 * the TRACE case from carrying over into the others.
 */
class RequestContentNotLoggedTest {
    private companion object {
        const val SENTINEL = "PII-SENTINEL-7f3a9c"
        const val ECHO = "/test/echo"
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

    @Nested
    @WebMvcTest(
        properties = [
            "logging.level.root=TRACE",
            "logging.level.org.springframework.web=TRACE",
            "logging.level.org.springframework.security=TRACE",
            "logging.level.cz.doucko=TRACE",
        ],
    )
    @Import(SecurityConfiguration::class, Echo::class)
    @ExtendWith(OutputCaptureExtension::class)
    inner class AtTrace {
        @Autowired
        lateinit var mvc: MockMvc

        // @trace NFR-3
        @Test
        fun `an unbound body and header values stay out of the logs even at TRACE`(output: CapturedOutput) {
            mvc.post("/api/child/does-not-exist") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"answers":{"a":"$SENTINEL"},"note":"$SENTINEL"}"""
                header("X-Probe", SENTINEL)
            }
            output.all shouldNotContain SENTINEL
        }
    }

    @Nested
    @WebMvcTest(properties = ["logging.level.root=INFO"])
    @Import(SecurityConfiguration::class, Echo::class)
    @ExtendWith(OutputCaptureExtension::class)
    inner class AtConfiguredLevels {
        @Autowired
        lateinit var mvc: MockMvc

        // @trace NFR-3
        @Test
        fun `a bound body, query and header values stay out of the logs at the configured levels`(output: CapturedOutput) {
            mvc.postWithSentinel(ECHO).andExpect { status { isNoContent() } }
            mvc.postWithSentinel("/api/child/does-not-exist")
            output.all shouldNotContain SENTINEL
        }
    }

    /** Negative control: with Spring MVC at DEBUG the bound body IS logged, so the capture can see a leak. */
    @Nested
    @WebMvcTest(properties = ["logging.level.root=INFO", "logging.level.org.springframework.web=DEBUG"])
    @Import(SecurityConfiguration::class, Echo::class)
    @ExtendWith(OutputCaptureExtension::class)
    inner class AtDebug {
        @Autowired
        lateinit var mvc: MockMvc

        // @trace NFR-3
        @Test
        fun `the capture sees a bound body when Spring MVC logs at DEBUG`(output: CapturedOutput) {
            mvc.postWithSentinel(ECHO).andExpect { status { isNoContent() } }
            output.all shouldContain SENTINEL
        }
    }
}
