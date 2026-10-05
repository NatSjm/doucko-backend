package cz.doucko.content

import com.jayway.jsonpath.JsonPath
import cz.doucko.admin.AdminWebTestBeans
import cz.doucko.admin.adapter.`in`.web.AdminSecurityConfiguration
import cz.doucko.admin.signedIn
import cz.doucko.content.domain.Modality
import cz.doucko.content.domain.Part
import cz.doucko.content.domain.PublishStatus
import cz.doucko.content.domain.SourceRef
import cz.doucko.content.domain.Spec
import cz.doucko.content.domain.Task
import cz.doucko.content.domain.TaskContent
import cz.doucko.practice.PracticeWebTestBeans
import cz.doucko.profile.ProfileWebTestBeans
import cz.doucko.shared.adapter.`in`.web.SecurityConfiguration
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart
import java.util.UUID

private const val DAMAGED = "The PDF could not be read. It may be damaged or password-protected."
private const val ONLY_PDF = "Only PDF files up to 50 MB are allowed."

fun ResultActionsDsl.isFieldProblem(
    status: Int,
    field: String,
    message: String? = null,
) = andExpect {
    status { isEqualTo(status) }
    content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
    jsonPath("$.status") { value(status) }
    jsonPath("$.errors[0].field") { value(field) }
    message?.let { jsonPath("$.errors[0].message") { value(containsString(it)) } }
}

/** A task referencing [sourceId], stored directly (the source rules need referencing tasks). */
fun storedTask(sourceId: UUID) =
    Task(
        UUID.randomUUID(),
        TaskContent(
            "Úloha",
            Topics.DECIMALS,
            6,
            1,
            Modality.TEXT,
            SourceRef(sourceId, 1, "1", null),
            listOf(Part("a", "a", Spec(cz.doucko.checking.AnswerType.INTEGER, listOf("1")))),
            emptyList(),
            emptyMap(),
        ),
        PublishStatus.DRAFT,
        emptyList(),
    )

/** content-authoring "Upload a PDF source", "Upload limits and storage", "Edit existing content" for sources (FR-1, FR-2, NFR-17). */
@WebMvcTest
@Import(
    SecurityConfiguration::class,
    AdminSecurityConfiguration::class,
    AdminWebTestBeans::class,
    ProfileWebTestBeans::class,
    ContentWebTestBeans::class,
    PracticeWebTestBeans::class,
)
class SourceApiTest {
    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var storage: InMemoryFileStorage

    @Autowired
    lateinit var sources: InMemorySourceStore

    @Autowired
    lateinit var tasks: InMemoryTaskStore

    @BeforeEach
    fun clear() {
        storage.files.clear()
        sources.sources.clear()
        tasks.tasks.clear()
    }

    private fun upload(): String {
        val admin = mvc.signedIn()
        val body =
            mvc
                .uploadSource(admin, Files.pdf(120), *SOURCE_FIELDS)
                .andExpect { status { isCreated() } }
                .andReturn()
                .response.contentAsString
        return JsonPath.read(body, "$.sourceId")
    }

