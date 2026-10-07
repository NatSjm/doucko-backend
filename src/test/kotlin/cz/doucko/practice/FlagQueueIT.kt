package cz.doucko.practice

import cz.doucko.TestcontainersConfiguration
import cz.doucko.admin.isProblem
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * The flag queue over HTTP against Postgres 16 (store spec progress-stats "Attempt log contents", "Attempt
 * log is append-only", "Flag queue", "Re-evaluation of a reported attempt", "Dismissing a report", "Report id
 * handling", "Stats and flag queue are admin-only"; FR-41, with the XP of gamification and the attempt log of
 * practice-flow). The wrong answer key of the spec's scenarios is a DECIMAL part expecting `0.05`; the fix
 * expects `0.5`, and the child types `0,5`.
 */
@Import(TestcontainersConfiguration::class)
@ExtendWith(DenyOutboundNetwork::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class FlagQueueIT {
    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var jdbc: JdbcTemplate

    lateinit var fx: StatsFixture

    @BeforeEach
    fun setUp() {
        fx = StatsFixture(mvc, jdbc)
        fx.setUp()
    }

    @AfterEach
    fun clean() = fx.clean()

    private val wrongKey get() = "parts" to fx.parts("DECIMAL", "0.05")
    private val fixedKey get() = "parts" to fx.parts("DECIMAL", "0.5")

    private fun badTask() = fx.task(wrongKey)

    private fun fix(taskId: String) = fx.edit(taskId, fixedKey)

    private fun entry(
        reportId: String,
        vararg params: Pair<String, String>,
    ): Map<String, Any?> = fx.reportsOk(*params).read<List<Map<String, Any?>>>("$[?(@.reportId == '$reportId')]").single()

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.attempt() = this["attempt"] as Map<String, Any?>?

    private fun state(
        m: String,
        t: String,
    ): String = fx.read(mvc.get("/api/child/missions/$m/tasks/$t")).read("$.state")

    private fun reevaluations(): Int = jdbc.queryForObject("SELECT count(*) FROM attempt_reevaluation", Int::class.java)!!

    private fun attemptRows(): List<Map<String, Any?>> =
        jdbc.queryForList("SELECT correct, parts::text AS parts FROM attempt ORDER BY created_at")

    // ---------------------------------------------------------------- the queue

    // @trace FR-41
    @Test
    fun `a report appears with the task, the reason and the attempt as logged`() {
        val t = fx.task(wrongKey, "prompt" to "\"Zapiš polovinu jako desetinné číslo.\"")
        val m = fx.mission(listOf(t))
        fx.answer(m, t, "0,5")
        val id = fx.report(m, t, "ANSWER_IS_CORRECT", "Myslím, že moje odpověď je správně")
        val e = entry(id)
        e["status"] shouldBe "OPEN"
        e["taskId"] shouldBe t
        e["reason"] shouldBe "ANSWER_IS_CORRECT"
        e["note"] shouldBe "Myslím, že moje odpověď je správně"
        e["taskPromptExcerpt"] shouldBe "Zapiš polovinu jako desetinné číslo."
        e["createdAt"].shouldNotBeNull()
        val a = e.attempt().shouldNotBeNull()
        a["attemptNo"] shouldBe 1
        a["rawInput"] shouldBe mapOf("a" to "0,5")
        a["normalized"] shouldBe mapOf("a" to "0.5")
        a["verdicts"] shouldBe mapOf("a" to "WRONG")
        a["hintsUsed"] shouldBe 0
        a["replay"] shouldBe false
        a["reevaluation"].shouldBeNull()
    }

    // @trace FR-41
    @Test
    fun `a report before any attempt has no attempt, and re-evaluating it is 409`() {
        val t = fx.task()
        val m = fx.mission(listOf(t))
        val id = fx.report(m, t, "UNCLEAR_PROMPT")
        val e = entry(id)
        e["attempt"].shouldBeNull()
        e["note"].shouldBeNull()
        e["reason"] shouldBe "UNCLEAR_PROMPT"
        fx.reevaluate(id).isProblem(409)
        fx.status(id) shouldBe "OPEN"
    }

    // @trace FR-41
    @Test
    fun `raw input is returned in full and verbatim, hints used and locale formatting included`() {
        val long = "𝔸ž".repeat(10) + "x".repeat(80)
        long.codePointCount(0, long.length) shouldBe 100
        val text = fx.task("parts" to fx.parts("TEXT", "kočka"), "hints" to """["Zvíře."]""")
        val number = fx.task("parts" to fx.parts("INTEGER", "1001"))
        val m = fx.mission(listOf(text, number))
        fx.openHints(m, text, 1)
        fx.answer(m, text, long)
        val first = fx.report(m, text)
        fx.answer(m, number, "1 000")
        val second = fx.report(m, number)
        entry(first).attempt()!!.let {
            it["rawInput"] shouldBe mapOf("a" to long)
            it["hintsUsed"] shouldBe 1
        }
        entry(second).attempt()!!.let {
            it["rawInput"] shouldBe mapOf("a" to "1 000")
            it["normalized"] shouldBe mapOf("a" to "1000")
        }
    }

    // @trace FR-41
    @Test
    fun `a long prompt is cut to an excerpt`() {
        val prompt = "Úloha " + "é".repeat(200)
        val t = fx.task("prompt" to "\"$prompt\"")
        val m = fx.mission(listOf(t))
        val excerpt = entry(fx.report(m, t, "UNCLEAR_PROMPT"))["taskPromptExcerpt"] as String
        excerpt shouldEndWith "…"
        prompt.startsWith(excerpt.removeSuffix("…")) shouldBe true
        excerpt.removeSuffix("…").length shouldBe 80
        // A space at the cut is kept: exactly 80 code points, then the ellipsis (review F04).
        val spaced = "a".repeat(79) + " " + "b".repeat(10)
        val u = fx.task("prompt" to "\"$spaced\"")
        val n = fx.mission(listOf(u))
        entry(fx.report(n, u, "UNCLEAR_PROMPT"))["taskPromptExcerpt"] shouldBe "a".repeat(79) + " …"
    }

    // @trace FR-41
    @Test
    fun `a locked part is not re-entered on attempt 2`() {
        val parts =
            """[{"key":"a","label":"a","spec":{"type":"INTEGER","accepted":["12"]}},""" +
                """{"key":"b","label":"b","spec":{"type":"DECIMAL","accepted":["0.05"]}}]"""
        val t = fx.task("parts" to parts)
        val m = fx.mission(listOf(t))
        fx.graded(m, t, """{"a":"12","b":"0,5"}""")
        fx.graded(m, t, """{"b":"0,5"}""")
        val id = fx.report(m, t)
        entry(id).attempt()!!.let {
            it["attemptNo"] shouldBe 2
            it["rawInput"] shouldBe mapOf("b" to "0,5")
            it["verdicts"] shouldBe mapOf("a" to "CORRECT", "b" to "WRONG")
        }
        fx.edit(t, "parts" to parts.replace("0.05", "0.5"))
        val result = fx.reevaluateOk(id)
        result.read<String>("$.newTaskState") shouldBe "CORRECT_SECOND_TRY"
        result.read<Int>("$.xpAwarded") shouldBe 4
        result.read<Map<String, String>>("$.report.attempt.reevaluation.verdicts") shouldBe mapOf("a" to "CORRECT", "b" to "CORRECT")
    }

    // @trace FR-41
    @Test
    fun `OPEN reports come first, oldest first, and the status filter is exact`() {
        val t = fx.task()
        val m = fx.mission(listOf(t))
        val base = Instant.parse("2026-09-21T08:00:00Z")

        fun report(
            status: String,
            at: Instant,
        ): String {
            val id = UUID.randomUUID()
            jdbc.update(
                "INSERT INTO problem_report (id, task_id, mission_id, reason, status, created_at) VALUES (?, ?::uuid, ?::uuid, 'OTHER', ?, ?)",
                id,
                t,
                m,
                status,
                Timestamp.from(at),
            )
            return id.toString()
        }
        val dismissedSunday = report("DISMISSED", base.minusSeconds(86_400))
        val wednesday = report("OPEN", base.plusSeconds(2 * 86_400))
        val monday = report("OPEN", base)
        val fixed = report("FIXED", base.minusSeconds(3 * 86_400))
        fx.reportsOk().read<List<String>>("$[*].reportId") shouldContainExactly listOf(monday, wednesday, fixed, dismissedSunday)
        fx.reportsOk("status" to "FIXED").read<List<String>>("$[*].reportId") shouldContainExactly listOf(fixed)
        fx.reportsOk("status" to "OPEN").read<List<String>>("$[*].reportId") shouldContainExactly listOf(monday, wednesday)
        fx.reportsOk("status" to "DISMISSED").read<List<String>>("$[*].status") shouldContainExactly listOf("DISMISSED")
        for (bad in listOf("FOO", "fixed", "", "Open", " OPEN")) {
            fx.reports("status" to bad).isProblem(400).andExpect {
                jsonPath("$.errors[0].field") { value("status") }
                jsonPath("$[0]") { doesNotExist() }
            }
        }
    }

    // ---------------------------------------------------------------- the attempt log is append-only

    // @trace FR-41
    @Test
    fun `editing an answer spec keeps past verdicts, the task state and the XP`() {
        val t = badTask()
        val m = fx.mission(listOf(t, fx.task()))
        fx.answer(m, t, "0,5")
        val before = attemptRows()
        fix(t)
        attemptRows() shouldBe before
        state(m, t) shouldBe "UNRESOLVED"
        fx.total() shouldBe 0
    }

    // ---------------------------------------------------------------- re-evaluation

    // @trace FR-41
    @Test
    fun `re-evaluation awards the missing XP for a revealed task and keeps the original verdict`() {
        val t = badTask()
        val m = fx.mission(listOf(t, fx.task()))
        fx.answer(m, t, "0,5")
        fx.answer(m, t, "0,5")
        val id = fx.report(m, t)
        fx.total() shouldBe 1
        fix(t)
        val result = fx.reevaluateOk(id)
        result.read<Boolean>("$.changed") shouldBe true
        result.read<Int>("$.xpAwarded") shouldBe 4
        result.read<Int>("$.bonusXp") shouldBe 0
        result.read<List<String>>("$.completedMissionIds") shouldHaveSize 0
        result.read<String>("$.newTaskState") shouldBe "CORRECT_SECOND_TRY"
        result.read<String>("$.report.status") shouldBe "FIXED"
        fx.total() shouldBe 5
        state(m, t) shouldBe "CORRECT_SECOND_TRY"
        val a = entry(id, "status" to "FIXED").attempt()!!
        a["attemptNo"] shouldBe 2
        a["verdicts"] shouldBe mapOf("a" to "WRONG")
        @Suppress("UNCHECKED_CAST")
        val re = a["reevaluation"] as Map<String, Any?>
        re["verdicts"] shouldBe mapOf("a" to "CORRECT")
        re["reevaluatedAt"].shouldNotBeNull()
        (re["reevaluatedAt"] == a["createdAt"]) shouldBe false
        attemptRows().map { it["correct"] } shouldContainExactly listOf(false, false)
        // The child's own feedback keeps what the attempt earned; HQ shows the total.
        fx.read(mvc.get("/api/child/missions/$m/tasks/$t")).let {
            it.read<Int>("$.lastFeedback.xpAwarded") shouldBe 1
            it.read<Int>("$.lastFeedback.attemptNo") shouldBe 2
            it.read<String>("$.lastFeedback.taskState") shouldBe "CORRECT_SECOND_TRY"
            it.read<String>("$.lastFeedback.parts[0].verdict") shouldBe "CORRECT"
        }
    }

    // @trace FR-41
    @Test
    fun `the task screen shows the re-evaluated attempt 1 of a revealed task`() {
        val t = badTask()
        val m = fx.mission(listOf(t, fx.task()))
        fx.answer(m, t, "0,5")
        val id = fx.report(m, t)
        fx.answer(m, t, "0,5")
        fix(t)
        fx.reevaluateOk(id).read<Int>("$.xpAwarded") shouldBe 9
        fx.read(mvc.get("/api/child/missions/$m/tasks/$t")).let {
            it.read<String>("$.state") shouldBe "CORRECT_FIRST_TRY"
            it.read<Int>("$.lastFeedback.attemptNo") shouldBe 1
            it.read<String>("$.lastFeedback.taskState") shouldBe "CORRECT_FIRST_TRY"
            it.read<String>("$.lastFeedback.parts[0].verdict") shouldBe "CORRECT"
        }
        fx.total() shouldBe 10
    }

    // @trace FR-41
    @Test
    fun `re-evaluation resolves a task still in progress`() {
        val t = badTask()
        val other = fx.task()
        val m = fx.mission(listOf(t, other))
        fx.answer(m, t, "0,5")
        val id = fx.report(m, t)
        fix(t)
        val result = fx.reevaluateOk(id)
        result.read<Int>("$.xpAwarded") shouldBe 10
        result.read<String>("$.newTaskState") shouldBe "CORRECT_FIRST_TRY"
        result.read<String>("$.report.status") shouldBe "FIXED"
        fx.status(id) shouldBe "FIXED"
        fx.total() shouldBe 10
        fx.attempt(m, t, """{"a":"0,5"}""").isProblem(409)
        fx.read(mvc.get("/api/child/missions/$m/current-task")).read<String>("$.taskId") shouldBe other
        fx.statsOk().read<Int>("$.firstTryRateByTopic[0].firstTry") shouldBe 1
    }

    // @trace FR-41
    @Test
    fun `re-evaluation completes a mission and pays its bonus`() {
        val solved = List(9) { fx.task() }
        val t = badTask()
        val m = fx.mission(solved + t)
        solved.forEach { fx.solve(m, it) }
        fx.answer(m, t, "0,5")
        val id = fx.report(m, t)
        fx.total() shouldBe 90
        fix(t)
        val result = fx.reevaluateOk(id)
        result.read<Int>("$.xpAwarded") shouldBe 10
        result.read<Int>("$.bonusXp") shouldBe 20
        result.read<List<String>>("$.completedMissionIds") shouldContainExactly listOf(m)
        val hq = fx.hq()
        hq.read<Int>("$.totalXp") shouldBe 120
        hq.read<List<Int>>("$.topics[*].missions[?(@.missionId == '$m')].resolvedCount") shouldContainExactly listOf(10)
        hq.read<List<String>>("$.topics[*].missions[?(@.missionId == '$m')].state") shouldContainExactly listOf("COMPLETE")
    }

    // @trace FR-41
    @Test
    fun `a rank-up from a re-evaluation waits for HQ`() {
        val t = badTask()
        val m = fx.mission(listOf(t, fx.task()))
        fx.answer(m, t, "0,5")
        val id = fx.report(m, t)
        fx.seedTo(145)
        fx.hq().read<Any?>("$.pendingRankUp").shouldBeNull()
        fix(t)
        fx.reevaluateOk(id).read<Int>("$.xpAwarded") shouldBe 10
        val hq = fx.hq()
        hq.read<Int>("$.rank.index") shouldBe 1
        hq.read<Int>("$.pendingRankUp.index") shouldBe 1
        // The celebration plays once: acknowledged, it is never offered again (review F07).
        mvc
            .post("/api/child/hq/rank-up-ack") {
                contentType = org.springframework.http.MediaType.APPLICATION_JSON
                content = """{"rankIndex":1}"""
            }.andExpect { status { isNoContent() } }
        fx.hq().read<Any?>("$.pendingRankUp").shouldBeNull()
        fx.hq().read<Int>("$.rank.index") shouldBe 1
    }

    // @trace FR-41
    @Test
    fun `re-evaluating a replay attempt records the verdict and awards nothing`() {
        val t = badTask()
        val m = fx.mission(listOf(t))
        fx.answer(m, t, "0,5")
        fx.answer(m, t, "0,5")
        val total = fx.total()
        total shouldBe 21
        fx.replay(m)
        fx.answer(m, t, "0,5")
        val id = fx.report(m, t)
        fix(t)
        val result = fx.reevaluateOk(id)
        result.read<Boolean>("$.changed") shouldBe true
        result.read<Int>("$.xpAwarded") shouldBe 0
        result.read<Int>("$.bonusXp") shouldBe 0
        result.read<Any?>("$.newTaskState").shouldBeNull()
        result.read<String>("$.report.status") shouldBe "FIXED"
        result.read<Boolean>("$.report.attempt.replay") shouldBe true
        result.read<Map<String, String>>("$.report.attempt.verdicts") shouldBe mapOf("a" to "WRONG")
        result.read<Map<String, String>>("$.report.attempt.reevaluation.verdicts") shouldBe mapOf("a" to "CORRECT")
        fx.total() shouldBe total
        fx.statsOk().read<Int>("$.firstTryRateByTopic[0].firstTry") shouldBe 0
        fx.read(mvc.get("/api/child/missions/$m")).read<List<String>>("$.tasks[*].state") shouldContainExactly listOf("UNRESOLVED")
    }

    // @trace FR-41
    @Test
    fun `re-evaluation never lowers XP`() {
        val t = fx.task()
        val m = fx.mission(listOf(t, fx.task()))
        fx.solve(m, t)
        val id = fx.report(m, t)
        fx.edit(t, "parts" to fx.parts("INTEGER", "13"))
        val result = fx.reevaluateOk(id)
        result.read<Boolean>("$.changed") shouldBe false
        result.read<Int>("$.xpAwarded") shouldBe 0
        result.read<Int>("$.bonusXp") shouldBe 0
        result.read<Any?>("$.newTaskState").shouldBeNull()
        result.read<String>("$.report.status") shouldBe "OPEN"
        result.read<Any?>("$.report.attempt.reevaluation").shouldBeNull()
        fx.total() shouldBe 10
        state(m, t) shouldBe "CORRECT_FIRST_TRY"
        fx.status(id) shouldBe "OPEN"
    }

    // @trace FR-41
    @Test
    fun `an unchanged spec changes nothing`() {
        val t = fx.task()
        val m = fx.mission(listOf(t, fx.task()))
        fx.wrong(m, t)
        val id = fx.report(m, t)
        fx.reevaluateOk(id).let {
            it.read<Boolean>("$.changed") shouldBe false
            it.read<Int>("$.xpAwarded") shouldBe 0
        }
        state(m, t) shouldBe "UNRESOLVED"
        fx.status(id) shouldBe "OPEN"
        reevaluations() shouldBe 0
        fx.total() shouldBe 0
    }

    // @trace FR-41
    @Test
    fun `an attempt unparseable under the new spec is no change and stores nothing`() {
        val t = fx.task("parts" to fx.parts("TEXT", "xyz"))
        val m = fx.mission(listOf(t, fx.task()))
        fx.answer(m, t, "abc")
        val id = fx.report(m, t)
        fx.edit(t, "parts" to fx.parts("INTEGER", "3"))
        fx.reevaluateOk(id).read<Boolean>("$.changed") shouldBe false
        reevaluations() shouldBe 0
        fx.status(id) shouldBe "OPEN"
    }

    // @trace FR-41
    @Test
    fun `input that no longer fits the part is no change`() {
        val t = fx.task("parts" to fx.parts("TEXT", "xyz"))
        val m = fx.mission(listOf(t, fx.task()))
        fx.answer(m, t, "A")
        val id = fx.report(m, t)
        val choice =
            """[{"key":"a","label":"a","spec":{"type":"CHOICE_SINGLE","accepted":["A"],""" +
                """"options":[{"key":"A","text":"jedna"},{"key":"B","text":"dvě"}]}}]"""
        fx.edit(t, "parts" to choice)
        fx.reevaluateOk(id).read<Boolean>("$.changed") shouldBe false
        reevaluations() shouldBe 0
    }

    // @trace FR-41
    @Test
    fun `re-evaluating twice awards XP once, and a dismissed report cannot be re-evaluated`() {
        val t = badTask()
        val other = badTask()
        val m = fx.mission(listOf(t, other, fx.task()))
        fx.answer(m, t, "0,5")
        val fixed = fx.report(m, t)
        fx.answer(m, other, "0,5")
        val dismissed = fx.report(m, other)
        fx.dismiss(dismissed).andExpect { status { isOk() } }
        fix(t)
        fix(other)
        fx.reevaluateOk(fixed).read<Int>("$.xpAwarded") shouldBe 10
        fx.reevaluate(fixed).isProblem(409)
        fx.reevaluate(dismissed).isProblem(409)
        fx.total() shouldBe 10
        fx.status(dismissed) shouldBe "DISMISSED"
        state(m, other) shouldBe "UNRESOLVED"
    }

    // ---------------------------------------------------------------- dismiss

    // @trace FR-41
    @Test
    fun `dismissing changes only the report`() {
        val t = fx.task()
        val m = fx.mission(listOf(t, fx.task()))
        fx.wrong(m, t)
        val id = fx.report(m, t, "OTHER", "Nevím.")
        val before = attemptRows()
        val report = fx.read(fx.dismiss(id).andExpect { status { isOk() } })
        report.read<String>("$.status") shouldBe "DISMISSED"
        report.read<String>("$.reportId") shouldBe id
        report.read<String>("$.attempt.verdicts.a") shouldBe "WRONG"
        attemptRows() shouldBe before
        state(m, t) shouldBe "UNRESOLVED"
        fx.total() shouldBe 0
        fx.dismiss(id).isProblem(409)
        fx.status(id) shouldBe "DISMISSED"
    }

    // @trace FR-41
    @Test
    fun `a fixed report cannot be dismissed and keeps its award`() {
        val t = badTask()
        val m = fx.mission(listOf(t, fx.task()))
        fx.answer(m, t, "0,5")
        fx.answer(m, t, "0,5")
        val id = fx.report(m, t)
        fix(t)
        fx.reevaluateOk(id).read<Int>("$.xpAwarded") shouldBe 4
        fx.dismiss(id).isProblem(409)
        fx.status(id) shouldBe "FIXED"
        fx.total() shouldBe 5
    }

    // ---------------------------------------------------------------- report ids

    // @trace FR-41
    @Test
    fun `an unknown report is 404 and a malformed id is 400 naming reportId`() {
        val t = badTask()
        val m = fx.mission(listOf(t, fx.task()))
        fx.answer(m, t, "0,5")
        val id = fx.report(m, t)
        fix(t)
        val unknown = UUID.randomUUID().toString()
        fx.reevaluate(unknown).isProblem(404)
        fx.dismiss(unknown).isProblem(404)
        for (bad in listOf("123", "abc", "a".repeat(5_000))) {
            fx.reevaluate(bad).isProblem(400).andExpect { jsonPath("$.errors[0].field") { value("reportId") } }
            fx.dismiss(bad).isProblem(400).andExpect { jsonPath("$.errors[0].field") { value("reportId") } }
        }
        fx.status(id) shouldBe "OPEN"
        fx.total() shouldBe 0
    }

    // ---------------------------------------------------------------- admin only

    // @trace FR-41
    @Test
    fun `re-evaluate and dismiss need a session and a CSRF token`() {
        val t = badTask()
        val m = fx.mission(listOf(t, fx.task()))
        fx.answer(m, t, "0,5")
        val id = fx.report(m, t)
        fix(t)
        fx.reevaluate(id, session = false).isProblem(401)
        fx.reevaluate(id, csrf = false).isProblem(403)
        fx.dismiss(id, session = false).isProblem(401)
        fx.dismiss(id, csrf = false).isProblem(403)
        fx.reevaluate("123", session = false).isProblem(401)
        fx.status(id) shouldBe "OPEN"
        fx.total() shouldBe 0
        mvc.get("/api/admin/reports").isProblem(401).andExpect { jsonPath("$[0]") { doesNotExist() } }
    }
}
