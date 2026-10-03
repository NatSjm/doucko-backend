package cz.doucko.shared

import cz.doucko.shared.adapter.`in`.web.SecurityConfiguration
import io.kotest.matchers.collections.shouldBeIn
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.MessageSource
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.web.servlet.resource.NoResourceFoundException
import java.util.Locale

/** Contract `info` → Conventions: an undeclared path is 404 problem+json (NFR-9: title from the catalog). */
@WebMvcTest
@Import(SecurityConfiguration::class)
class UndeclaredPathTest {
    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var messages: MessageSource

    private val notFoundTitle: String
        get() = messages.getMessage("problemDetail.title.${NoResourceFoundException::class.java.name}", null, Locale.ENGLISH)

    // @trace NFR-9
    @Test
    fun `an undeclared child path is 404 problem+json titled from the message catalog`() {
        mvc.get("/api/child/does-not-exist").andExpect {
            status { isNotFound() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.title") { value(notFoundTitle) }
            jsonPath("$.status") { value(404) }
            jsonPath("$.trace") { doesNotExist() }
            jsonPath("$.exception") { doesNotExist() }
        }
    }

    // @trace NFR-9
    @Test
    fun `a POST to an undeclared child path needs no CSRF token and is 404`() {
        mvc
            .post("/api/child/does-not-exist") {
                contentType = MediaType.APPLICATION_JSON
                content = "{}"
            }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `the admin area is closed until admin-access`() {
        for (path in listOf("/api/admin/sources", "/api/admin/does-not-exist")) {
            mvc
                .get(path)
                .andReturn()
                .response.status shouldBeIn listOf(401, 403)
        }
    }
}
