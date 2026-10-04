package cz.doucko.content

import com.jayway.jsonpath.JsonPath
import cz.doucko.admin.Admin
import cz.doucko.admin.AdminWebTestBeans
import cz.doucko.admin.adapter.`in`.web.AdminSecurityConfiguration
import cz.doucko.admin.signedIn
import cz.doucko.checking.AnswerChecker
import cz.doucko.checking.AnswerSpec
import cz.doucko.checking.AnswerType
import cz.doucko.content.domain.PublishStatus
import cz.doucko.profile.ProfileWebTestBeans
import cz.doucko.shared.adapter.`in`.web.SecurityConfiguration
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID

/**
 * content-authoring tasks over the web layer: create and edit with every field rule, list filters and
 * search, images, preview, answer tester, status and bulk publish (FR-3..FR-8, FR-10, FR-11, FR-13).
 */
@WebMvcTest
@Import(
    SecurityConfiguration::class,
    AdminSecurityConfiguration::class,
    AdminWebTestBeans::class,
    ProfileWebTestBeans::class,
    ContentWebTestBeans::class,
)
class TaskApiTest {
    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var storage: InMemoryFileStorage

    @Autowired
    lateinit var sources: InMemorySourceStore

    @Autowired
    lateinit var tasks: InMemoryTaskStore

    lateinit var admin: Admin
    lateinit var sourceId: String

    @BeforeEach
    fun setUp() {
        storage.files.clear()
        sources.sources.clear()
        tasks.tasks.clear()
        admin = mvc.signedIn()
        sourceId =
            JsonPath.read(
                mvc
                    .uploadSource(admin, Files.pdf(120), *SOURCE_FIELDS)
                    .andReturn()
                    .response.contentAsString,
                "$.sourceId",
            )
    }

    private fun create(vararg overrides: Pair<String, String>) =
        mvc.adminJson("POST", admin, "/api/admin/tasks", taskJson(sourceId, *overrides))

    private fun createId(vararg overrides: Pair<String, String>): String =
        JsonPath.read(
            create(*overrides)
                .andExpect { status { isCreated() } }
                .andReturn()
                .response.contentAsString,
            "$.taskId",
        )

    private fun parts(vararg parts: String) = "parts" to parts.joinToString(",", "[", "]")

    private fun part(
        key: String,
        spec: String,
        label: String = key,
    ) = """{"key":"$key","label":"$label","spec":$spec}"""

    // @trace FR-3
    // @trace FR-13
    @Test
    fun `create a multi-part task as DRAFT with its source reference`() {
        create(
            parts(
                part("a", """{"type":"INTEGER","accepted":["12"]}"""),
                part("b", """{"type":"NUMBER_WITH_UNIT","accepted":["24 cm2"],"unitRequired":true}"""),
            ),
        ).andExpect {
            status { isCreated() }
            jsonPath("$.status") { value("DRAFT") }
            jsonPath("$.topicId") { value(Topics.DECIMALS.toString()) }
            jsonPath("$.difficulty") { value(2) }
            jsonPath("$.sourceRef.page") { value(57) }
            jsonPath("$.sourceRef.taskNo") { value("4") }
            jsonPath("$.sourceRef.subPart") { value("b") }
            jsonPath("$.provenance.sourceTitle") { value("Matematika 6 – pracovní sešit") }
            jsonPath("$.provenance.edition") { value("1") }
            jsonPath("$.provenance.page") { value(57) }
            jsonPath("$.parts[*].key") { value(contains("a", "b")) }
            jsonPath("$.parts[1].spec.accepted[0]") { value("24 cm2") }
            jsonPath("$.parts[1].spec.acceptedNormalized[0]") { value("24 cm²") }
            jsonPath("$.parts[1].spec.unit") { value("cm²") }
            jsonPath("$.attemptCount") { value(0) }
            jsonPath("$.missionIds.length()") { value(0) }
            jsonPath("$.images.length()") { value(0) }
        }
        tasks.tasks.size shouldBe 1
    }

    // @trace FR-3
    @ParameterizedTest
    @ValueSource(strings = ["4", "0", "\"abc\"", "\"2,0\"", "2.0", "99999999999", "null"])
    fun `an invalid difficulty is refused and nothing is stored`(value: String) {
        create("difficulty" to value).isFieldProblem(400, "difficulty", "whole number from 1 to 3")
        tasks.tasks.size shouldBe 0
    }

