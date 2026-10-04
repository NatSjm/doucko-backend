package cz.doucko.shared

import cz.doucko.admin.adapter.`in`.web.AdminSecurityConfiguration
import cz.doucko.profile.ProfileWebTestBeans
import cz.doucko.shared.adapter.`in`.web.SecurityConfiguration
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.MessageSource
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.request
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.resource.NoResourceFoundException
import java.net.URI
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Contract `info` → Conventions: an undeclared path is 404 problem+json (NFR-9: title from the catalog).
 * Without an admin session, every `/api/admin/` request is refused as problem+json and no handler runs.
 */
@WebMvcTest
@Import(SecurityConfiguration::class, AdminSecurityConfiguration::class, UndeclaredPathTest.AdminProbe::class, ProfileWebTestBeans::class)
class UndeclaredPathTest {
    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var messages: MessageSource

    /** A handler under the admin area: the security chain must refuse before it is reached. */
    @TestConfiguration
    @RestController
    class AdminProbe {
        @RequestMapping("/api/admin/probe")
        fun probe() {
            calls.incrementAndGet()
        }

        companion object {
            val calls = AtomicInteger()
        }
    }

    private fun title(exception: Class<*>) = messages.getMessage("problemDetail.title.${exception.name}", null, Locale.ENGLISH)

    private fun ResultActionsDsl.isProblem(
        status: Int,
        title: String,
    ) = andExpect {
        status { isEqualTo(status) }
        content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
        jsonPath("$.title") { value(title) }
        jsonPath("$.status") { value(status) }
        jsonPath("$.trace") { doesNotExist() }
        jsonPath("$.exception") { doesNotExist() }
    }

    private fun send(
        method: String,
        path: String,
    ) = mvc.request(HttpMethod.valueOf(method), URI(path)) {
        contentType = MediaType.APPLICATION_JSON
        content = "{}"
    }

    // @trace NFR-9
    @Test
    fun `an undeclared child path is 404 problem+json titled from the message catalog`() {
        mvc.get("/api/child/does-not-exist").isProblem(404, title(NoResourceFoundException::class.java))
    }

    // @trace NFR-9
    @ParameterizedTest
    @ValueSource(strings = ["POST", "PUT", "DELETE"])
    fun `a write to an undeclared path needs no CSRF token and is 404 problem+json`(method: String) {
        for (path in listOf("/api/child/does-not-exist", "/api/does-not-exist", "/does-not-exist")) {
            send(method, path).isProblem(404, title(NoResourceFoundException::class.java))
        }
    }

    // @trace NFR-9
    @ParameterizedTest
    @ValueSource(strings = ["GET", "POST", "PUT", "DELETE"])
    fun `the admin area refuses a client without a session, as problem+json, and no handler runs`(method: String) {
        AdminProbe.calls.set(0)
        for (path in listOf("/api/admin/probe", "/api/admin/sources", "/api/admin/does-not-exist")) {
            send(method, path).isProblem(401, messages.getMessage("problem.title.401", null, Locale.ENGLISH))
        }
        AdminProbe.calls.get() shouldBe 0
    }
}
