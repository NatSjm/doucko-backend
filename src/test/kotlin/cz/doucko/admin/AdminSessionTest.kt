package cz.doucko.admin

import cz.doucko.admin.adapter.`in`.web.AdminSecurityConfiguration
import cz.doucko.profile.ProfileWebTestBeans
import cz.doucko.shared.adapter.`in`.web.SecurityConfiguration
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request
import java.net.URI

/** Store spec admin-access: authentication, CSRF and lockout scenarios against the admin chain. */
@WebMvcTest
@Import(SecurityConfiguration::class, AdminSecurityConfiguration::class, AdminWebTestBeans::class, ProfileWebTestBeans::class)
class AdminSessionTest {
    @Autowired
    lateinit var mvc: MockMvc

    private fun MvcResult.setsSession() = request.getSession(false) != null || response.getCookie("SESSION") != null

    // @trace NFR-1
    @ParameterizedTest
    @CsvSource(
        "GET, /api/admin/session",
        "DELETE, /api/admin/session",
        "GET, /api/admin/stats",
        "POST, /api/admin/tasks",
        "POST, /api/admin/tasks/0b6f2f1e-3c55-4e7a-9a40-1f6b1c2d3e4f/check",
        "POST, /api/admin/reports/0b6f2f1e-3c55-4e7a-9a40-1f6b1c2d3e4f/reevaluate",
        "PUT, /api/admin/missions/0b6f2f1e-3c55-4e7a-9a40-1f6b1c2d3e4f/status",
        "GET, " + PROBE,
        "POST, " + PROBE,
        "GET, /api/admin/does-not-exist",
    )
    fun `without a session every admin operation is 401 problem+json and no handler runs`(
        method: String,
        path: String,
    ) {
        AdminProbe.calls.set(0)
        mvc
            .request(HttpMethod.valueOf(method), URI(path)) {
                contentType = MediaType.APPLICATION_JSON
                content = """{"status":"PUBLISHED"}"""
            }.isProblem(401, "Unauthorized")
        AdminProbe.calls.get() shouldBe 0
    }

    // @trace NFR-1
    @Test
    fun `sign-in is 204 and the session then checks as signed in`() {
        val admin = mvc.signedIn()
        mvc.checkSession(admin.session).andExpect { status { isNoContent() } }
    }

    // @trace NFR-15
    @Test
    fun `sign-in needs no CSRF token and issues one`() {
        mvc.signIn().andExpect {
            status { isNoContent() }
            cookie { exists("XSRF-TOKEN") }
        }
    }

    // @trace NFR-1
    @Test
    fun `wrong passwords are indistinguishable and set no session`() {
        val bodies =
            listOf("Kocka-na-plote-43", "x").map { wrong ->
                val result = mvc.signIn("""{"password":"$wrong"}""").isProblem(401, "Invalid credentials").andReturn()
                result.setsSession() shouldBe false
                result.response.contentAsString shouldNotContain wrong
                result.response.contentAsString
            }
        bodies[0] shouldBe bodies[1]
    }

    // @trace NFR-1
    @Test
    fun `an extra username field is ignored`() {
        mvc.signIn("""{"username":"anything","password":"$PASSWORD"}""").andExpect { status { isNoContent() } }
    }

    // @trace NFR-1
    @ParameterizedTest
    @ValueSource(strings = ["", "{}", """{"password":""}""", """{"password":null}""", """{"password":""", """{"password":{}}""", "[]"])
    fun `an empty, missing or malformed body is 401 Invalid credentials, never 400`(body: String) {
        val result = mvc.signIn(body.ifEmpty { null }).isProblem(401, "Invalid credentials").andReturn()
        result.setsSession() shouldBe false
    }

    // @trace NFR-1
    @Test
    fun `a body without a content type or with a non-JSON one is 401 Invalid credentials`() {
        mvc.signIn(body = null, type = null).isProblem(401, "Invalid credentials")
        mvc.signIn(body = PASSWORD, type = MediaType.TEXT_PLAIN).isProblem(401, "Invalid credentials")
        mvc.signIn(body = "password=$PASSWORD", type = MediaType.APPLICATION_FORM_URLENCODED).isProblem(401, "Invalid credentials")
    }