    // @trace FR-3
    @ParameterizedTest
    @ValueSource(strings = ["121", "0", "\"abc\"", "\"2,0\"", "99999999999", "null", "57.0"])
    fun `a missing, out-of-range or malformed page is refused with the range`(value: String) {
        create("sourceRef" to """{"sourceId":"$sourceId","page":$value,"taskNo":"4"}""").isFieldProblem(400, "sourceRef.page", "1–120")
        tasks.tasks.size shouldBe 0
    }

    // @trace FR-3
    @Test
    fun `prompt limit, grade, topic, source and modality are checked`() {
        create("prompt" to quote("a".repeat(4001))).isFieldProblem(400, "prompt", "4000")
        create("prompt" to quote("ž".repeat(4000))).andExpect { status { isCreated() } }
        create("grade" to "7").isFieldProblem(400, "grade", "6")
        create("grade" to "6.5").isFieldProblem(400, "grade", "6")
        create("topicId" to quote(UUID.randomUUID().toString())).isFieldProblem(400, "topicId")
        create("topicId" to quote(Topics.GRADE7.toString())).isFieldProblem(400, "topicId", "grade 6")
        create("sourceRef" to """{"sourceId":"${UUID.randomUUID()}","page":1,"taskNo":"1"}""").isFieldProblem(400, "sourceRef.sourceId")
        create("modality" to "ABSENT").isFieldProblem(400, "modality")
        create("sourceRef" to """{"sourceId":"$sourceId","page":1,"taskNo":"${"1".repeat(21)}"}""")
            .isFieldProblem(400, "sourceRef.taskNo", "20")
        tasks.tasks.size shouldBe 1
    }

    // @trace FR-5
    // @trace FR-6
    @Test
    fun `a text task needs a part, a graphic task is stored without parts and found by modality`() {
        create(parts()).isFieldProblem(400, "parts", "at least one answer part")
        val graphic = createId("modality" to "\"GRAPHIC\"", parts())
        mvc.adminGet(admin, "/api/admin/tasks", "modality" to "GRAPHIC").andExpect {
            jsonPath("$[*].taskId") { value(contains(graphic)) }
        }
        mvc
            .adminJson("PUT", admin, "/api/admin/tasks/$graphic", taskJson(sourceId, parts()))
            .isFieldProblem(400, "parts")
        tasks.tasks
            .getValue(UUID.fromString(graphic))
            .content.modality.name shouldBe "GRAPHIC"
    }

    // @trace FR-6
    @Test
    fun `part errors name the exact field path`() {
        val a = part("a", """{"type":"INTEGER","accepted":["12"]}""")
        create(parts(a, part("b", """{"type":"DECIMAL","accepted":[]}"""))).isFieldProblem(400, "parts[1].spec.accepted")
        create(parts(a, part("b", """{"accepted":["1,5"]}"""))).isFieldProblem(400, "parts[1].spec.type")
        create(parts(a, part("b", """{"type":"INTEGER","accepted":["1"]}""", label = "a"))).isFieldProblem(400, "parts[1].label")
        create(parts(a, part("b", """{"type":"INTEGER","accepted":["1"]}""", label = " "))).isFieldProblem(400, "parts[1].label")
        create(
            parts(part("a", """{"type":"DECIMAL","accepted":["1.250,75"]}""")),
        ).isFieldProblem(400, "parts[0].spec.accepted[0]", "1 250,75")
        create(
            parts(part("a", """{"type":"CHOICE_SINGLE","accepted":["A"],"options":[{"key":"A","text":"4"},{"key":"B","text":" 4 "}]}""")),
        ).isFieldProblem(400, "parts[0].spec.options[1].text")
        create(
            parts(part("a", """{"type":"DECIMAL","accepted":["1"],"tolerance":"-0,1"}""")),
        ).isFieldProblem(400, "parts[0].spec.tolerance")
        create(parts(*Array(11) { part("p$it", """{"type":"INTEGER","accepted":["1"]}""") })).isFieldProblem(400, "parts", "10")
        tasks.tasks.size shouldBe 0
    }

