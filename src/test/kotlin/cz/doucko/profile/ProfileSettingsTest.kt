package cz.doucko.profile

import cz.doucko.admin.AdminWebTestBeans
import cz.doucko.admin.adapter.`in`.web.AdminSecurityConfiguration
import cz.doucko.admin.signedIn
import cz.doucko.content.ContentWebTestBeans
import cz.doucko.profile.domain.AnswerMode
import cz.doucko.profile.domain.ProfileSettings
import cz.doucko.shared.adapter.`in`.web.SecurityConfiguration
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.put

private const val SETTINGS = "/api/child/profile/settings"

private fun MockMvc.patchSettings(
    body: String,
    type: MediaType = MediaType.APPLICATION_JSON,
) = patch(SETTINGS) {
    contentType = type
    content = body
}

private fun ResultActionsDsl.isSettings(
    locale: String?,
    mode: String = "DIRECT",
) = andExpect {
    status { isOk() }
    content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
    jsonPath("$.secondaryTaskLocale") { value(locale) }
    jsonPath("$.answerMode") { value(mode) }
}

private fun ResultActionsDsl.isProblem(
    status: Int,
    vararg fields: String,
) = andExpect {
    status { isEqualTo(status) }
    content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
    jsonPath("$.status") { value(status) }
    jsonPath("$.title") { isNotEmpty() }
    if (fields.isNotEmpty()) jsonPath("$.errors[*].field") { value(org.hamcrest.Matchers.containsInAnyOrder(*fields)) }
}

/** child-profile "Profile settings" and "Invalid settings are refused without side effects" over the web layer. */
@WebMvcTest
@Import(
    SecurityConfiguration::class,
    AdminSecurityConfiguration::class,
    AdminWebTestBeans::class,
    ProfileWebTestBeans::class,
    ContentWebTestBeans::class,
)
class ProfileSettingsTest {
    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var store: InMemoryChildProfileStore

    @BeforeEach
    fun reset() = store.reset()

    private fun given(locale: String?) {
        store.current = ProfileSettings(locale, AnswerMode.DIRECT)
    }

    private fun unchanged(locale: String?) {
        store.current shouldBe ProfileSettings(locale, AnswerMode.DIRECT)
        store.writes shouldBe 0
    }

    // @trace FR-43
    @Test
    fun `a new profile has no translation and direct answers`() {
        mvc.get(SETTINGS).isSettings(null)
    }

    // @trace FR-43
    // @trace NFR-1
    @Test
    fun `settings need no session, cookie or CSRF token`() {
        mvc.get(SETTINGS).isSettings(null)
        mvc.patchSettings("""{"secondaryTaskLocale":"uk"}""").isSettings("uk")
        mvc.get(SETTINGS).isSettings("uk")
    }

    // @trace FR-43
    @Test
    fun `an unrelated admin session cookie is not needed and not used`() {
        mvc
            .patch(SETTINGS) {
                contentType = MediaType.APPLICATION_JSON
                content = """{"secondaryTaskLocale":"uk"}"""
                cookie(Cookie("XSRF-TOKEN", "stale"))
            }.isSettings("uk")
    }

    // @trace FR-43
    @Test
    fun `set and clear the secondary locale`() {
        mvc.patchSettings("""{"secondaryTaskLocale":"uk"}""").isSettings("uk")
        mvc.get(SETTINGS).isSettings("uk")
        mvc.patchSettings("""{"secondaryTaskLocale":null}""").isSettings(null)
        mvc.get(SETTINGS).isSettings(null)
    }

    // @trace FR-43
    @Test
    fun `an omitted field means no change`() {
        given("uk")
        mvc.patchSettings("""{"answerMode":"DIRECT"}""").isSettings("uk")
        store.current.secondaryTaskLocale shouldBe "uk"
    }

    // @trace FR-43
    @Test
    fun `an empty object is a successful no-op`() {
        given("uk")
        mvc.patchSettings("{}").isSettings("uk")
        store.current shouldBe ProfileSettings("uk", AnswerMode.DIRECT)
    }

    // @trace FR-43
    @Test
    fun `unknown top-level fields are ignored`() {
        mvc.patchSettings("""{"secondaryTaskLocale":"uk","displayName":"Někdo","secondaryLocale":"ru"}""").isSettings("uk")
        mvc.patchSettings("""{"displayName":{"nested":[1,2]}}""").isSettings("uk")
        store.displayName shouldBe FICTIONAL_NAME
    }

    // @trace FR-43
    @Test
    fun `guided mode is not available yet`() {
        mvc.patchSettings("""{"answerMode":"GUIDED"}""").isProblem(422)
        mvc.get(SETTINGS).isSettings(null)
        unchanged(null)
    }

    // @trace FR-43
    @Test
    fun `a guided refusal also discards the locale of the same request`() {
        mvc.patchSettings("""{"secondaryTaskLocale":"uk","answerMode":"GUIDED"}""").isProblem(422)
        mvc.get(SETTINGS).isSettings(null)
        unchanged(null)
    }

    // @trace FR-43
    @Test
    fun `a validation error wins over GUIDED`() {
        mvc.patchSettings("""{"secondaryTaskLocale":"ru","answerMode":"GUIDED"}""").isProblem(400, "secondaryTaskLocale")
        unchanged(null)
    }

    // @trace FR-43
    @Test
    fun `an unsupported locale is refused and the detail lists the supported ones`() {
        mvc
            .patchSettings("""{"secondaryTaskLocale":"ru"}""")
            .isProblem(400, "secondaryTaskLocale")
            .andExpect { jsonPath("$.detail") { value(org.hamcrest.Matchers.containsString("uk")) } }
        unchanged(null)
    }