    // @trace FR-1
    @Test
    fun `upload a source and see it listed with its page count`() {
        val admin = mvc.signedIn()
        mvc.uploadSource(admin, Files.pdf(120), *SOURCE_FIELDS).andExpect {
            status { isCreated() }
            jsonPath("$.title") { value("Matematika 6 – pracovní sešit") }
            jsonPath("$.pageCount") { value(120) }
        }
        mvc.adminGet(admin, "/api/admin/sources").andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].title") { value("Matematika 6 – pracovní sešit") }
            jsonPath("$[0].publisher") { value("Testovací nakladatelství") }
            jsonPath("$[0].edition") { value("1") }
            jsonPath("$[0].grade") { value(6) }
            jsonPath("$[0].subject") { value("math") }
            jsonPath("$[0].pageCount") { value(120) }
        }
        storage.files.size shouldBe 1
    }

    // @trace FR-1
    @Test
    fun `publisher and edition are optional`() {
        val admin = mvc.signedIn()
        mvc.uploadSource(admin, Files.pdf(2), "title" to "Sešit", "grade" to "6", "subject" to "math").andExpect {
            status { isCreated() }
            jsonPath("$.publisher") { doesNotExist() }
            jsonPath("$.edition") { doesNotExist() }
        }
    }

    // @trace FR-1
    @Test
    fun `missing, blank or overlong title is refused and nothing is stored`() {
        val admin = mvc.signedIn()
        mvc.uploadSource(admin, Files.pdf(2), "grade" to "6", "subject" to "math").isFieldProblem(400, "title")
        mvc.uploadSource(admin, Files.pdf(2), "title" to "   ", "grade" to "6", "subject" to "math").isFieldProblem(400, "title")
        mvc
            .uploadSource(admin, Files.pdf(2), "title" to "t".repeat(201), "grade" to "6", "subject" to "math")
            .isFieldProblem(400, "title", "200")
        sources.sources.size shouldBe 0
        storage.files.shouldBeEmpty()
    }

    // @trace FR-1
    @ParameterizedTest
    @ValueSource(strings = ["7", "abc", "6,0", "99999999999"])
    fun `a grade that is not configured is refused listing the allowed values`(grade: String) {
        val admin = mvc.signedIn()
        mvc
            .uploadSource(admin, Files.pdf(2), "title" to "Sešit", "grade" to grade, "subject" to "math")
            .isFieldProblem(400, "grade", "6")
        sources.sources.size shouldBe 0
        storage.files.shouldBeEmpty()
    }

    // @trace FR-1
    @Test
    fun `a subject that is not configured is refused`() {
        val admin = mvc.signedIn()
        mvc
            .uploadSource(admin, Files.pdf(2), "title" to "Sešit", "grade" to "6", "subject" to "physics")
            .isFieldProblem(400, "subject", "math")
        storage.files.shouldBeEmpty()
    }

    // @trace NFR-17
    @Test
    fun `a docx or a PNG renamed to pdf is refused as not a PDF`() {
        val admin = mvc.signedIn()
        for (file in listOf(Files.docx(), Files.png())) {
            mvc.uploadSource(admin, file, *SOURCE_FIELDS).andExpect {
                status { isEqualTo(415) }
                content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
                jsonPath("$.detail") { value(ONLY_PDF) }
            }
        }
        sources.sources.size shouldBe 0
        storage.files.shouldBeEmpty()
    }

    // @trace NFR-17
    @Test
    fun `an encrypted, truncated or broken PDF is refused as unreadable`() {
        val admin = mvc.signedIn()
        for (file in listOf(Files.encryptedPdf(), Files.truncatedPdf(), Files.brokenPageTreePdf())) {
            mvc.uploadSource(admin, file, *SOURCE_FIELDS).isFieldProblem(400, "file", DAMAGED)
        }
        sources.sources.size shouldBe 0
        storage.files.shouldBeEmpty()
    }

    // @trace NFR-17
    @Test
    fun `a missing or empty file is refused on the file field`() {
        val admin = mvc.signedIn()
        mvc.uploadSource(admin, null, *SOURCE_FIELDS).isFieldProblem(400, "file")
        mvc.uploadSource(admin, ByteArray(0), *SOURCE_FIELDS).isFieldProblem(400, "file")
    }

    // @trace FR-1
    @Test
    fun `authoring needs the admin session and the CSRF token`() {
        mvc.get("/api/admin/sources").andExpect { status { isUnauthorized() } }
        val admin = mvc.signedIn()
        mvc
            .multipart("/api/admin/sources") {
                session = admin.session
                file(MockMultipartFile("file", "a.pdf", "application/pdf", Files.pdf(1)))
                SOURCE_FIELDS.forEach { (k, v) -> param(k, v) }
            }.andExpect { status { isForbidden() } }
        sources.sources.size shouldBe 0
    }

    // @trace FR-2
    @Test
    fun `the PDF is served for the viewer, unknown ids are 404 and malformed ids 400`() {
        val id = upload()
        val admin = mvc.signedIn()
        val bytes =
            mvc
                .adminGet(admin, "/api/admin/sources/$id/file")
                .andExpect {
                    status { isOk() }
                    content { contentType(MediaType.APPLICATION_PDF) }
                }.andReturn()
                .response.contentAsByteArray
        bytes.toList() shouldBe
            storage.files.values
                .single()
                .toList()
        mvc.adminGet(admin, "/api/admin/sources/$id").andExpect { jsonPath("$.pageCount") { value(120) } }
        mvc.adminGet(admin, "/api/admin/sources/${UUID.randomUUID()}").andExpect { status { isNotFound() } }
        mvc.adminGet(admin, "/api/admin/sources/not-a-uuid").isFieldProblem(400, "sourceId")
    }

    // @trace FR-1
    @Test
    fun `source metadata is edited, an invalid edit leaves it unchanged`() {
        val id = upload()
        val admin = mvc.signedIn()
        val path = "/api/admin/sources/$id"
        mvc.adminJson("PUT", admin, path, """{"title":"Sešit","edition":"2","grade":6,"subject":"math"}""").andExpect {
            status { isOk() }
            jsonPath("$.edition") { value("2") }
            jsonPath("$.publisher") { doesNotExist() }
        }
        mvc.adminJson("PUT", admin, path, """{"title":" ","grade":6,"subject":"math"}""").isFieldProblem(400, "title")
        mvc.adminJson("PUT", admin, path, """{"title":"X","grade":6,"subject":"physics"}""").isFieldProblem(400, "subject")
        mvc.adminJson("PUT", admin, path, """{"title":"X","grade":"6","subject":"math"}""").isFieldProblem(400, "grade")
        mvc.adminJson("PUT", admin, path, "[]").andExpect { status { isBadRequest() } }
        sources.sources.values
            .single()
            .metadata.title shouldBe "Sešit"
    }

    // @trace FR-1
    @Test
    fun `the source file can never be replaced`() {
        val id = upload()
        val before =
            storage.files.values
                .single()
                .toList()
        val admin = mvc.signedIn()
        mvc
            .adminJson("PUT", admin, "/api/admin/sources/$id", """{"title":"X","grade":6,"subject":"math","file":"JVBERi0="}""")
            .isFieldProblem(400, "file")
        mvc
            .multipart("/api/admin/sources/$id") {
                with(
                    org.springframework.test.web.servlet.request.RequestPostProcessor {
                        it.method = "PUT"
                        it
                    },
                )
                session = admin.session
                cookie(jakarta.servlet.http.Cookie("XSRF-TOKEN", admin.xsrf))
                header("X-XSRF-TOKEN", admin.xsrf)
                file(MockMultipartFile("file", "b.pdf", "application/pdf", Files.pdf(3)))
            }.andExpect { status { isUnsupportedMediaType() } }
        storage.files.values
            .single()
            .toList() shouldBe before
    }

    // @trace FR-1
    @Test
    fun `a referenced source cannot be deleted, an unused one is deleted with its file`() {
        val id = UUID.fromString(upload())
        val admin = mvc.signedIn()
        repeat(3) { storedTask(id).also { tasks.insert(it) } }
        mvc.adminDelete(admin, "/api/admin/sources/$id").andExpect {
            status { isConflict() }
            jsonPath("$.detail") { value(containsString("3 tasks reference")) }
            jsonPath("$.conflicts.length()") { value(3) }
        }
        storage.files.size shouldBe 1
        tasks.tasks.clear()
        mvc.adminDelete(admin, "/api/admin/sources/$id").andExpect { status { isNoContent() } }
        mvc.adminGet(admin, "/api/admin/sources").andExpect { jsonPath("$.length()") { value(0) } }
        storage.files.shouldBeEmpty()
        mvc.adminDelete(admin, "/api/admin/sources/$id").andExpect { status { isNotFound() } }
    }

    @Nested
    @WebMvcTest(properties = ["doucko.content.grades=6,7"])
    @Import(
        SecurityConfiguration::class,
        AdminSecurityConfiguration::class,
        AdminWebTestBeans::class,
        ProfileWebTestBeans::class,
        ContentWebTestBeans::class,
        PracticeWebTestBeans::class,
    )
    inner class WithGradeSeven {
        @Autowired
        lateinit var mvc: MockMvc

        @Autowired
        lateinit var sources: InMemorySourceStore

        @Autowired
        lateinit var tasks: InMemoryTaskStore

        // @trace FR-1
        @Test
        fun `the grade of a referenced source is locked`() {
            sources.sources.clear()
            tasks.tasks.clear()
            val admin = mvc.signedIn()
            val id =
                UUID.fromString(
                    JsonPath.read(
                        mvc
                            .uploadSource(admin, Files.pdf(2), *SOURCE_FIELDS)
                            .andReturn()
                            .response.contentAsString,
                        "$.sourceId",
                    ),
                )
            tasks.insert(storedTask(id))
            mvc
                .adminJson("PUT", admin, "/api/admin/sources/$id", """{"title":"Sešit","grade":7,"subject":"math"}""")
                .andExpect {
                    status { isConflict() }
                    jsonPath("$.detail") { value(containsString("1 task references")) }
                }
            sources.sources
                .getValue(id)
                .metadata.grade shouldBe 6
            tasks.tasks.clear()
            mvc
                .adminJson("PUT", admin, "/api/admin/sources/$id", """{"title":"Sešit","grade":7,"subject":"math"}""")
                .andExpect { status { isOk() } }
        }
    }
}