    // @trace FR-7
    // @trace FR-8
    @Test
    fun `hints and translations are validated`() {
        create("hints" to """["  "]""").isFieldProblem(400, "hints[0]")
        create("hints" to (1..11).joinToString(",", "[", "]") { "\"h$it\"" }).isFieldProblem(400, "hints", "10")
        create("hints" to """["${"a".repeat(1001)}"]""").isFieldProblem(400, "hints[0]", "1000")
        create("translations" to """{"de":{"prompt":"Berechne."}}""").isFieldProblem(400, "translations.de", "uk")
        create("translations" to """{"uk":{"prompt":"${"a".repeat(4001)}"}}""").isFieldProblem(400, "translations.uk.prompt", "4000")
        create("hints" to """["první","druhá"]""", "translations" to """{"uk":{"prompt":"Обчисли.","hints":[null,"друга"]}}""").andExpect {
            status { isCreated() }
            jsonPath("$.hints") { value(contains("první", "druhá")) }
            jsonPath("$.translations.uk.prompt") { value("Обчисли.") }
            jsonPath("$.translations.uk.hints[1]") { value("друга") }
        }
    }

    // @trace FR-6
    @Test
    fun `answer specs come back with their normalized interpretation`() {
        create(
            parts(
                part("a", """{"type":"DECIMAL","accepted":["1 250,75"]}"""),
                part("b", """{"type":"FRACTION","accepted":["1 1/2"]}"""),
                part(
                    "c",
                    """{"type":"CHOICE_SINGLE","accepted":["B"],"options":[{"key":"A","text":"3"},{"key":"B","text":"4"},{"key":"C","text":"5"}]}""",
                ),
            ),
        ).andExpect {
            status { isCreated() }
            jsonPath("$.parts[0].spec.accepted[0]") { value("1 250,75") }
            jsonPath("$.parts[0].spec.acceptedNormalized[0]") { value("1250.75") }
            jsonPath("$.parts[1].spec.acceptedNormalized[0]") { value("3/2") }
            jsonPath("$.parts[2].spec.options[1].text") { value("4") }
            jsonPath("$.parts[2].spec.accepted[0]") { value("B") }
        }
    }

    // @trace FR-3
    @Test
    fun `an edit replaces every field, an invalid edit changes nothing`() {
        val id = createId()
        val path = "/api/admin/tasks/$id"
        mvc.adminJson("PUT", admin, path, taskJson(sourceId, "difficulty" to "0")).isFieldProblem(400, "difficulty")
        tasks.tasks
            .getValue(UUID.fromString(id))
            .content.difficulty shouldBe 2
        mvc
            .adminJson(
                "PUT",
                admin,
                path,
                taskJson(sourceId, "prompt" to "\"Nové zadání\"", "difficulty" to "3", "hints" to """["nová"]"""),
            ).andExpect {
                status { isOk() }
                jsonPath("$.prompt") { value("Nové zadání") }
                jsonPath("$.difficulty") { value(3) }
                jsonPath("$.hints[0]") { value("nová") }
                jsonPath("$.status") { value("DRAFT") }
            }
        mvc.adminGet(admin, path).andExpect { jsonPath("$.prompt") { value("Nové zadání") } }
        mvc.adminGet(admin, "/api/admin/tasks/${UUID.randomUUID()}").andExpect { status { isNotFound() } }
        mvc.adminGet(admin, "/api/admin/tasks/12").isFieldProblem(400, "taskId")
        mvc.adminJson("PUT", admin, "/api/admin/tasks/${UUID.randomUUID()}", taskJson(sourceId)).andExpect { status { isNotFound() } }
        mvc.adminJson("POST", admin, "/api/admin/tasks", "{").andExpect { status { isBadRequest() } }
    }

    // @trace FR-3
    @Test
    fun `a task is deleted with its images`() {
        val id = createId()
        mvc.addImage(admin, id, Files.png()).andExpect { status { isCreated() } }
        storage.files.size shouldBe 2
        mvc.adminDelete(admin, "/api/admin/tasks/$id").andExpect { status { isNoContent() } }
        tasks.tasks.size shouldBe 0
        storage.files.size shouldBe 1
    }

