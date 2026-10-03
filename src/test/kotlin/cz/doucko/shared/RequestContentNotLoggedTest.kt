package cz.doucko.shared

import cz.doucko.shared.adapter.`in`.web.SecurityConfiguration
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

/**
 * NFR-3: request content (answers, report notes, any text a child types) never reaches the logs.
 * Later slices add their endpoints to [AtConfiguredLevels].
 */
class RequestContentNotLoggedTest {
    private companion object {
        const val SENTINEL = "PII-SENTINEL-7f3a9c"
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
    @Import(SecurityConfiguration::class)
    @ExtendWith(OutputCaptureExtension::class)
    inner class AtTrace {
        @Autowired
        lateinit var mvc: MockMvc

        // @trace NFR-3
        @Test
        fun `body and header values stay out of the logs even at TRACE`(output: CapturedOutput) {
            mvc.post("/api/child/does-not-exist") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"answers":{"a":"$SENTINEL"},"note":"$SENTINEL"}"""
                header("X-Probe", SENTINEL)
            }
            output.all shouldNotContain SENTINEL
        }
    }

    @Nested
    @WebMvcTest
    @Import(SecurityConfiguration::class)
    @ExtendWith(OutputCaptureExtension::class)
    inner class AtConfiguredLevels {
        @Autowired
        lateinit var mvc: MockMvc

        // @trace NFR-3
        @Test
        fun `body, query and header values stay out of the logs at the configured levels`(output: CapturedOutput) {
            mvc.post("/api/child/does-not-exist?q=$SENTINEL") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"answers":{"a":"$SENTINEL"}}"""
                header("X-Probe", SENTINEL)
            }
            output.all shouldNotContain SENTINEL
        }
    }
}