    // @trace NFR-1
    @Test
    fun `a 1 MB password is 401 and the correct password still signs in`() {
        val result = mvc.signIn("""{"password":"${"a".repeat(1_048_576)}"}""").isProblem(401, "Invalid credentials").andReturn()
        result.setsSession() shouldBe false
        mvc.signIn().andExpect { status { isNoContent() } }
    }

    // @trace NFR-1
    @Test
    fun `only the full password signs in`() {
        // The 72-byte prefix case is in AdminCredentialsTest; here the value plus or minus one character.
        mvc.signIn("""{"password":"${PASSWORD}x"}""").isProblem(401, "Invalid credentials")
        mvc.signIn("""{"password":"${PASSWORD.dropLast(1)}"}""").isProblem(401, "Invalid credentials")
    }

    // @trace NFR-15
    @Test
    fun `twenty failures do not lock the account`() {
        repeat(20) { mvc.signIn("""{"password":"wrong-$it"}""").isProblem(401) }
        mvc.signIn().andExpect { status { isNoContent() } }
    }

    // @trace NFR-15
    @Test
    fun `sign-out with the CSRF header ends the session`() {
        val admin = mvc.signedIn()
        mvc.signOut(admin).andExpect {
            status { isNoContent() }
            cookie {
                maxAge("SESSION", 0)
                path("SESSION", "/api/admin")
                maxAge("XSRF-TOKEN", 0)
            }
        }
        mvc.checkSession(admin.session).isProblem(401)
    }

    // @trace NFR-15
    @Test
    fun `sign-out without the CSRF header is 403 and the session survives`() {
        val admin = mvc.signedIn()
        mvc.signOut(admin, header = null).isProblem(403)
        mvc.checkSession(admin.session).andExpect { status { isNoContent() } }
    }

    // @trace NFR-15
    @Test
    fun `a wrong CSRF token is 403 and nothing changes`() {
        val admin = mvc.signedIn()
        mvc.signOut(admin, header = "not-the-token").isProblem(403)
        mvc.checkSession(admin.session).andExpect { status { isNoContent() } }
    }

    // @trace NFR-15
    @Test
    fun `a data-changing admin request without the CSRF header is 403 and its handler does not run`() {
        val admin = mvc.signedIn()
        AdminProbe.calls.set(0)
        mvc.post(PROBE) { session = admin.session }.isProblem(403)
        AdminProbe.calls.get() shouldBe 0
    }

    // @trace NFR-15
    @Test
    fun `a data-changing admin request with the CSRF token is processed`() {
        val admin = mvc.signedIn()
        AdminProbe.calls.set(0)
        mvc
            .post(PROBE) {
                session = admin.session
                cookie(Cookie("XSRF-TOKEN", admin.xsrf))
                header("X-XSRF-TOKEN", admin.xsrf)
            }.andExpect { status { isOk() } }
        AdminProbe.calls.get() shouldBe 1
    }

    // @trace NFR-15
    @Test
    fun `reads need no CSRF token`() {
        val admin = mvc.signedIn()
        AdminProbe.calls.set(0)
        mvc.get(PROBE) { session = admin.session }.andExpect { status { isOk() } }
        AdminProbe.calls.get() shouldBe 1
        // No stats handler yet: past security it is an ordinary undeclared path.
        mvc.get("/api/admin/stats") { session = admin.session }.isProblem(404)
    }

    // @trace NFR-15
    @Test
    fun `no session wins over a missing CSRF token`() {
        for (path in listOf(
            "/api/admin/tasks/0b6f2f1e-3c55-4e7a-9a40-1f6b1c2d3e4f/check",
            "/api/admin/reports/0b6f2f1e-3c55-4e7a-9a40-1f6b1c2d3e4f/reevaluate",
        )) {
            mvc.post(path).isProblem(401)
        }
    }

    // @trace NFR-1
    @Test
    fun `sign-in replaces any earlier session`() {
        val first = mvc.signedIn()
        val result =
            mvc
                .post("/api/admin/session") {
                    session = first.session
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"password":"$PASSWORD"}"""
                }.andExpect { status { isNoContent() } }
                .andReturn()
        first.session.isInvalid shouldBe true
        (result.request.getSession(false) === first.session) shouldBe false
    }

    // @trace NFR-1
    @Test
    fun `a failed sign-in creates no session`() {
        mvc
            .signIn("""{"password":"wrong"}""")
            .andReturn()
            .request
            .getSession(false)
            .shouldBeNull()
    }
}