    // @trace FR-11
    @Test
    fun `filters combine and the search ignores case and diacritics`() {
        val match = createId("prompt" to "\"Urči obvod obdélníku.\"")
        createId("prompt" to "\"Sečti čísla.\"")
        val otherPage =
            createId(
                "prompt" to "\"Obvod obdélníku jinde.\"",
                "sourceRef" to """{"sourceId":"$sourceId","page":58,"taskNo":"1"}""",
            )
        val published = createId("prompt" to "\"Obdélník publikovaný.\"")
        tasks.setStatus(UUID.fromString(published), PublishStatus.PUBLISHED)
        mvc.adminGet(admin, "/api/admin/tasks", "sourceId" to sourceId, "page" to "57", "status" to "DRAFT").andExpect {
            jsonPath("$[*].taskId") { value(containsInAnyOrder(match, tasks.tasks.keys.map { it.toString() }[1])) }
        }
        for (q in listOf("obdelnik", "OBDÉLNÍK", " obdélník ")) {
            mvc.adminGet(admin, "/api/admin/tasks", "q" to q, "status" to "DRAFT").andExpect {
                jsonPath("$[*].taskId") { value(containsInAnyOrder(match, otherPage)) }
            }
        }
        mvc.adminGet(admin, "/api/admin/tasks", "q" to "   ", "status" to "DRAFT").andExpect { jsonPath("$.length()") { value(3) } }
        mvc.adminGet(admin, "/api/admin/tasks", "modality" to "GRAPHIC").andExpect { jsonPath("$.length()") { value(0) } }
        mvc.adminGet(admin, "/api/admin/tasks", "grade" to "6", "topicId" to Topics.DECIMALS.toString()).andExpect {
            jsonPath("$.length()") { value(4) }
            jsonPath("$[0].provenance.sourceTitle") { value("Matematika 6 – pracovní sešit") }
            jsonPath("$[0].promptExcerpt") { isNotEmpty() }
        }
        mvc.adminGet(admin, "/api/admin/tasks", "q" to "a".repeat(201)).isFieldProblem(400, "q", "200")
        mvc.adminGet(admin, "/api/admin/tasks", "page" to "0").isFieldProblem(400, "page")
        mvc.adminGet(admin, "/api/admin/tasks", "status" to "draft").isFieldProblem(400, "status")
        mvc.adminGet(admin, "/api/admin/tasks", "topicId" to "x").isFieldProblem(400, "topicId")
    }

    // @trace FR-4
    @Test
    fun `images are attached, reordered, served and removed`() {
        val id = createId()
        val first: String =
            JsonPath.read(
                mvc
                    .addImage(admin, id, Files.png())
                    .andReturn()
                    .response.contentAsString,
                "$.imageId",
            )
        val second: String =
            JsonPath.read(
                mvc
                    .addImage(admin, id, Files.jpg(), "b.jpg")
                    .andExpect {
                        status { isCreated() }
                        jsonPath("$.order") { value(2) }
                        jsonPath("$.url") { value(containsString("/api/admin/tasks/$id/images/")) }
                    }.andReturn()
                    .response.contentAsString,
                "$.imageId",
            )
        mvc.adminJson("PUT", admin, "/api/admin/tasks/$id/images", """{"imageIds":["$second","$first"]}""").andExpect {
            status { isOk() }
            jsonPath("$[*].imageId") { value(contains(second, first)) }
            jsonPath("$[*].order") { value(contains(1, 2)) }
        }
        mvc.adminJson("PUT", admin, "/api/admin/tasks/$id/images", """{"imageIds":["$second"]}""").isFieldProblem(400, "imageIds")
        mvc
            .adminJson(
                "PUT",
                admin,
                "/api/admin/tasks/$id/images",
                """{"imageIds":["$second","$second"]}""",
            ).isFieldProblem(400, "imageIds[1]")
        mvc.adminGet(admin, "/api/admin/tasks/$id/preview").andExpect { jsonPath("$.images[*].imageId") { value(contains(second, first)) } }
        val bytes =
            mvc
                .adminGet(admin, "/api/admin/tasks/$id/images/$second")
                .andExpect {
                    status { isOk() }
                    content { contentType("image/jpeg") }
                }.andReturn()
                .response.contentAsByteArray
        bytes.toList() shouldBe Files.jpg().toList()
        mvc.adminDelete(admin, "/api/admin/tasks/$id/images/$second").andExpect { status { isNoContent() } }
        mvc.adminGet(admin, "/api/admin/tasks/$id").andExpect {
            jsonPath("$.images[*].imageId") { value(contains(first)) }
            jsonPath("$.images[0].order") { value(1) }
        }
        mvc.adminGet(admin, "/api/admin/tasks/$id/images/$second").andExpect { status { isNotFound() } }
    }

