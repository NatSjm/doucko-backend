package cz.doucko.content

import com.jayway.jsonpath.JsonPath
import cz.doucko.TestcontainersConfiguration
import cz.doucko.admin.Admin
import cz.doucko.admin.signedIn
import cz.doucko.content.application.FileStoragePort
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.hamcrest.Matchers.contains
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import java.io.File
import java.nio.file.Path
import java.nio.file.Files as NioFiles

/**
 * content-authoring against Postgres 16 and the real file storage: JSONB round trips, the folded search
 * in SQL, image positions under the deferred constraint, source delete rules with the foreign key, and
 * uploaded files outside every repository (FR-1, FR-3..FR-8, FR-11, FR-13, NFR-17).
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension::class)
class ContentAuthoringIT {
    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var storage: FileStoragePort

    lateinit var admin: Admin

    @BeforeEach
    fun clean() {
        // Practice rows reference tasks (the practice ITs share this context and database).
        jdbc.execute("TRUNCATE xp_award, rank_celebration, attempt_reevaluation")
        jdbc.update("DELETE FROM problem_report")
        jdbc.update("DELETE FROM opened_hint")
        jdbc.update("DELETE FROM attempt")
        jdbc.update("DELETE FROM mission")
        jdbc.update("DELETE FROM task_image")
        jdbc.update("DELETE FROM task")
        jdbc.update("DELETE FROM source")
        admin = mvc.signedIn()
    }

    private fun source(pages: Int = 120): String =
        JsonPath.read(
            mvc
                .uploadSource(admin, Files.pdf(pages), *SOURCE_FIELDS)
                .andExpect { status { isCreated() } }
                .andReturn()
                .response.contentAsString,
            "$.sourceId",
        )

    private fun task(
        sourceId: String,
        vararg overrides: Pair<String, String>,
    ): String =
        JsonPath.read(
            mvc
                .adminJson("POST", admin, "/api/admin/tasks", taskJson(sourceId, *overrides))
                .andExpect { status { isCreated() } }
                .andReturn()
                .response.contentAsString,
            "$.taskId",
        )

    private fun storageKeys(): List<String> =
        jdbc.queryForList("SELECT storage_key FROM source UNION ALL SELECT storage_key FROM task_image", String::class.java).filterNotNull()

    // @trace FR-3
    // @trace FR-6
    // @trace FR-8
    @Test
    fun `a task with parts, hints and translations survives the JSONB round trip`() {
        val sourceId = source()
        val id =
            task(
                sourceId,
                "parts" to
                    """[{"key":"a","label":"a","spec":{"type":"INTEGER","accepted":["12"]}},
                       {"key":"b","label":"b","spec":{"type":"NUMBER_WITH_UNIT","accepted":["24 cm2"],"unitRequired":false}},
                       {"key":"c","label":"c","spec":{"type":"CHOICE_SINGLE","accepted":["A","C"],
                        "options":[{"key":"A","text":"3"},{"key":"B","text":"4"},{"key":"C","text":"5"}]}},
                       {"key":"d","label":"d","spec":{"type":"DECIMAL","accepted":["0,50"],"exactDecimals":2,"tolerance":"0,01"}}]""",
                "hints" to """["první","druhá"]""",
                "translations" to
                    """{"uk":{"prompt":"Обчисли.","partLabels":{"a":"а"},"hints":[null,"друга"],"options":{"c.B":"чотири"}}}""",
            )
        mvc.adminGet(admin, "/api/admin/tasks/$id").andExpect {
            status { isOk() }
            jsonPath("$.parts[*].key") { value(contains("a", "b", "c", "d")) }
            jsonPath("$.parts[1].spec.unitRequired") { value(false) }
            jsonPath("$.parts[1].spec.unit") { value("cm²") }
            jsonPath("$.parts[2].spec.accepted") { value(contains("A", "C")) }
            jsonPath("$.parts[3].spec.exactDecimals") { value(2) }
            jsonPath("$.parts[3].spec.tolerance") { value("0,01") }
            jsonPath("$.hints") { value(contains("první", "druhá")) }
            jsonPath("$.translations.uk.options['c.B']") { value("чотири") }
            jsonPath("$.translations.uk.hints[0]") { value(null) }
            jsonPath("$.status") { value("DRAFT") }
        }
        jdbc.queryForObject("SELECT jsonb_typeof(parts) FROM task", String::class.java) shouldBe "array"
    }

    // @trace FR-1
    // @trace FR-3
    // @trace FR-13
    @Test
    fun `edits, status changes and bulk publish are stored`() {
        val sourceId = source()
        val id = task(sourceId)
        mvc
            .adminJson("PUT", admin, "/api/admin/tasks/$id", taskJson(sourceId, "prompt" to "\"Upravené zadání\"", "difficulty" to "3"))
            .andExpect { status { isOk() } }
        jdbc.queryForMap("SELECT prompt, prompt_folded, difficulty FROM task").let {
            it["prompt"] shouldBe "Upravené zadání"
            it["prompt_folded"] shouldBe "upravene zadani"
            it["difficulty"] shouldBe 3
        }
        mvc.adminJson("PUT", admin, "/api/admin/tasks/$id/status", """{"status":"PUBLISHED"}""").andExpect { status { isOk() } }
        jdbc.queryForObject("SELECT status FROM task", String::class.java) shouldBe "PUBLISHED"
        val other = task(sourceId)
        mvc.adminJson("POST", admin, "/api/admin/tasks/publish", """{"taskIds":["$id","$other"]}""").andExpect { status { isOk() } }
        jdbc.queryForList("SELECT DISTINCT status FROM task", String::class.java) shouldContainExactly listOf("PUBLISHED")
        mvc
            .adminJson("PUT", admin, "/api/admin/sources/$sourceId", """{"title":"Nový název","edition":"2","grade":6,"subject":"math"}""")
            .andExpect { status { isOk() } }
        mvc.adminGet(admin, "/api/admin/tasks/$id").andExpect {
            jsonPath("$.provenance.sourceTitle") { value("Nový název") }
            jsonPath("$.provenance.edition") { value("2") }
        }
        mvc.adminGet(admin, "/api/admin/reference").andExpect { jsonPath("$.topics.length()") { value(9) } }
    }

    // @trace FR-11
    // @trace FR-13
    @Test
    fun `filters and the folded search run in SQL`() {
        val sourceId = source()
        val match = task(sourceId, "prompt" to "\"Urči obvod obdélníku 100% přesně_\"")
        task(sourceId, "prompt" to "\"Sečti čísla.\"")
        task(sourceId, "prompt" to "\"Obvod obdélníku.\"", "sourceRef" to """{"sourceId":"$sourceId","page":58,"taskNo":"1"}""")
        for (q in listOf("OBDELNIK", "obdélník", "100%", "_")) {
            mvc.adminGet(admin, "/api/admin/tasks", "q" to q, "page" to "57").andExpect {
                jsonPath("$[*].taskId") { value(contains(match)) }
            }
        }
        mvc.adminGet(admin, "/api/admin/tasks", "sourceId" to sourceId, "page" to "57", "status" to "DRAFT").andExpect {
            jsonPath("$.length()") { value(2) }
            jsonPath("$[0].provenance.sourceTitle") { value("Matematika 6 – pracovní sešit") }
            jsonPath("$[0].provenance.taskNo") { value("4") }
        }
        mvc.adminGet(admin, "/api/admin/tasks", "modality" to "GRAPHIC").andExpect { jsonPath("$.length()") { value(0) } }
    }

    // @trace FR-4
    @Test
    fun `image positions swap under the deferred constraint and close gaps on delete`() {
        val id = task(source())
        val ids =
            (1..3).map {
                JsonPath.read<String>(
                    mvc
                        .addImage(admin, id, Files.png(it, it))
                        .andReturn()
                        .response.contentAsString,
                    "$.imageId",
                )
            }
        mvc
            .adminJson("PUT", admin, "/api/admin/tasks/$id/images", """{"imageIds":["${ids[2]}","${ids[0]}","${ids[1]}"]}""")
            .andExpect { jsonPath("$[*].imageId") { value(contains(ids[2], ids[0], ids[1])) } }
        mvc.adminDelete(admin, "/api/admin/tasks/$id/images/${ids[0]}").andExpect { status { isNoContent() } }
        jdbc.queryForList("SELECT position FROM task_image ORDER BY position", Int::class.java) shouldContainExactly listOf(1, 2)
        mvc.adminGet(admin, "/api/admin/tasks/$id").andExpect { jsonPath("$.images[*].imageId") { value(contains(ids[2], ids[1])) } }
        mvc.adminDelete(admin, "/api/admin/tasks/$id").andExpect { status { isNoContent() } }
        jdbc.queryForObject("SELECT count(*) FROM task_image", Int::class.java) shouldBe 0
    }

    // @trace FR-1
    // @trace NFR-17
    @Test
    fun `a referenced source is kept, an unused one is deleted with its file`() {
        val sourceId = source()
        task(sourceId)
        val key = storageKeys().single()
        mvc.adminDelete(admin, "/api/admin/sources/$sourceId").andExpect { status { isConflict() } }
        NioFiles.exists(storage.locate(key)) shouldBe true
        jdbc.update("DELETE FROM task")
        mvc.adminDelete(admin, "/api/admin/sources/$sourceId").andExpect { status { isNoContent() } }
        NioFiles.exists(storage.locate(key)) shouldBe false
    }

    // @trace NFR-17
    @Test
    fun `refused uploads leave no file on the storage volume`() {
        val root = storage.locate("sources/x").parent.parent

        fun count() = if (NioFiles.exists(root)) NioFiles.walk(root).use { s -> s.filter { NioFiles.isRegularFile(it) }.count() } else 0L
        val before = count()
        mvc
            .uploadSource(
                admin,
                Files.pdf(2),
                "title" to "Sešit",
                "grade" to "7",
                "subject" to "math",
            ).andExpect { status { isBadRequest() } }
        mvc.uploadSource(admin, Files.encryptedPdf(), *SOURCE_FIELDS).andExpect { status { isBadRequest() } }
        mvc.uploadSource(admin, Files.png(), *SOURCE_FIELDS).andExpect { status { isUnsupportedMediaType() } }
        count() shouldBe before
        jdbc.queryForObject("SELECT count(*) FROM source", Int::class.java) shouldBe 0
    }

    // @trace NFR-17
    @Test
    fun `uploaded files are stored outside every repository working tree`() {
        val id = task(source())
        mvc.addImage(admin, id, Files.png()).andExpect { status { isCreated() } }
        val paths = storageKeys().map { storage.locate(it).toAbsolutePath().normalize() }
        paths.size shouldBe 2
        val here = File(".").absoluteFile.toPath().normalize()
        val repositories: List<Path> =
            listOf(here, here.resolveSibling("docs"), here.resolveSibling("frontend"), here.resolveSibling("backend"))
                .map { it.toAbsolutePath().normalize() }
        for (path in paths) {
            NioFiles.isRegularFile(path) shouldBe true
            for (repository in repositories) path.startsWith(repository) shouldBe false
        }
    }

    // @trace NFR-3
    @Test
    fun `mission requests are not logged`(output: CapturedOutput) {
        val sentinel = "PII-SENTINEL-mission-${System.nanoTime()}"
        val id = task(source())
        val body = """{"topicId":"${Topics.DECIMALS}","position":1,"taskIds":["$id"]}"""
        val missionId: String =
            JsonPath.read(
                mvc
                    .adminJson("POST", admin, "/api/admin/missions", body)
                    .andExpect { status { isCreated() } }
                    .andReturn()
                    .response.contentAsString,
                "$.missionId",
            )
        mvc.adminJson("PUT", admin, "/api/admin/missions/$missionId", body.replace("1,", quote(sentinel) + ","))
        mvc.adminJson(
            "POST",
            admin,
            "/api/admin/missions",
            """{"topicId":${quote(sentinel)},"position":2,"taskIds":[${quote(sentinel)}]}""",
        )
        mvc.adminJson("PUT", admin, "/api/admin/missions/$missionId/status", """{"status":${quote(sentinel)}}""")
        output.all shouldNotContain sentinel
    }

    // @trace NFR-3
    @Test
    fun `authoring requests are not logged`(output: CapturedOutput) {
        val sentinel = "PII-SENTINEL-content-${System.nanoTime()}"
        val sourceId = source()
        task(sourceId, "prompt" to quote(sentinel))
        mvc.adminJson("POST", admin, "/api/admin/tasks", taskJson(sourceId, "difficulty" to quote(sentinel)))
        mvc.adminGet(admin, "/api/admin/tasks", "q" to sentinel)
        mvc.uploadSource(admin, Files.pdf(1), "title" to sentinel, "grade" to "6", "subject" to "math")
        output.all shouldNotContain sentinel
    }

    // @trace FR-3
    @Test
    fun `a NUL character in task text is a 400 problem, not a conflict or a 500, and stores nothing`() {
        val sourceId = source()
        for (field in listOf("prompt" to "\"a\u0000b\"", "hints" to """["a\u0000b"]""")) {
            mvc.adminJson("POST", admin, "/api/admin/tasks", taskJson(sourceId, field)).andExpect {
                status { isBadRequest() }
                content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
            }
        }
        jdbc.queryForObject("SELECT count(*) FROM task", Int::class.java) shouldBe 0
    }
}
