package cz.doucko.admin

import cz.doucko.admin.adapter.`in`.web.AdminSecurityConfiguration
import cz.doucko.content.ContentWebTestBeans
import cz.doucko.profile.ProfileWebTestBeans
import cz.doucko.shared.adapter.`in`.web.SecurityConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.time.Duration

/** Store spec admin-access "Admin session idle timeout": 8 h on the backend's clock (NFR-15). */
@WebMvcTest
@Import(
    SecurityConfiguration::class,
    AdminSecurityConfiguration::class,
    AdminWebTestBeans::class,
    ProfileWebTestBeans::class,
    ContentWebTestBeans::class,
)
class AdminIdleTimeoutTest {
    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var clock: MutableClock

    private fun idle(
        hours: Long,
        minutes: Long = 0,
    ) = clock.advance(Duration.ofHours(hours).plusMinutes(minutes))

    // @trace NFR-15
    @Test
    fun `a session idle for 7 h 59 min is still valid`() {
        val admin = mvc.signedIn()
        idle(7, 59)
        mvc.checkSession(admin.session).andExpect { status { isNoContent() } }
    }

    // @trace NFR-15
    @Test
    fun `a session idle for 8 h 1 min is refused with 401, even with a valid CSRF token`() {
        val admin = mvc.signedIn()
        idle(8, 1)
        mvc.signOut(admin).isProblem(401)
        mvc.checkSession(admin.session).isProblem(401)
    }

    // @trace NFR-15
    @Test
    fun `activity keeps the session alive`() {
        val admin = mvc.signedIn()
        idle(7)
        mvc.checkSession(admin.session).andExpect { status { isNoContent() } }
        idle(7)
        mvc.checkSession(admin.session).andExpect { status { isNoContent() } }
    }

    // @trace NFR-15
    @Test
    fun `a CSRF refusal still counts as activity`() {
        val admin = mvc.signedIn()
        idle(7)
        mvc.signOut(admin, header = null).isProblem(403)
        idle(7)
        mvc.checkSession(admin.session).andExpect { status { isNoContent() } }
    }

    // @trace NFR-15
    @Test
    fun `requests after expiry do not revive the session`() {
        val admin = mvc.signedIn()
        idle(8, 1)
        mvc.checkSession(admin.session).isProblem(401)
        idle(0, 1)
        mvc.checkSession(admin.session).isProblem(401)
    }

    // @trace NFR-15
    @Test
    fun `a failed sign-in carrying the session is a 401 and does not reset the idle clock`() {
        val admin = mvc.signedIn()
        idle(7)
        mvc
            .post("/api/admin/session") {
                session = admin.session
                contentType = MediaType.APPLICATION_JSON
                content = """{"password":"wrong"}"""
            }.isProblem(401, "Invalid credentials")
        idle(1, 1)
        mvc.checkSession(admin.session).isProblem(401)
    }
}