    // @trace FR-4
    // @trace NFR-17
    @Test
    fun `rejected images leave the task unchanged`() {
        val id = createId()
        mvc.addImage(admin, id, Files.gif(), "a.png").andExpect {
            status { isUnsupportedMediaType() }
            jsonPath("$.detail") { value("Only PNG or JPG files up to 5 MB are allowed.") }
        }
        mvc.addImage(admin, id, Files.jpgOfSize(6 * 1024 * 1024), "big.jpg").andExpect {
            status { isEqualTo(413) }
            jsonPath("$.detail") { value("Only PNG or JPG files up to 5 MB are allowed.") }
        }
        mvc.addImage(admin, id, ByteArray(0)).isFieldProblem(400, "file")
        repeat(10) { mvc.addImage(admin, id, Files.png()).andExpect { status { isCreated() } } }
        mvc.addImage(admin, id, Files.png()).isFieldProblem(400, "file", "10")
        tasks.tasks
            .getValue(UUID.fromString(id))
            .images.size shouldBe 10
        mvc.addImage(admin, UUID.randomUUID(), Files.png()).andExpect { status { isNotFound() } }
    }

    // @trace FR-10
    @Test
    fun `the preview is the child view without source reference, every hint opened`() {
        val id =
            createId(
                "hints" to """["první","druhá"]""",
                "translations" to """{"uk":{"prompt":"Обчисли.","partLabels":{"a":"а"},"hints":["перша"]}}""",
            )
        val body =
            mvc
                .adminGet(admin, "/api/admin/tasks/$id/preview", "locale" to "uk")
                .andExpect {
                    status { isOk() }
                    jsonPath("$.taskId") { value(id) }
                    jsonPath("$.missionId") { value(null) }
                    jsonPath("$.position") { value(1) }
                    jsonPath("$.prompt.cs") { value("Vypočítej obvod obdélníku.") }
                    jsonPath("$.prompt.secondary.locale") { value("uk") }
                    jsonPath("$.prompt.secondary.text") { value("Обчисли.") }
                    jsonPath("$.translationAvailable") { value(true) }
                    jsonPath("$.parts[0].label.secondary.text") { value("а") }
                    jsonPath("$.hints.total") { value(2) }
                    jsonPath("$.hints.opened[0].text.secondary.text") { value("перша") }
                    jsonPath("$.hints.opened[1].text.cs") { value("druhá") }
                    jsonPath("$.hints.opened[1].text.secondary") { value(null) }
                    jsonPath("$.hints.opened[1].remaining") { value(0) }
                    jsonPath("$.maxAttempts") { value(2) }
                    jsonPath("$.attemptsUsed") { value(0) }
                    jsonPath("$.state") { value("UNRESOLVED") }
                }.andReturn()
                .response.contentAsString
        body shouldNotContain "Matematika 6"
        body shouldNotContain "taskNo"
        body shouldNotContain "\"page\""
        mvc.adminGet(admin, "/api/admin/tasks/$id/preview").andExpect { jsonPath("$.prompt.secondary") { value(null) } }
        mvc.adminGet(admin, "/api/admin/tasks/$id/preview", "locale" to "de").isFieldProblem(400, "locale", "uk")
        val graphic = createId("modality" to "\"GRAPHIC\"", parts())
        mvc.adminGet(admin, "/api/admin/tasks/$graphic/preview").andExpect {
            jsonPath("$.parts.length()") { value(0) }
            jsonPath("$.images.length()") { value(0) }
        }
    }

