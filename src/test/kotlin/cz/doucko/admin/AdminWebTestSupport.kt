package cz.doucko.admin

import jakarta.servlet.http.Cookie
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.atomic.AtomicInteger

/** The spec's example password (admin-access scenarios); src/test/resources/application.properties sets it. */
const val PASSWORD = "Kocka-na-plote-42"

/** A signed-in admin as the browser holds it: the session and the XSRF-TOKEN cookie value. */
data class Admin(
    val session: MockHttpSession,
    val xsrf: String,
)

/** A movable backend clock, and [AdminProbe]. */
@TestConfiguration(proxyBeanMethods = false)
@Import(AdminProbe::class)
class AdminWebTestBeans {
    @Bean
    @Primary
    fun testClock() = MutableClock()
}

/** A handler under the admin area that counts how often it runs. */
@TestConfiguration(proxyBeanMethods = false)
@RestController
class AdminProbe {
    @RequestMapping(PROBE)
    fun probe() {
        calls.incrementAndGet()
    }

    companion object {
        val calls = AtomicInteger()
    }
}

const val PROBE = "/api/admin/test-probe"

fun MockMvc.signIn(
    body: String? = """{"password":"$PASSWORD"}""",
    type: MediaType? = MediaType.APPLICATION_JSON,
) = post("/api/admin/session") {
    type?.let { contentType = it }
    body?.let { content = it }
}

fun MockMvc.signedIn(): Admin {
    val result = signIn().andExpect { status { isNoContent() } }.andReturn()
    return Admin(
        result.request.getSession(false) as MockHttpSession,
        requireNotNull(result.response.getCookie("XSRF-TOKEN")) { "sign-in set no XSRF-TOKEN cookie" }.value,
    )
}

fun MockMvc.checkSession(session: MockHttpSession) = get("/api/admin/session") { this.session = session }

fun MockMvc.signOut(
    admin: Admin,
    header: String? = admin.xsrf,
) = delete("/api/admin/session") {
    session = admin.session
    cookie(Cookie("XSRF-TOKEN", admin.xsrf))
    header?.let { header("X-XSRF-TOKEN", it) }
}

fun ResultActionsDsl.isProblem(
    status: Int,
    title: String? = null,
) = andExpect {
    status { isEqualTo(status) }
    content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
    jsonPath("$.status") { value(status) }
    title?.let { jsonPath("$.title") { value(it) } }
}