    // @trace FR-43
    @ParameterizedTest
    @ValueSource(strings = ["UK", "uk-UA", " uk"])
    fun `the locale is matched exactly`(value: String) {
        mvc.patchSettings("""{"secondaryTaskLocale":"$value"}""").isProblem(400, "secondaryTaskLocale")
        unchanged(null)
    }

    // @trace FR-43
    @Test
    fun `an empty string does not clear the locale`() {
        given("uk")
        mvc.patchSettings("""{"secondaryTaskLocale":""}""").isProblem(400, "secondaryTaskLocale")
        mvc.get(SETTINGS).isSettings("uk")
        unchanged("uk")
    }

    // @trace FR-43
    @Test
    fun `a null answer mode is refused with the valid locale beside it`() {
        mvc.patchSettings("""{"answerMode":null,"secondaryTaskLocale":"uk"}""").isProblem(400, "answerMode")
        mvc.get(SETTINGS).isSettings(null)
        unchanged(null)
    }

    // @trace FR-43
    @ParameterizedTest
    @ValueSource(strings = ["SOCRATIC", "direct"])
    fun `an unknown answer mode is refused`(value: String) {
        mvc.patchSettings("""{"answerMode":"$value"}""").isProblem(400, "answerMode")
        unchanged(null)
    }

    // @trace FR-43
    @ParameterizedTest
    @ValueSource(
        strings = [
            """{"secondaryTaskLocale":5}|secondaryTaskLocale""",
            """{"answerMode":true}|answerMode""",
            """{"secondaryTaskLocale":["uk"]}|secondaryTaskLocale""",
            """{"answerMode":{"value":"DIRECT"}}|answerMode""",
        ],
    )
    fun `wrong JSON types are refused naming the field`(case: String) {
        val (body, field) = case.split('|')
        mvc.patchSettings(body).isProblem(400, field)
        unchanged(null)
    }

    // @trace FR-43
    @Test
    fun `every invalid field is named`() {
        mvc.patchSettings("""{"secondaryTaskLocale":7,"answerMode":null}""").isProblem(400, "secondaryTaskLocale", "answerMode")
        unchanged(null)
    }

    // @trace FR-43
    // @trace NFR-3
    @Test
    fun `an oversized locale is refused briefly and never echoed`() {
        val value = "u".repeat(10_240)
        val body =
            mvc
                .patchSettings("""{"secondaryTaskLocale":"$value"}""")
                .isProblem(400, "secondaryTaskLocale")
                .andReturn()
                .response.contentAsByteArray
        body.size shouldBeLessThan 2_048
        String(body, Charsets.UTF_8) shouldNotContain "uuuuuuuuuuuu"
        unchanged(null)
    }

    // @trace NFR-3
    @Test
    fun `a refused value is never echoed`() {
        val sentinel = "PII-SENTINEL-4d1"
        for (body in listOf(
            """{"secondaryTaskLocale":"$sentinel"}""",
            """{"answerMode":"$sentinel"}""",
            """{"answerMode": "$sentinel""",
        )) {
            mvc
                .patchSettings(body)
                .andReturn()
                .response.contentAsString shouldNotContain sentinel
        }
    }

    // @trace FR-43
    @ParameterizedTest
    @ValueSource(strings = ["""{"answerMode": """, "[]", """["uk"]""", "\"uk\"", "null", "5", "", "{} {}"])
    fun `a body that is not one JSON object is refused`(body: String) {
        mvc.patchSettings(body).isProblem(400)
        unchanged(null)
    }

    // @trace FR-43
    @Test
    fun `a child body over 16 KB is too large`() {
        mvc.patchSettings("""{"secondaryTaskLocale":"uk","pad":"${"x".repeat(16 * 1024)}"}""").isProblem(413)
        unchanged(null)
    }

    // @trace FR-43
    // @trace NFR-1
    @Test
    fun `the admin API offers no way to change the profile`() {
        given("uk")
        val admin = mvc.signedIn()
        for (request in listOf(
            mvc.patch("/api/admin/profile/settings") {
                session = admin.session
                cookie(Cookie("XSRF-TOKEN", admin.xsrf))
                header("X-XSRF-TOKEN", admin.xsrf)
                contentType = MediaType.APPLICATION_JSON
                content = """{"secondaryTaskLocale":null}"""
            },
            mvc.put("/api/admin/profile") {
                session = admin.session
                cookie(Cookie("XSRF-TOKEN", admin.xsrf))
                header("X-XSRF-TOKEN", admin.xsrf)
                contentType = MediaType.APPLICATION_JSON
                content = """{"secondaryTaskLocale":null}"""
            },
        )) {
            request.isProblem(404)
        }
        mvc.get(SETTINGS).isSettings("uk")
        unchanged("uk")
    }

    /** child-profile "Removing the configured locale makes it refused": no code change, only configuration. */
    @Nested
    @WebMvcTest(properties = ["doucko.profile.task-locales="])
    @Import(
        SecurityConfiguration::class,
        AdminSecurityConfiguration::class,
        AdminWebTestBeans::class,
        ProfileWebTestBeans::class,
        ContentWebTestBeans::class,
    )
    inner class WithoutConfiguredLocales {
        @Autowired
        lateinit var mvc: MockMvc

        @Autowired
        lateinit var store: InMemoryChildProfileStore

        // @trace FR-43
        @Test
        fun `uk is refused and null still succeeds`() {
            store.reset()
            mvc.patchSettings("""{"secondaryTaskLocale":"uk"}""").isProblem(400, "secondaryTaskLocale")
            store.writes shouldBe 0
            mvc.patchSettings("""{"secondaryTaskLocale":null}""").isSettings(null)
        }
    }
}