    // @trace FR-10
    @Test
    fun `the answer tester shows verdicts and records nothing`() {
        val id =
            createId(
                parts(
                    part("a", """{"type":"DECIMAL","accepted":["0.5"]}"""),
                    part("b", """{"type":"INTEGER","accepted":["12500"]}"""),
                    part("c", """{"type":"DECIMAL","accepted":["5,2"]}"""),
                    part(
                        "d",
                        """{"type":"CHOICE_MULTI","accepted":["A","C"],"options":[{"key":"A","text":"1"},{"key":"B","text":"2"},{"key":"C","text":"3"}]}""",
                    ),
                ),
            )
        val before = tasks.tasks.toMap()
        val tip = AnswerChecker.check(AnswerSpec(AnswerType.DECIMAL, listOf("5,2")), "5,,2").formatTip
        mvc
            .adminJson("POST", admin, "/api/admin/tasks/$id/check", """{"answers":{"a":"0,50","b":"12 400","c":"5,,2","d":["C","A"]}}""")
            .andExpect {
                status { isOk() }
                jsonPath("$[0].key") { value("a") }
                jsonPath("$[0].verdict") { value("CORRECT") }
                jsonPath("$[0].normalized") { value("0.5") }
                jsonPath("$[0].formatTip") { value(null) }
                jsonPath("$[0].locked") { value(false) }
                jsonPath("$[1].verdict") { value("WRONG") }
                jsonPath("$[1].normalized") { value("12400") }
                jsonPath("$[2].verdict") { value("UNPARSEABLE") }
                jsonPath("$[2].normalized") { value(null) }
                jsonPath("$[2].formatTip") { value(tip) }
                jsonPath("$[3].verdict") { value("CORRECT") }
            }
        tasks.tasks shouldBe before
        val check = "/api/admin/tasks/$id/check"
        mvc.adminJson("POST", admin, check, """{"answers":{"z":"1"}}""").isFieldProblem(400, "answers.z")
        mvc.adminJson("POST", admin, check, """{"answers":{"a":["1"]}}""").isFieldProblem(400, "answers.a")
        mvc.adminJson("POST", admin, check, """{"answers":{"d":"A"}}""").isFieldProblem(400, "answers.d")
        mvc
            .adminJson(
                "POST",
                admin,
                check,
                """{"answers":{"d":[${(1..21).joinToString(",") { "\"A\"" }}]}}""",
            ).isFieldProblem(400, "answers.d")
        mvc.adminJson("POST", admin, check, """{"answers":{"a":"${"1".repeat(17 * 1024)}"}}""").andExpect { status { isEqualTo(413) } }
        mvc
            .adminJson(
                "POST",
                admin,
                "/api/admin/tasks/${UUID.randomUUID()}/check",
                """{"answers":{}}""",
            ).andExpect { status { isNotFound() } }
    }

