package cz.doucko.practice

import com.jayway.jsonpath.DocumentContext
import com.jayway.jsonpath.JsonPath
import cz.doucko.TestcontainersConfiguration
import cz.doucko.admin.Admin
import cz.doucko.admin.isProblem
import cz.doucko.admin.signedIn
import cz.doucko.content.Files
import cz.doucko.content.SOURCE_FIELDS
import cz.doucko.content.Topics
import cz.doucko.content.addImage
import cz.doucko.content.adminDelete
import cz.doucko.content.adminGet
import cz.doucko.content.adminJson
import cz.doucko.content.taskJson
import cz.doucko.content.uploadSource
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.util.UUID

/**
 * The child practice flow against Postgres 16 over HTTP (store spec practice-flow, the attempt log of
 * progress-stats, answer-checking FR-22, the FR-12 child-facing scenarios carried over from slice 6, the
 * admin-access NFR-1 child-write scenario and the real `ContentUsage`). Content is authored through the
 * admin API; every child request carries no credentials.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class PracticeIT {
    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var jdbc: JdbcTemplate

    lateinit var admin: Admin
    lateinit var sourceId: String
    private var position = 0

    @BeforeEach
    fun setUp() {
        clean()
        admin = mvc.signedIn()
        sourceId =
            JsonPath.read(
                mvc
                    .uploadSource(admin, Files.pdf(120), *SOURCE_FIELDS)
                    .andReturn()
                    .response.contentAsString,
                "$.sourceId",
            )
        settings("""{"secondaryTaskLocale":null}""")
    }

    @AfterEach
    fun clean() {
        for (table in listOf("problem_report", "opened_hint", "attempt", "practice_run", "mission", "task_image", "task", "source")) {
            jdbc.update("DELETE FROM $table")
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun created(
        result: ResultActionsDsl,
        path: String,
    ): String =
        JsonPath.read(
            result
                .andExpect { status { isCreated() } }
                .andReturn()
                .response.contentAsString,
            path,
        )

    private fun task(
        vararg overrides: Pair<String, String>,
        topic: UUID = Topics.DECIMALS,
        publish: Boolean = true,
    ): String {
        val id =
            created(mvc.adminJson("POST", admin, "/api/admin/tasks", taskJson(sourceId, "topicId" to "\"$topic\"", *overrides)), "$.taskId")
        if (publish) {
            mvc
                .adminJson(
                    "PUT",
                    admin,
                    "/api/admin/tasks/$id/status",
                    """{"status":"PUBLISHED"}""",
                ).andExpect { status { isOk() } }
        }
        return id
    }

    private fun mission(
        ids: List<String>,
        topic: UUID = Topics.DECIMALS,
        publish: Boolean = true,
        at: Int = ++position,
    ): String {
        val body = """{"topicId":"$topic","position":$at,"taskIds":${ids.joinToString(",", "[", "]") { "\"$it\"" }}}"""
        val id = created(mvc.adminJson("POST", admin, "/api/admin/missions", body), "$.missionId")
        if (publish) {
            mvc
                .adminJson(
                    "PUT",
                    admin,
                    "/api/admin/missions/$id/status",
                    """{"status":"PUBLISHED"}""",
                ).andExpect { status { isOk() } }
        }
        return id
    }

    private fun unpublish(missionId: String) =
        mvc.adminJson("PUT", admin, "/api/admin/missions/$missionId/status", """{"status":"DRAFT"}""").andExpect { status { isOk() } }

    private fun read(result: ResultActionsDsl): DocumentContext = JsonPath.parse(result.andReturn().response.contentAsString)

    private fun hq() = read(mvc.get("/api/child/hq").andExpect { status { isOk() } })

    private fun childTask(
        m: String,
        t: String,
    ) = mvc.get("/api/child/missions/$m/tasks/$t")

    private fun attempt(
        m: String,
        t: String,
        answers: String,
        extra: String = "",
    ) = mvc.post("/api/child/missions/$m/tasks/$t/attempts") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"answers":$answers$extra}"""
    }

    private fun graded(
        m: String,
        t: String,
        answers: String,
    ) = read(attempt(m, t, answers).andExpect { status { isOk() } })

    private fun hint(
        m: String,
        t: String,
    ) = mvc.post("/api/child/missions/$m/tasks/$t/hints")

    private fun report(
        m: String,
        t: String,
        body: String,
    ) = mvc.post("/api/child/missions/$m/tasks/$t/reports") {
        contentType = MediaType.APPLICATION_JSON
        content = body
    }

    private fun settings(body: String) =
        mvc
            .patch("/api/child/profile/settings") {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect { status { isOk() } }

    private fun count(table: String): Int = jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java)!!

    private fun nothingStored() {
        count("attempt") shouldBe 0
        count("opened_hint") shouldBe 0
        count("problem_report") shouldBe 0
    }

    /** Resolves a single-part `12` task correctly on attempt 1. */
    private fun solve(
        m: String,
        t: String,
    ) = graded(m, t, """{"a":"12"}""")

    private fun reveal(
        m: String,
        t: String,
    ) {
        graded(m, t, """{"a":"1"}""")
        graded(m, t, """{"a":"2"}""")
    }

    private val twoParts =
        "parts" to """[{"key":"a","label":"a","spec":{"type":"INTEGER","accepted":["12"]}},""" +
            """{"key":"b","label":"b","spec":{"type":"DECIMAL","accepted":["0.5"]}}]"""
    private val twoHints = "hints" to """["První nápověda.","Druhá nápověda."]"""

    // ---------------------------------------------------------------- HQ (FR-24, FR-25)

    // @trace FR-24
    @Test
    fun `HQ needs no credentials and shows the single profile with the placeholder rank`() {
        val m = mission(listOf(task()))
        val hq = hq()
        hq.read<String>("$.displayName") shouldBe "Hrdinka"
        hq.read<Int>("$.totalXp") shouldBe 0
        hq.read<Int>("$.rank.index") shouldBe 0
        hq.read<Int>("$.rank.minXp") shouldBe 0
        hq.read<Int>("$.nextRank.minXp") shouldBe 150
        hq.read<Any?>("$.pendingRankUp").shouldBeNull()
        hq.read<Any?>("$.continueTarget").shouldBeNull()
        hq.read<String>("$.topics[0].missions[0].missionId") shouldBe m
    }

    // @trace FR-25
    @Test
    fun `an empty HQ is 200 with no missions`() {
        hq().read<List<Any>>("$.topics") shouldBe emptyList()
    }

    // @trace FR-25
    @Test
    fun `missions are grouped by topic and ordered by position, new, in progress and complete`() {
        val d2 = mission(List(3) { task() }, at = 2)
        val d1 = mission(List(3) { task() }, at = 1)
        val n1 = mission(List(2) { task(topic = Topics.NATURAL) }, topic = Topics.NATURAL, at = 1)
        val hq = hq()
        hq.read<List<String>>("$.topics[*].name") shouldContainExactly listOf("Přirozená čísla", "Desetinná čísla")
        hq.read<List<String>>("$.topics[1].missions[*].missionId") shouldContainExactly listOf(d1, d2)
        hq.read<List<String>>("$.topics[0].missions[*].missionId") shouldContainExactly listOf(n1)
        hq.read<List<String>>("$.topics[1].missions[*].state") shouldContainExactly listOf("NEW", "NEW")
        hq.read<List<Int>>("$.topics[1].missions[*].taskCount") shouldContainExactly listOf(3, 3)
    }

    // @trace FR-25
    @Test
    fun `resolved and revealed tasks count, an attempted unresolved task does not`() {
        val a = List(3) { task() }
        val b = List(3) { task() }
        val c = List(3) { task() }
        val ma = mission(a)
        val mb = mission(b)
        val mc = mission(c)
        solve(ma, a[0])
        solve(ma, a[1])
        reveal(ma, a[2])
        graded(mb, b[0], """{"a":"7"}""")
        val missions = hq().read<List<Map<String, Any>>>("$.topics[0].missions")
        missions.map { it["missionId"] to it["resolvedCount"] } shouldContainExactly listOf(ma to 3, mb to 0, mc to 0)
        missions.map { it["state"] } shouldContainExactly listOf("COMPLETE", "IN_PROGRESS", "NEW")
    }

    // @trace FR-25
    @Test
    fun `a task resolved in one mission counts in every mission and is skipped when continuing`() {
        val shared = task()
        val a = mission(listOf(task(), shared))
        val b = mission(listOf(shared, task(), task()))
        solve(a, shared)
        val missions = hq().read<List<Map<String, Any>>>("$.topics[0].missions")
        missions.map { it["resolvedCount"] } shouldContainExactly listOf(1, 1)
        missions.map { it["state"] } shouldContainExactly listOf("IN_PROGRESS", "IN_PROGRESS")
        val next = read(mvc.get("/api/child/missions/$b/current-task").andExpect { status { isOk() } })
        next.read<Int>("$.position") shouldBe 2
        attempt(b, shared, """{"a":"12"}""").isProblem(409)
        read(childTask(b, shared)).read<String>("$.state") shouldBe "CORRECT_FIRST_TRY"
    }

    // @trace FR-25
    @Test
    fun `every published mission is open and starts at its first task`() {
        mission(List(2) { task() })
        val second = List(2) { task() }
        val m2 = mission(second)
        read(mvc.get("/api/child/missions/$m2/current-task")).read<String>("$.taskId") shouldBe second[0]
    }

    // @trace FR-25
    @Test
    fun `continue targets the most recently practised in-progress mission, and nothing when none is in progress`() {
        val a = List(3) { task() }
        val b = List(3) { task() }
        val ma = mission(a)
        val mb = mission(b)
        val mc = mission(listOf(task()))
        hq().read<Any?>("$.continueTarget").shouldBeNull()
        solve(mc, jdbcTaskOf(mc))
        hq().read<Any?>("$.continueTarget").shouldBeNull()
        solve(ma, a[0])
        solve(mb, b[0])
        graded(mb, b[1], """{"a":"1"}""")
        val target = hq().read<Map<String, String>>("$.continueTarget")
        target shouldBe mapOf("missionId" to mb, "taskId" to b[1])
        solve(ma, a[1])
        hq().read<Map<String, String>>("$.continueTarget") shouldBe mapOf("missionId" to ma, "taskId" to a[2])
    }

    private fun jdbcTaskOf(missionId: String): String =
        jdbc.queryForObject(
            "SELECT task_id::text FROM mission_task WHERE mission_id = ?::uuid AND position = 1",
            String::class.java,
            missionId,
        )!!

    // @trace FR-25
    @Test
    fun `the briefing lists the tasks with their states`() {
        val ids = List(3) { task() }
        val m = mission(ids)
        reveal(m, ids[1])
        val briefing = read(mvc.get("/api/child/missions/$m").andExpect { status { isOk() } })
        briefing.read<String>("$.topicName") shouldBe "Desetinná čísla"
        briefing.read<String>("$.state") shouldBe "IN_PROGRESS"
        briefing.read<Int>("$.xpAvailable") shouldBe 0
        briefing.read<List<String>>("$.tasks[*].state") shouldContainExactly listOf("UNRESOLVED", "REVEALED", "UNRESOLVED")
        briefing.read<List<Int>>("$.tasks[*].position") shouldContainExactly listOf(1, 2, 3)
    }

    // ---------------------------------------------------------------- served content (FR-12, FR-26)

    // @trace FR-12
    @Test
    fun `a draft mission is not served and its tasks cannot be requested`() {
        val ids = List(2) { task() }
        val draft = mission(ids, publish = false)
        val published = mission(listOf(task()))
        hq().read<List<String>>("$.topics[*].missions[*].missionId") shouldContainExactly listOf(published)
        mvc.get("/api/child/missions/$draft").isProblem(404)
        mvc.get("/api/child/missions/$draft/current-task").isProblem(404)
        childTask(draft, ids[0]).isProblem(404)
        attempt(draft, ids[0], """{"a":"12"}""").isProblem(404)
        hint(draft, ids[0]).isProblem(404)
        report(draft, ids[0], """{"reason":"UNCLEAR_PROMPT"}""").isProblem(404)
        nothingStored()
    }

    // @trace FR-12
    @Test
    fun `a published task outside any published mission is not found`() {
        val loose = task()
        val m = mission(listOf(task()))
        childTask(m, loose).isProblem(404)
        attempt(m, loose, """{"a":"12"}""").isProblem(404)
        nothingStored()
    }

    // @trace FR-12
    @Test
    fun `a published graphic task is never served, nor its draft mission`() {
        val graphic = task("modality" to "\"GRAPHIC\"", "hints" to """["Nakresli to."]""", "prompt" to "\"Nakresli trojúhelník.\"")
        val draft = mission(listOf(graphic), publish = false)
        val published = mission(listOf(task()))
        val body =
            childTask(draft, graphic)
                .isProblem(404)
                .andReturn()
                .response.contentAsString
        body shouldNotContain "Nakresli"
        childTask(published, graphic).isProblem(404)
        mvc.get("/api/child/missions/$draft").isProblem(404)
        attempt(draft, graphic, """{"a":"12"}""").isProblem(404)
        hint(draft, graphic).isProblem(404)
        report(draft, graphic, """{"reason":"UNCLEAR_PROMPT"}""").isProblem(404)
        hq().read<List<String>>("$.topics[*].missions[*].missionId") shouldContainExactly listOf(published)
        nothingStored()
    }

    // @trace FR-26
    @Test
    fun `a draft task, an unknown task and a task of another mission are not found and store nothing`() {
        val t = task()
        val a = mission(listOf(t))
        val b = mission(listOf(task()))
        attempt(b, t, """{"a":"12"}""").isProblem(404)
        attempt(a, UUID.randomUUID().toString(), """{"a":"12"}""").isProblem(404)
        mvc.get("/api/child/missions/${UUID.randomUUID()}").isProblem(404)
        nothingStored()
        read(childTask(a, t)).read<Int>("$.attemptsUsed") shouldBe 0
        mvc.get("/api/child/missions/not-a-uuid").isProblem(400).andExpect { jsonPath("$.errors[0].field") { value("missionId") } }
        childTask(a, "x").isProblem(400).andExpect { jsonPath("$.errors[0].field") { value("taskId") } }
    }

    // @trace FR-26
    @Test
    fun `the task screen shows the prompt, images and labelled parts, never the source or the answer`() {
        val t = task(twoParts, twoHints)
        val imageId: String =
            JsonPath.read(
                mvc
                    .addImage(admin, t, Files.png())
                    .andReturn()
                    .response.contentAsString,
                "$.imageId",
            )
        val m = mission(listOf(task(), t))
        val view = read(childTask(m, t).andExpect { status { isOk() } })
        view.read<String>("$.prompt.cs") shouldBe "Vypočítej obvod obdélníku."
        view.read<Int>("$.position") shouldBe 2
        view.read<List<String>>("$.parts[*].label.cs") shouldContainExactly listOf("a", "b")
        view.read<List<String>>("$.parts[*].type") shouldContainExactly listOf("INTEGER", "DECIMAL")
        view.read<String>("$.images[0].url") shouldBe "/api/child/images/$imageId"
        view.read<Int>("$.hints.total") shouldBe 2
        view.read<List<Any>>("$.hints.opened") shouldBe emptyList()
        view.read<Int>("$.maxAttempts") shouldBe 2
        view.read<Any?>("$.lastFeedback").shouldBeNull()
        val text = view.jsonString()
        text shouldNotContain "sourceRef"
        text shouldNotContain "0.5"
        text shouldNotContain "\"12\""
        mvc.get("/api/child/images/$imageId").andExpect {
            status { isOk() }
            content { contentType(MediaType.IMAGE_PNG) }
        }
        val draftTask = task(publish = false)
        val draftImage: String =
            JsonPath.read(
                mvc
                    .addImage(admin, draftTask, Files.png())
                    .andReturn()
                    .response.contentAsString,
                "$.imageId",
            )
        mvc.get("/api/child/images/$draftImage").isProblem(404)
        mvc.get("/api/child/images/${UUID.randomUUID()}").isProblem(404)
    }

    // ---------------------------------------------------------------- attempts (FR-27, FR-22)

    // @trace FR-27
    @Test
    fun `correct on the first attempt resolves the task and a further submission is 409`() {
        val t = task()
        val m = mission(listOf(t, task()))
        val result = graded(m, t, """{"a":"12"}""")
        result.read<String>("$.status") shouldBe "GRADED"
        result.read<Int>("$.attemptNo") shouldBe 1
        result.read<String>("$.taskState") shouldBe "CORRECT_FIRST_TRY"
        result.read<Int>("$.attemptsLeft") shouldBe 0
        result.read<Boolean>("$.missionComplete") shouldBe false
        attempt(m, t, """{"a":"12"}""").isProblem(409)
        count("attempt") shouldBe 1
    }

    // @trace FR-27
    @Test
    fun `a wrong part is marked without its correct value, then the locked part is ignored and the task resolves`() {
        val t = task(twoParts)
        val m = mission(listOf(t))
        val first = attempt(m, t, """{"a":"12","b":"3"}""").andExpect { status { isOk() } }
        first.andReturn().response.contentAsString shouldNotContain "0.5"
        read(first).let {
            it.read<List<String>>("$.parts[*].verdict") shouldContainExactly listOf("CORRECT", "WRONG")
            it.read<List<Boolean>>("$.parts[*].locked") shouldContainExactly listOf(false, false)
            it.read<Int>("$.attemptsLeft") shouldBe 1
            it.read<String>("$.taskState") shouldBe "UNRESOLVED"
            it.read<Any?>("$.feedback.revealedAnswers").shouldBeNull()
        }
        read(childTask(m, t)).let {
            it.read<Int>("$.attemptsUsed") shouldBe 1
            it.read<List<String>>("$.lastFeedback.parts[*].verdict") shouldContainExactly listOf("CORRECT", "WRONG")
        }
        val second = graded(m, t, """{"a":"13","b":"0,5"}""")
        second.read<String>("$.taskState") shouldBe "CORRECT_SECOND_TRY"
        second.read<List<Boolean>>("$.parts[*].locked") shouldContainExactly listOf(true, false)
        second.read<List<String>>("$.parts[*].verdict") shouldContainExactly listOf("CORRECT", "CORRECT")
        second.read<String>("$.parts[0].normalized") shouldBe "12"
        second.read<Boolean>("$.missionComplete") shouldBe true
        second.read<Int>("$.xpAwarded") shouldBe 0
        second.read<Int>("$.bonusXp") shouldBe 0
        second.read<List<Any>>("$.completedMissionIds") shouldBe emptyList()
        second.read<Any?>("$.rankUp").shouldBeNull()
        attempt(m, t, """{"b":"0.5"}""").isProblem(409)
    }

    // @trace FR-27
    @Test
    fun `two wrong attempts reveal every part`() {
        val choice =
            "parts" to """[{"key":"a","label":"a","spec":{"type":"INTEGER","accepted":["12"]}},""" +
                """{"key":"c","label":"c","spec":{"type":"CHOICE_MULTI","accepted":["A","C"],"options":[{"key":"A","text":"jedna"},""" +
                """{"key":"B","text":"dvě"},{"key":"C","text":"tři"}]}}]"""
        val t = task(choice)
        val m = mission(listOf(t))
        graded(m, t, """{"a":"12","c":["A"]}""")
        val second = graded(m, t, """{"c":["B","C"]}""")
        second.read<String>("$.taskState") shouldBe "REVEALED"
        second.read<Int>("$.attemptsLeft") shouldBe 0
        second.read<List<String>>("$.feedback.revealedAnswers[*].key") shouldContainExactly listOf("a", "c")
        second.read<String>("$.feedback.revealedAnswers[0].answer") shouldBe "12"
        second.read<List<String>>("$.feedback.revealedAnswers[1].answer") shouldContainExactly listOf("A", "C")
        read(childTask(m, t)).read<String>("$.lastFeedback.feedback.revealedAnswers[0].answer") shouldBe "12"
        attempt(m, t, """{"a":"12","c":["A","C"]}""").isProblem(409)
    }

    // @trace FR-22
    @Test
    fun `an unreadable part refuses the whole submission without cost, then the next one is attempt 1`() {
        val t = task(twoParts)
        val m = mission(listOf(t))
        val refused = graded(m, t, """{"a":"12","b":"3,,4"}""")
        refused.read<String>("$.status") shouldBe "UNPARSEABLE"
        refused.read<Any?>("$.attemptNo").shouldBeNull()
        refused.read<List<String>>("$.parts[*].key") shouldContainExactly listOf("b")
        refused.read<String>("$.parts[0].verdict") shouldBe "UNPARSEABLE"
        refused.read<String>("$.parts[0].formatTip").shouldNotBeNull()
        refused.read<Int>("$.attemptsLeft") shouldBe 2
        refused.read<Int>("$.xpAwarded") shouldBe 0
        refused.read<Any?>("$.feedback").shouldBeNull()
        count("attempt") shouldBe 0
        graded(m, t, """{"a":"12"}""").read<String>("$.status") shouldBe "UNPARSEABLE"
        graded(m, t, """{"a":"12","b":"9"}""").read<Int>("$.attemptNo") shouldBe 1
        val second = graded(m, t, """{"b":"12a"}""")
        second.read<String>("$.status") shouldBe "UNPARSEABLE"
        second.read<Int>("$.attemptsLeft") shouldBe 1
        second.read<Any?>("$.feedback").shouldBeNull()
        count("attempt") shouldBe 1
    }

    // @trace FR-26
    @Test
    fun `oversized input never costs an attempt, and huge integers are graded by value`() {
        val multi =
            "parts" to
                """[{"key":"a","label":"a","spec":{"type":"CHOICE_MULTI","accepted":["A"],"options":[{"key":"A","text":"x"},{"key":"B","text":"y"}]}}]"""
        val choice = task(multi)
        val t = task()
        val big = task("parts" to """[{"key":"a","label":"a","spec":{"type":"INTEGER","accepted":["999999999999"]}}]""")
        val m = mission(listOf(choice, t, big))
        graded(m, t, """{"a":"${"1".repeat(101)}"}""").read<String>("$.status") shouldBe "UNPARSEABLE"
        attempt(m, choice, """{"a":${List(21) { "\"A\"" }.joinToString(",", "[", "]")}}""")
            .isProblem(400)
            .andExpect { jsonPath("$.errors[0].field") { value("answers.a") } }
        attempt(
            m,
            choice,
            """{"a":["${"A".repeat(101)}"]}""",
        ).isProblem(400).andExpect { jsonPath("$.errors[0].field") { value("answers.a") } }
        attempt(m, t, """{"a":"12","c":"1"}""").isProblem(400).andExpect { jsonPath("$.errors[0].field") { value("answers.c") } }
        attempt(m, t, """{"a":["12"]}""").isProblem(400).andExpect { jsonPath("$.errors[0].field") { value("answers.a") } }
        attempt(m, t, """{"a":12}""").isProblem(400).andExpect { jsonPath("$.errors[0].field") { value("answers.a") } }
        attempt(m, t, "[]").isProblem(400).andExpect { jsonPath("$.errors[0].field") { value("answers") } }
        mvc
            .post("/api/child/missions/$m/tasks/$t/attempts") {
                contentType = MediaType.APPLICATION_JSON
                content = "{"
            }.isProblem(400)
        mvc
            .post("/api/child/missions/$m/tasks/$t/attempts") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"answers":{"a":"12"},"pad":"${"x".repeat(17 * 1024)}"}"""
            }.isProblem(413)
        count("attempt") shouldBe 0
        val huge = graded(m, t, """{"a":"99999999999999999999"}""")
        huge.read<String>("$.parts[0].verdict") shouldBe "WRONG"
        huge.read<Int>("$.attemptsLeft") shouldBe 1
        jdbc.queryForObject("SELECT parts->0->>'raw' FROM attempt", String::class.java) shouldBe "99999999999999999999"
        graded(m, big, """{"a":"999 999 999 999"}""").read<String>("$.parts[0].verdict") shouldBe "CORRECT"
    }

    // @trace NFR-1
    @Test
    fun `child write is limited to its own practice`() {
        val t = task()
        val m = mission(listOf(t))
        val body =
            """{"answers":{"a":"12"},"prompt":"Změněno","parts":[{"key":"a","label":"a","spec":{"type":"INTEGER","accepted":["99"]}}],""" +
                """"hints":["x"],"status":"DRAFT","answerSpec":{"a":"99"},"xpAwarded":500}"""
        val result =
            read(
                mvc
                    .post("/api/child/missions/$m/tasks/$t/attempts") {
                        contentType = MediaType.APPLICATION_JSON
                        content = body
                    }.andExpect { status { isOk() } },
            )
        result.read<String>("$.status") shouldBe "GRADED"
        result.read<String>("$.parts[0].verdict") shouldBe "CORRECT"
        val stored = read(mvc.adminGet(admin, "/api/admin/tasks/$t").andExpect { status { isOk() } })
        stored.read<String>("$.prompt") shouldBe "Vypočítej obvod obdélníku."
        stored.read<List<String>>("$.parts[0].spec.accepted") shouldContainExactly listOf("12")
        stored.read<List<String>>("$.hints") shouldBe emptyList()
        stored.read<String>("$.status") shouldBe "PUBLISHED"
        mvc
            .put("/api/admin/tasks/$t") {
                contentType = MediaType.APPLICATION_JSON
                content = taskJson(sourceId, "prompt" to "\"Změněno\"")
            }.isProblem(401)
        read(mvc.adminGet(admin, "/api/admin/tasks/$t")).read<String>("$.prompt") shouldBe "Vypočítej obvod obdélníku."
    }

    // ---------------------------------------------------------------- hints (FR-28) and feedback (FR-44)

    // @trace FR-28
    @Test
    fun `hints open one at a time in order, at any point, and survive a reload`() {
        val t = task(twoHints)
        val m = mission(listOf(t))
        read(hint(m, t).andExpect { status { isOk() } }).let {
            it.read<Int>("$.index") shouldBe 1
            it.read<String>("$.text.cs") shouldBe "První nápověda."
            it.read<Int>("$.remaining") shouldBe 1
        }
        read(childTask(m, t)).read<List<String>>("$.hints.opened[*].text.cs") shouldContainExactly listOf("První nápověda.")
        read(hint(m, t)).read<Int>("$.remaining") shouldBe 0
        hint(m, t).isProblem(409)
        count("opened_hint") shouldBe 2
        jdbc.queryForList("SELECT hint_index FROM opened_hint ORDER BY hint_index", Int::class.java) shouldContainExactly listOf(1, 2)
    }

    // @trace FR-28
    @Test
    fun `a hint after the reveal keeps the task revealed, and a task without hints has none to open`() {
        val t = task(twoHints)
        val bare = task()
        val m = mission(listOf(t, bare))
        reveal(m, t)
        read(hint(m, t)).read<Int>("$.index") shouldBe 1
        read(childTask(m, t)).read<String>("$.state") shouldBe "REVEALED"
        hint(m, bare).isProblem(409)
        read(childTask(m, bare)).read<Int>("$.hints.total") shouldBe 0
    }

    // @trace FR-44
    @Test
    fun `feedback points to the next unopened hint and to none once all are open`() {
        val t = task(twoHints)
        val u = task(twoHints)
        val m = mission(listOf(t, u))
        graded(m, t, """{"a":"1"}""").read<Int>("$.feedback.nextHintIndex") shouldBe 1
        hint(m, u)
        hint(m, u)
        graded(m, u, """{"a":"1"}""").read<Any?>("$.feedback.nextHintIndex").shouldBeNull()
        jdbc.queryForObject("SELECT hints_used FROM attempt WHERE task_id = ?::uuid", Int::class.java, u) shouldBe 2
    }

    // ---------------------------------------------------------------- translation (FR-29)

    // @trace FR-29
    @Test
    fun `the Ukrainian text follows the profile toggle, and an untranslated task shows only Czech`() {
        val translated =
            task(
                twoHints,
                "translations" to """{"uk":{"prompt":"Обчисли периметр.","partLabels":{"a":"а"},"hints":["Перша підказка.",null]}}""",
            )
        val plain = task()
        val m = mission(listOf(translated, plain))
        read(childTask(m, translated)).let {
            it.read<Boolean>("$.translationAvailable") shouldBe true
            it.read<Any?>("$.prompt.secondary").shouldBeNull()
        }
        settings("""{"secondaryTaskLocale":"uk"}""")
        read(childTask(m, translated)).let {
            it.read<String>("$.prompt.secondary.text") shouldBe "Обчисли периметр."
            it.read<String>("$.prompt.secondary.locale") shouldBe "uk"
            it.read<String>("$.parts[0].label.secondary.text") shouldBe "а"
        }
        read(hint(m, translated)).read<String>("$.text.secondary.text") shouldBe "Перша підказка."
        read(hint(m, translated)).read<Any?>("$.text.secondary").shouldBeNull()
        read(childTask(m, plain)).let {
            it.read<Boolean>("$.translationAvailable") shouldBe false
            it.read<Any?>("$.prompt.secondary").shouldBeNull()
        }
    }

    // ---------------------------------------------------------------- reports (FR-30)

    // @trace FR-30
    @Test
    fun `a report attaches the latest attempt of the current run and changes nothing else`() {
        val t = task()
        val before = task()
        val revealed = task()
        val m = mission(listOf(t, before, revealed))
        graded(m, t, """{"a":"7"}""")
        report(m, t, """{"reason":"ANSWER_IS_CORRECT"}""").andExpect { status { isCreated() } }
        report(m, before, """{"reason":"UNCLEAR_PROMPT","note":"  "}""").andExpect { status { isCreated() } }
        reveal(m, revealed)
        report(m, revealed, """{"reason":"OTHER","note":"  Nesedí obrázek.  "}""").andExpect { status { isCreated() } }
        read(childTask(m, t)).read<Int>("$.attemptsUsed") shouldBe 1
        read(childTask(m, revealed)).read<String>("$.state") shouldBe "REVEALED"
        val rows =
            jdbc.queryForList(
                """
                SELECT r.task_id::text AS task, r.reason, r.note, r.status, a.attempt_no, a.parts->0->>'raw' AS raw
                FROM problem_report r LEFT JOIN attempt a ON a.id = r.attempt_id
                """.trimIndent(),
            )
        val byTask = rows.associateBy { it["task"] }
        byTask.getValue(t)["raw"] shouldBe "7"
        byTask.getValue(t)["status"] shouldBe "OPEN"
        byTask.getValue(before)["attempt_no"].shouldBeNull()
        byTask.getValue(before)["note"].shouldBeNull()
        byTask.getValue(revealed)["attempt_no"] shouldBe 2
        byTask.getValue(revealed)["note"] shouldBe "Nesedí obrázek."
    }

    // @trace FR-30
    @Test
    fun `an invalid report is 400 and stores nothing`() {
        val t = task()
        val m = mission(listOf(t))
        report(m, t, """{"reason":"OTHER"}""").isProblem(400).andExpect { jsonPath("$.errors[0].field") { value("note") } }
        report(m, t, """{"reason":"OTHER","note":"   "}""").isProblem(400)
        report(m, t, """{"note":"x"}""").isProblem(400).andExpect { jsonPath("$.errors[0].field") { value("reason") } }
        report(m, t, """{"reason":"OTHER","note":"${"x".repeat(1001)}"}""").isProblem(400)
        report(m, t, """{"reason":"OTHER","note":"${"x".repeat(17 * 1024)}"}""").isProblem(413)
        count("problem_report") shouldBe 0
    }

    // ---------------------------------------------------------------- save and resume (FR-31), attempt log (FR-42)

    // @trace FR-31
    @Test
    fun `resume opens the first unresolved task with the attempts already used`() {
        val ids = List(5) { task(twoParts) }
        val m = mission(ids)
        listOf(0, 1, 3).forEach { graded(m, ids[it], """{"a":"12","b":"0.5"}""") }
        read(mvc.get("/api/child/missions/$m/current-task")).read<String>("$.taskId") shouldBe ids[2]
        graded(m, ids[2], """{"a":"12","b":"0.5"}""")
        graded(m, ids[4], """{"a":"12","b":"9"}""")
        val resumed = read(mvc.get("/api/child/missions/$m/current-task").andExpect { status { isOk() } })
        resumed.read<String>("$.taskId") shouldBe ids[4]
        resumed.read<Int>("$.attemptsUsed") shouldBe 1
        resumed.read<List<String>>("$.lastFeedback.parts[*].verdict") shouldContainExactly listOf("CORRECT", "WRONG")
        resumed.read<Int>("$.lastFeedback.attemptsLeft") shouldBe 1
        graded(m, ids[4], """{"b":"0.5"}""").read<Boolean>("$.missionComplete") shouldBe true
        mvc.get("/api/child/missions/$m/current-task").andExpect { status { isNoContent() } }
    }

    // @trace FR-42
    @Test
    fun `every graded attempt is logged verbatim and an answer-spec edit keeps the past verdicts`() {
        val t = task(twoParts, twoHints)
        val m = mission(listOf(t))
        hint(m, t)
        hint(m, t)
        graded(m, t, """{"a":"1 000","b":"0,5"}""")
        val row = jdbc.queryForMap("SELECT attempt_no, hints_used, replay, correct, created_at, parts::text AS parts FROM attempt")
        row["attempt_no"] shouldBe 1
        row["hints_used"] shouldBe 2
        row["replay"] shouldBe false
        row["created_at"].shouldNotBeNull()
        val parts = JsonPath.parse(row["parts"] as String)
        parts.read<List<String>>("$[*].raw") shouldContainExactly listOf("1 000", "0,5")
        parts.read<List<String>>("$[*].normalized") shouldContainExactly listOf("1000", "0.5")
        parts.read<List<String>>("$[*].verdict") shouldContainExactly listOf("WRONG", "CORRECT")
        mvc
            .adminJson(
                "PUT",
                admin,
                "/api/admin/tasks/$t",
                taskJson(
                    sourceId,
                    "parts" to """[{"key":"a","label":"a","spec":{"type":"INTEGER","accepted":["1000"]}},""" +
                        """{"key":"b","label":"b","spec":{"type":"DECIMAL","accepted":["0.5"]}}]""",
                    twoHints,
                ),
            ).andExpect { status { isOk() } }
        jdbc.queryForObject("SELECT parts->0->>'verdict' FROM attempt", String::class.java) shouldBe "WRONG"
        read(childTask(m, t)).let {
            it.read<Int>("$.attemptsUsed") shouldBe 1
            it.read<String>("$.state") shouldBe "UNRESOLVED"
        }
    }

    // ---------------------------------------------------------------- content usage (FR-56)

    // @trace FR-56
    @Test
    fun `attempts, reports and opened hints make tasks and their missions undeletable`() {
        val attempted = task()
        val reported = task()
        val hinted = task(twoHints)
        val m = mission(listOf(attempted, reported, hinted))
        graded(m, attempted, """{"a":"1"}""")
        report(m, reported, """{"reason":"UNCLEAR_PROMPT"}""")
        hint(m, hinted)
        unpublish(m)
        read(mvc.adminGet(admin, "/api/admin/tasks/$attempted")).let {
            it.read<Int>("$.attemptCount") shouldBe 1
            it.read<Boolean>("$.deletable") shouldBe false
        }
        for (id in listOf(attempted, reported, hinted)) mvc.adminDelete(admin, "/api/admin/tasks/$id").isProblem(409)
        mvc.adminDelete(admin, "/api/admin/missions/$m").isProblem(409)
        read(mvc.adminGet(admin, "/api/admin/missions/$m")).read<Boolean>("$.hasAttempts") shouldBe true
        val unused = task()
        mvc.adminDelete(admin, "/api/admin/tasks/$unused").andExpect { status { isNoContent() } }
    }

    // ---------------------------------------------------------------- slice-8 placeholders (design decision 7)

    // @trace FR-31
    @Test
    fun `replay and summary placeholders keep runs apart and HQ complete`() {
        val ids = List(2) { task() }
        val m = mission(ids)
        mvc.post("/api/child/missions/$m/replay").isProblem(409)
        mvc.get("/api/child/missions/$m/summary").isProblem(409)
        solve(m, ids[0])
        reveal(m, ids[1])
        read(mvc.get("/api/child/missions/$m/summary").andExpect { status { isOk() } }).let {
            it.read<Int>("$.firstTryCount") shouldBe 1
            it.read<Int>("$.taskCount") shouldBe 2
            it.read<Boolean>("$.replay") shouldBe false
            it.read<Int>("$.xpEarned") shouldBe 0
        }
        val replay = read(mvc.post("/api/child/missions/$m/replay").andExpect { status { isOk() } })
        replay.read<String>("$.taskId") shouldBe ids[0]
        replay.read<Int>("$.attemptsUsed") shouldBe 0
        replay.read<String>("$.state") shouldBe "UNRESOLVED"
        solve(m, ids[0])
        read(mvc.post("/api/child/missions/$m/replay")).read<String>("$.taskId") shouldBe ids[1]
        count("practice_run") shouldBe 1
        jdbc.queryForObject("SELECT count(*) FROM attempt WHERE replay AND attempt_no = 1", Int::class.java) shouldBe 1
        hq().let {
            it.read<String>("$.topics[0].missions[0].state") shouldBe "COMPLETE"
            it.read<Int>("$.topics[0].missions[0].resolvedCount") shouldBe 2
            it.read<Any?>("$.continueTarget").shouldBeNull()
        }
        read(mvc.get("/api/child/missions/$m/current-task")).read<String>("$.taskId") shouldBe ids[1]
        solve(m, ids[1]).read<Boolean>("$.missionComplete") shouldBe true
        read(mvc.get("/api/child/missions/$m/summary")).read<Boolean>("$.replay") shouldBe true
        mvc
            .post("/api/child/hq/rank-up-ack") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"rankIndex":1}"""
            }.andExpect { status { isNoContent() } }
        mvc
            .post("/api/child/hq/rank-up-ack") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"rankIndex":-1}"""
            }.isProblem(400)
            .andExpect { jsonPath("$.errors[0].field") { value("rankIndex") } }
        mvc.post("/api/child/hq") { contentType = MediaType.APPLICATION_JSON }.andExpect { status { isMethodNotAllowed() } }
        mvc.get("/api/child/missions/$m/summary").andExpect { jsonPath("$.rankUp") { value(nullValue()) } }
    }
}