    // @trace FR-3
    @Test
    fun `status changes and bulk publish`() {
        val drafts = List(3) { createId() }
        val published = createId()
        mvc.adminJson("PUT", admin, "/api/admin/tasks/$published/status", """{"status":"PUBLISHED"}""").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("PUBLISHED") }
        }
        mvc.adminJson("PUT", admin, "/api/admin/tasks/$published/status", """{"status":"published"}""").isFieldProblem(400, "status")
        val all = drafts + published
        mvc.adminJson("POST", admin, "/api/admin/tasks/publish", all.joinToString(",", """{"taskIds":[""", "]}") { "\"$it\"" }).andExpect {
            status { isOk() }
            jsonPath("$[*].taskId") { value(contains(*all.toTypedArray())) }
            jsonPath("$[*].published") { value(contains(true, true, true, true)) }
            jsonPath("$[0].reason") { value(null) }
        }
        tasks.tasks.values.all { it.status == PublishStatus.PUBLISHED } shouldBe true
        mvc.adminJson("PUT", admin, "/api/admin/tasks/$published/status", """{"status":"DRAFT"}""").andExpect {
            jsonPath("$.status") { value("DRAFT") }
        }
    }

    // @trace FR-3
    @Test
    fun `an unknown id does not block the rest, the selection is limited`() {
        val a = createId()
        val b = createId()
        val unknown = UUID.randomUUID().toString()
        mvc.adminJson("POST", admin, "/api/admin/tasks/publish", """{"taskIds":["$a","$unknown","$b"]}""").andExpect {
            status { isOk() }
            jsonPath("$[1].taskId") { value(unknown) }
            jsonPath("$[1].published") { value(false) }
            jsonPath("$[1].reason") { value("not found") }
            jsonPath("$[2].published") { value(true) }
        }
        val c = createId()
        val publish = "/api/admin/tasks/publish"
        mvc.adminJson("POST", admin, publish, """{"taskIds":[]}""").isFieldProblem(400, "taskIds")
        mvc
            .adminJson("POST", admin, publish, (1..101).joinToString(",", """{"taskIds":[""", "]}") { "\"${UUID.randomUUID()}\"" })
            .isFieldProblem(400, "taskIds", "100")
        mvc.adminJson("POST", admin, publish, """{"taskIds":["$c","$c"]}""").isFieldProblem(400, "taskIds[1]")
        mvc.adminJson("POST", admin, publish, """{"taskIds":["nope"]}""").isFieldProblem(400, "taskIds[0]")
        tasks.tasks.getValue(UUID.fromString(c)).status shouldBe PublishStatus.DRAFT
    }

    // @trace FR-3
    @Test
    fun `task operations need the session and the CSRF token`() {
        mvc.get("/api/admin/tasks").andExpect { status { isUnauthorized() } }
        mvc.adminJson("POST", admin, "/api/admin/tasks", taskJson(sourceId), csrf = false).andExpect { status { isForbidden() } }
        mvc.post("/api/admin/tasks/publish").andExpect { status { isUnauthorized() } }
        tasks.tasks.size shouldBe 0
    }

    // @trace FR-3
    // @trace FR-6
    @Test
    fun `values of the wrong JSON type are refused on their path, never a 500`() {
        val cases =
            listOf(
                taskJson(sourceId, "sourceRef" to "[]") to "sourceRef",
                taskJson(sourceId, "parts" to "{}") to "parts",
                taskJson(sourceId, "parts" to "[1]") to "parts[0]",
                taskJson(sourceId, "parts" to """[{"key":"a","label":"a","spec":"INTEGER"}]""") to "parts[0].spec",
                taskJson(sourceId, "parts" to """[{"key":"a","label":"a","spec":{"type":"INTEGER","accepted":[1]}}]""") to
                    "parts[0].spec.accepted[0]",
                taskJson(
                    sourceId,
                    "parts" to """[{"key":"a","label":"a","spec":{"type":"TEXT","accepted":["x"],"diacriticsStrict":"yes"}}]""",
                ) to
                    "parts[0].spec.diacriticsStrict",
                taskJson(sourceId, "parts" to """[{"key":"a","label":"a","spec":{"type":"integer","accepted":["1"]}}]""") to
                    "parts[0].spec.type",
                taskJson(sourceId, "hints" to """[3]""") to "hints[0]",
                taskJson(sourceId, "translations" to "[]") to "translations",
                taskJson(sourceId, "translations" to """{"uk":{"partLabels":[]}}""") to "translations.uk.partLabels",
                taskJson(sourceId, "topicId" to "\"6a000000-0000-4000-8000-00000000000\"") to "topicId",
                taskJson(sourceId, "modality" to "\"text\"") to "modality",
                taskJson(sourceId, "prompt" to "5") to "prompt",
            )
        for ((body, field) in cases) mvc.adminJson("POST", admin, "/api/admin/tasks", body).isFieldProblem(400, field)
        for (body in listOf("[]", "{} {}", "\"x\"", "")) {
            mvc.adminJson("POST", admin, "/api/admin/tasks", body).andExpect {
                status { isBadRequest() }
                jsonPath("$.title") { value("Invalid input") }
            }
        }
        mvc.adminJson("POST", admin, "/api/admin/tasks", " ".repeat(1024 * 1024 + 1)).andExpect { status { isEqualTo(413) } }
        tasks.tasks.size shouldBe 0
    }

    // @trace FR-10
    // @trace FR-11
    @Test
    fun `query and path parameters of the wrong form name the parameter`() {
        val id = createId()
        mvc.adminGet(admin, "/api/admin/tasks/$id/preview", "locale" to "UKR").isFieldProblem(400, "locale", "uk")
        mvc.adminGet(admin, "/api/admin/tasks", "modality" to "text").isFieldProblem(400, "modality")
        mvc.adminGet(admin, "/api/admin/tasks", "grade" to "x").isFieldProblem(400, "grade")
        mvc.adminGet(admin, "/api/admin/tasks/$id/images/nope").isFieldProblem(400, "imageId")
        mvc.adminJson("PUT", admin, "/api/admin/tasks/$id/images", """{"imageIds":"x"}""").isFieldProblem(400, "imageIds")
        mvc.adminJson("PUT", admin, "/api/admin/tasks/$id/images", """{"imageIds":["x"]}""").isFieldProblem(400, "imageIds[0]")
        mvc.adminJson("POST", admin, "/api/admin/tasks/$id/check", """{"answers":[]}""").isFieldProblem(400, "answers")
        mvc.adminJson("POST", admin, "/api/admin/tasks/$id/check", """{"answers":{"a":5}}""").isFieldProblem(400, "answers.a")
        mvc.adminJson("POST", admin, "/api/admin/tasks/$id/check", """{"answers":{"a":[5]}}""").isFieldProblem(400, "answers.a")
        mvc
            .adminJson(
                "POST",
                admin,
                "/api/admin/tasks/$id/check",
                """{"answers":{"a":["${"x".repeat(101)}"]}}""",
            ).isFieldProblem(400, "answers.a")
        mvc.adminJson("POST", admin, "/api/admin/tasks/publish", """{}""").isFieldProblem(400, "taskIds")
    }

    // @trace FR-3
    @Test
    fun `a database rule broken by a concurrent edit is a 409, never a 500`() {
        tasks.failNextInsert = true
        create().andExpect {
            status { isConflict() }
            content { contentTypeCompatibleWith("application/problem+json") }
        }
    }

    // @trace FR-10
    @Test
    fun `the tester grades a part without an answer as unreadable`() {
        val choice = part("b", """{"type":"CHOICE_SINGLE","accepted":["A"],"options":[{"key":"A","text":"1"},{"key":"B","text":"2"}]}""")
        val id = createId(parts(part("a", """{"type":"INTEGER","accepted":["1"]}"""), choice))
        mvc.adminJson("POST", admin, "/api/admin/tasks/$id/check", """{"answers":{}}""").andExpect {
            status { isOk() }
            jsonPath("$[*].verdict") { value(contains("UNPARSEABLE", "UNPARSEABLE")) }
        }
        mvc.adminJson("POST", admin, "/api/admin/tasks/$id/check", """{"answers":{"b":["B"]}}""").andExpect {
            jsonPath("$[1].verdict") { value("WRONG") }
        }
    }

    // @trace FR-10
    // @trace FR-11
    @Test
    fun `long prompts are excerpted in the list, the preview tells whether a translation exists`() {
        val id = createId("prompt" to quote("ž".repeat(300)), "translations" to """{"uk":{"prompt":"Обчисли."}}""")
        mvc.adminGet(admin, "/api/admin/tasks").andExpect { jsonPath("$[0].promptExcerpt") { value("ž".repeat(120) + "…") } }
        mvc.adminGet(admin, "/api/admin/tasks/$id/preview").andExpect {
            jsonPath("$.translationAvailable") { value(true) }
            jsonPath("$.prompt.secondary") { value(null) }
        }
        val plain = createId()
        mvc.adminGet(admin, "/api/admin/tasks/$plain/preview", "locale" to "uk").andExpect {
            jsonPath("$.translationAvailable") { value(false) }
            jsonPath("$.prompt.secondary") { value(null) }
        }
        mvc.adminJson("PUT", admin, "/api/admin/tasks/$plain/status", """{"status":"DRAFT"}""").andExpect {
            jsonPath("$.status") { value("DRAFT") }
        }
        mvc.adminDelete(admin, "/api/admin/tasks/$plain/images/${UUID.randomUUID()}").andExpect { status { isNotFound() } }
    }

    // @trace FR-3
    // @trace FR-8
    @Test
    fun `reference data lists configured grades, subjects, locales and seeded topics`() {
        mvc.adminGet(admin, "/api/admin/reference").andExpect {
            status { isOk() }
            jsonPath("$.grades") { value(contains(6)) }
            jsonPath("$.subjects") { value(contains("math")) }
            jsonPath("$.taskLocales") { value(contains("uk")) }
            jsonPath("$.topics[?(@.name == 'Desetinná čísla')].grade") { value(contains(6)) }
        }
    }

    @Nested
    @WebMvcTest(properties = ["doucko.content.grades=6,7"])
    @Import(
        SecurityConfiguration::class,
        AdminSecurityConfiguration::class,
        AdminWebTestBeans::class,
        ProfileWebTestBeans::class,
        ContentWebTestBeans::class,
    )
    inner class WithGradeSeven {
        @Autowired
        lateinit var mvc: MockMvc

        // @trace FR-3
        @Test
        fun `a task grade must match its source and its topic`() {
            val admin = mvc.signedIn()
            val source: String =
                JsonPath.read(
                    mvc
                        .uploadSource(admin, Files.pdf(5), *SOURCE_FIELDS)
                        .andReturn()
                        .response.contentAsString,
                    "$.sourceId",
                )
            mvc
                .adminJson(
                    "POST",
                    admin,
                    "/api/admin/tasks",
                    taskJson(
                        source,
                        "grade" to "7",
                        "sourceRef" to """{"sourceId":"$source","page":1,"taskNo":"1"}""",
                    ),
                ).isFieldProblem(400, "grade", "(6)")
            mvc
                .adminJson(
                    "POST",
                    admin,
                    "/api/admin/tasks",
                    taskJson(
                        source,
                        "topicId" to quote(Topics.GRADE7.toString()),
                        "sourceRef" to """{"sourceId":"$source","page":1,"taskNo":"1"}""",
                    ),
                ).isFieldProblem(400, "topicId", "grade 6")
        }
    }
}
