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
import cz.doucko.content.adminJson
import cz.doucko.content.taskJson
import cz.doucko.content.uploadSource
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
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
 * Gamification over HTTP against Postgres 16 (store spec gamification; the "Mission complete screen" and
 * "Replay completed missions" requirements of practice-flow): awards, bonuses, ranks, rank-up
 * celebrations, summaries and the append-only ledger. A starting total is set by inserting bonus rows for
 * missions that do not exist ([seedTo]): XP credited outside a child session.
 */
@Import(TestcontainersConfiguration::class)
@ExtendWith(DenyOutboundNetwork::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class GamificationIT {
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
    }

    @AfterEach
    fun clean() {
        jdbc.cleanPractice()
        for (table in listOf("mission", "task_image", "task", "source")) jdbc.update("DELETE FROM $table")
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

    private fun hints(n: Int) = "hints" to List(n) { "\"Nápověda ${it + 1}.\"" }.joinToString(",", "[", "]")

    private fun task(vararg overrides: Pair<String, String>): String {
        val id = created(mvc.adminJson("POST", admin, "/api/admin/tasks", taskJson(sourceId, *overrides)), "$.taskId")
        mvc.adminJson("PUT", admin, "/api/admin/tasks/$id/status", """{"status":"PUBLISHED"}""").andExpect { status { isOk() } }
        return id
    }

    private fun tasks(n: Int) = List(n) { task() }

    private fun mission(
        ids: List<String>,
        publish: Boolean = true,
    ): String {
        val body = """{"topicId":"${Topics.DECIMALS}","position":${++position},"taskIds":${ids.joinToString(
            ",",
            "[",
            "]",
        ) { "\"$it\"" }}}"""
        val id = created(mvc.adminJson("POST", admin, "/api/admin/missions", body), "$.missionId")
        if (publish) status(id, "PUBLISHED")
        return id
    }

    private fun status(
        missionId: String,
        status: String,
    ) = mvc.adminJson("PUT", admin, "/api/admin/missions/$missionId/status", """{"status":"$status"}""").andExpect { status { isOk() } }

    private fun read(result: ResultActionsDsl): DocumentContext = JsonPath.parse(result.andReturn().response.contentAsString)

    private fun hq() = read(mvc.get("/api/child/hq").andExpect { status { isOk() } })

    private fun total() = hq().read<Int>("$.totalXp")

    private fun briefing(m: String) = read(mvc.get("/api/child/missions/$m").andExpect { status { isOk() } })

    private fun summary(m: String) = read(mvc.get("/api/child/missions/$m/summary").andExpect { status { isOk() } })

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

    private fun solve(
        m: String,
        t: String,
    ) = graded(m, t, """{"a":"12"}""")

    private fun wrong(
        m: String,
        t: String,
    ) = graded(m, t, """{"a":"1"}""")

    private fun reveal(
        m: String,
        t: String,
    ): DocumentContext {
        wrong(m, t)
        return wrong(m, t)
    }

    private fun openHints(
        m: String,
        t: String,
        n: Int,
    ) = repeat(n) { mvc.post("/api/child/missions/$m/tasks/$t/hints").andExpect { status { isOk() } } }

    private fun ack(rankIndex: Int) =
        mvc
            .post("/api/child/hq/rank-up-ack") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"rankIndex":$rankIndex}"""
            }.andExpect { status { isNoContent() } }

    private fun replay(m: String) = read(mvc.post("/api/child/missions/$m/replay").andExpect { status { isOk() } })

    /** Raises the total to [target] with bonus rows of missions that do not exist (XP credited outside a session). */
    private fun seedTo(target: Int) {
        var left = target - ledgerTotal()
        while (left > 0) {
            val amount = minOf(left, 1000)
            jdbc.update(
                "INSERT INTO xp_award (id, kind, mission_id, amount, created_at) VALUES (?, 'MISSION_BONUS', ?, ?, now())",
                UUID.randomUUID(),
                UUID.randomUUID(),
                amount,
            )
            left -= amount
        }
        ledgerTotal() shouldBe target
    }

    private fun ledgerTotal(): Int = jdbc.queryForObject("SELECT coalesce(sum(amount), 0) FROM xp_award", Int::class.java)!!

    private fun bonusRows(missionId: String): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM xp_award WHERE kind = 'MISSION_BONUS' AND mission_id = ?::uuid",
            Int::class.java,
            missionId,
        )!!

    // ---------------------------------------------------------------- XP per task (FR-34)

    // @trace FR-34
    @Test
    fun `the award table holds over HTTP and the total is the sum`() {
        val t = List(8) { task(hints(5)) }
        val m = mission(t + task())
        openHints(m, t[1], 2)
        openHints(m, t[2], 5)
        openHints(m, t[4], 1)
        openHints(m, t[5], 3)
        openHints(m, t[7], 3)
        val awards =
            listOf(
                solve(m, t[0]),
                solve(m, t[1]),
                solve(m, t[2]),
                wrong(m, t[3]).let { solve(m, t[3]) },
                wrong(m, t[4]).let { solve(m, t[4]) },
                wrong(m, t[5]).let { solve(m, t[5]) },
                reveal(m, t[6]),
                reveal(m, t[7]),
            ).map { it.read<Int>("$.xpAwarded") }
        awards shouldContainExactly listOf(10, 6, 1, 5, 3, 1, 1, 1)
        hq().let {
            it.read<Int>("$.totalXp") shouldBe 28
            it.read<Int>("$.solvedTasks") shouldBe 8
        }
        read(mvc.get("/api/child/missions/$m/tasks/${t[0]}")).let {
            it.read<Int>("$.lastFeedback.xpAwarded") shouldBe 10
            it.read<Any?>("$.lastFeedback.rankUp").shouldBeNull()
        }
    }

    // @trace FR-34, FR-38
    @Test
    fun `wrong attempts and unreadable input neither award nor cost XP`() {
        val (t1, t2) = tasks(2)
        val m = mission(listOf(t1, t2, task()))
        seedTo(40)
        graded(m, t1, """{"a":"5,,2"}""").let {
            it.read<String>("$.status") shouldBe "UNPARSEABLE"
            it.read<Int>("$.xpAwarded") shouldBe 0
            it.read<Int>("$.bonusXp") shouldBe 0
            it.read<List<Any>>("$.completedMissionIds") shouldBe emptyList()
        }
        total() shouldBe 40
        solve(m, t1).read<Int>("$.xpAwarded") shouldBe 10
        total() shouldBe 50
        wrong(m, t2).let {
            it.read<Int>("$.xpAwarded") shouldBe 0
            it.read<String>("$.taskState") shouldBe "UNRESOLVED"
        }
        total() shouldBe 50
        solve(m, t2).read<Int>("$.xpAwarded") shouldBe 5
        total() shouldBe 55
    }

    // @trace FR-34
    @Test
    fun `a task resolved in one mission earns nothing in another, and later hints change nothing`() {
        val t = task(hints(2))
        val a = mission(listOf(t, task()))
        val b = mission(listOf(t, task()))
        solve(a, t).read<Int>("$.xpAwarded") shouldBe 10
        attempt(b, t, """{"a":"12"}""").isProblem(409)
        openHints(b, t, 2)
        total() shouldBe 10
        briefing(b).read<Int>("$.xpAvailable") shouldBe 30
        jdbc.queryForObject("SELECT count(*) FROM xp_award WHERE kind = 'TASK'", Int::class.java) shouldBe 1
    }

    // ---------------------------------------------------------------- XP is granted only by the server

    // @trace FR-34, FR-35
    @Test
    fun `client-supplied XP fields in an attempt are ignored`() {
        val (t, other) = tasks(2)
        val m = mission(listOf(t, other))
        seedTo(50)
        val before = hq().read<Int>("$.solvedTasks")
        val result =
            read(
                attempt(
                    m,
                    t,
                    """{"a":"12"}""",
                    ""","xpAwarded":500,"totalXp":9999,"rank":4,"solvedTasks":99""",
                ).andExpect { status { isOk() } },
            )
        result.read<Int>("$.xpAwarded") shouldBe 10
        hq().let {
            it.read<Int>("$.totalXp") shouldBe 60
            it.read<Int>("$.solvedTasks") shouldBe before + 1
        }
    }

    // @trace FR-34, FR-38
    @Test
    fun `progress has no write operation`() {
        val ids = tasks(4)
        val m = mission(ids)
        ids.take(3).forEach { solve(m, it) }
        seedTo(50)
        val rank = hq().read<Map<String, Any>>("$.rank")
        val body = """{"totalXp": 9999, "rank": {"index": 4}, "solvedTasks": 99}"""
        mvc
            .put("/api/child/hq") {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect { status { is4xxClientError() } }
        mvc
            .post("/api/child/hq") {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect { status { is4xxClientError() } }
        mvc
            .patch("/api/child/hq") {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect { status { is4xxClientError() } }
        hq().let {
            it.read<Int>("$.totalXp") shouldBe 50
            it.read<Int>("$.solvedTasks") shouldBe 3
            it.read<Map<String, Any>>("$.rank") shouldBe rank
        }
    }

    // @trace FR-35, FR-38
    @Test
    fun `re-evaluation without an admin session is 401 and the total stays`() {
        seedTo(50)
        mvc.post("/api/admin/reports/${UUID.randomUUID()}/reevaluate").isProblem(401)
        total() shouldBe 50
    }

    // ---------------------------------------------------------------- mission completion bonus (FR-35)

    // @trace FR-35
    @Test
    fun `the first completion pays the bonus with the last task`() {
        val ids = tasks(10)
        val m = mission(ids)
        ids.take(9).forEach { solve(m, it) }
        seedTo(100)
        solve(m, ids[9]).let {
            it.read<Int>("$.xpAwarded") shouldBe 10
            it.read<Int>("$.bonusXp") shouldBe 20
            it.read<List<String>>("$.completedMissionIds") shouldContainExactly listOf(m)
            it.read<Boolean>("$.missionComplete") shouldBe true
        }
        total() shouldBe 130
    }

    // @trace FR-35
    @Test
    fun `a revealed last task completes the mission too`() {
        val ids = tasks(10)
        val m = mission(ids)
        ids.take(9).forEach { solve(m, it) }
        seedTo(100)
        reveal(m, ids[9]).let {
            it.read<Int>("$.xpAwarded") shouldBe 1
            it.read<Int>("$.bonusXp") shouldBe 20
            it.read<Boolean>("$.missionComplete") shouldBe true
        }
        total() shouldBe 121
    }

    // @trace FR-35, FR-33
    @Test
    fun `a replay to the end pays no task XP and no second bonus`() {
        val ids = tasks(10)
        val m = mission(ids)
        ids.forEach { solve(m, it) }
        seedTo(250)
        replay(m)
        val results = ids.map { solve(m, it) }
        results.map { it.read<Int>("$.xpAwarded") }.distinct() shouldContainExactly listOf(0)
        results.map { it.read<Int>("$.bonusXp") }.distinct() shouldContainExactly listOf(0)
        results.map { it.read<List<Any>>("$.completedMissionIds") }.distinct() shouldContainExactly listOf(emptyList())
        results.map { it.read<Any?>("$.rankUp") }.distinct() shouldContainExactly listOf(null)
        results.last().read<Boolean>("$.missionComplete") shouldBe true
        total() shouldBe 250
        jdbc.queryForObject("SELECT count(*) FROM attempt WHERE replay", Int::class.java) shouldBe 10
        summary(m).let {
            it.read<Boolean>("$.replay") shouldBe true
            it.read<Int>("$.xpEarned") shouldBe 0
            it.read<Int>("$.firstTryCount") shouldBe 10
        }
        bonusRows(m) shouldBe 1
    }

    // @trace FR-35
    @Test
    fun `a shared task completes another mission and pays its bonus`() {
        val t = tasks(11)
        val a = mission(t.take(10))
        val b = mission(listOf(t[0], t[1], t[10]))
        solve(b, t[10])
        solve(a, t[0])
        seedTo(100)
        solve(a, t[1]).let {
            it.read<Int>("$.xpAwarded") shouldBe 10
            it.read<Int>("$.bonusXp") shouldBe 20
            it.read<List<String>>("$.completedMissionIds") shouldContainExactly listOf(b)
            it.read<Boolean>("$.missionComplete") shouldBe false
        }
        total() shouldBe 130
        hq().read<List<String>>("$.topics[0].missions[*].state") shouldContainExactly listOf("IN_PROGRESS", "COMPLETE")
        briefing(b).read<String>("$.state") shouldBe "COMPLETE"
        replay(b)
        listOf(t[0], t[1], t[10]).map { solve(b, it).read<Int>("$.xpAwarded") } shouldContainExactly listOf(0, 0, 0)
        total() shouldBe 130
    }

    // @trace FR-35
    @Test
    fun `one resolution completes two missions and pays both bonuses`() {
        val (t, x, y) = tasks(3)
        val a = mission(listOf(x, t))
        val b = mission(listOf(y, t))
        solve(a, x)
        solve(b, y)
        seedTo(100)
        solve(a, t).let {
            it.read<Int>("$.xpAwarded") shouldBe 10
            it.read<Int>("$.bonusXp") shouldBe 40
            it.read<List<String>>("$.completedMissionIds") shouldContainExactlyInAnyOrder listOf(a, b)
            it.read<Boolean>("$.missionComplete") shouldBe true
        }
        total() shouldBe 150
    }

    // @trace FR-35
    @Test
    fun `each sharing mission pays its own bonus once`() {
        val (s1, s2, z) = tasks(3)
        val a = mission(listOf(s1, s2))
        val b = mission(listOf(s1, s2, z))
        solve(a, s1)
        solve(a, s2).read<List<String>>("$.completedMissionIds") shouldContainExactly listOf(a)
        solve(b, z).let {
            it.read<Int>("$.bonusXp") shouldBe 20
            it.read<List<String>>("$.completedMissionIds") shouldContainExactly listOf(b)
        }
        bonusRows(a) shouldBe 1
        bonusRows(b) shouldBe 1
        total() shouldBe 70
    }

    // @trace FR-35, FR-36
    @Test
    fun `a mission published already complete pays its bonus once and raises a pending rank-up`() {
        val (t1, t2, open) = tasks(3)
        val a = mission(listOf(t1, t2, open))
        solve(a, t1)
        solve(a, t2)
        seedTo(140)
        val c = mission(listOf(t1, t2), publish = false)
        hq().read<Any?>("$.pendingRankUp").shouldBeNull()
        status(c, "PUBLISHED")
        hq().let {
            it.read<Int>("$.totalXp") shouldBe 160
            it.read<Int>("$.rank.index") shouldBe 1
            it.read<Int>("$.pendingRankUp.index") shouldBe 1
            it.read<String>("$.pendingRankUp.name") shouldBe it.read<String>("$.rank.name")
        }
        status(c, "DRAFT")
        status(c, "PUBLISHED")
        total() shouldBe 160
        bonusRows(c) shouldBe 1
        jdbc.queryForObject("SELECT attempt_id IS NULL FROM xp_award WHERE mission_id = ?::uuid", Boolean::class.java, c) shouldBe true
        ack(1)
        hq().read<Any?>("$.pendingRankUp").shouldBeNull()
        hq().read<Any?>("$.pendingRankUp").shouldBeNull()
    }

    // @trace FR-35
    @Test
    fun `an edit that removes the last unresolved task of a published mission pays its bonus`() {
        val (t1, open) = tasks(2)
        val m = mission(listOf(t1, open))
        solve(m, t1)
        total() shouldBe 10
        val body = """{"topicId":"${Topics.DECIMALS}","position":$position,"taskIds":["$t1"]}"""
        mvc.adminJson("PUT", admin, "/api/admin/missions/$m", body).andExpect { status { isOk() } }
        total() shouldBe 30
        bonusRows(m) shouldBe 1
    }

    // ---------------------------------------------------------------- rank-ups (FR-36)

    // @trace FR-36
    @Test
    fun `a mid-mission rank-up plays once with the attempt`() {
        val (t1, t2) = tasks(2)
        val m = mission(listOf(t1, t2))
        seedTo(145)
        solve(m, t1).let {
            it.read<Int>("$.rankUp.index") shouldBe 1
            it.read<Int>("$.rankUp.minXp") shouldBe 150
            it.read<Boolean>("$.missionComplete") shouldBe false
        }
        hq().let {
            it.read<Int>("$.rank.index") shouldBe 1
            it.read<Any?>("$.pendingRankUp").shouldBeNull()
        }
        read(mvc.get("/api/child/missions/$m/tasks/$t1")).read<Any?>("$.lastFeedback.rankUp").shouldBeNull()
    }

    // @trace FR-36
    @Test
    fun `an award that crosses no threshold has no rank-up`() {
        val (t1, t2) = tasks(2)
        val m = mission(listOf(t1, t2))
        seedTo(160)
        ack(1)
        solve(m, t1).read<Any?>("$.rankUp").shouldBeNull()
        hq().read<Any?>("$.pendingRankUp").shouldBeNull()
    }

    // @trace FR-36, FR-32
    @Test
    fun `a mission-complete rank-up waits for the summary or HQ and plays once`() {
        val t = task()
        val m = mission(listOf(t))
        seedTo(140)
        solve(m, t).let {
            it.read<Boolean>("$.missionComplete") shouldBe true
            it.read<Any?>("$.rankUp").shouldBeNull()
            it.read<Int>("$.bonusXp") shouldBe 20
        }
        hq().read<Int>("$.pendingRankUp.index") shouldBe 1
        summary(m).let {
            it.read<Int>("$.rankUp.index") shouldBe 1
            it.read<Any?>("$.rankReachedDuringRun").shouldBeNull()
            it.read<Int>("$.xpEarned") shouldBe 30
        }
        ack(1)
        hq().read<Any?>("$.pendingRankUp").shouldBeNull()
        summary(m).let {
            it.read<Any?>("$.rankUp").shouldBeNull()
            it.read<Int>("$.rankReachedDuringRun.index") shouldBe 1
        }
        hq().read<Any?>("$.pendingRankUp").shouldBeNull()
    }

    // @trace FR-36, FR-32
    @Test
    fun `a rank-up earlier in the run is a static line on the summary`() {
        val ids = tasks(3)
        val m = mission(ids)
        seedTo(145)
        solve(m, ids[0]).read<Int>("$.rankUp.index") shouldBe 1
        solve(m, ids[1]).read<Any?>("$.rankUp").shouldBeNull()
        solve(m, ids[2]).read<Boolean>("$.missionComplete") shouldBe true
        summary(m).let {
            it.read<Any?>("$.rankUp").shouldBeNull()
            it.read<Int>("$.rankReachedDuringRun.index") shouldBe 1
            it.read<Int>("$.xpEarned") shouldBe 50
        }
        hq().read<Any?>("$.pendingRankUp").shouldBeNull()
    }

    // @trace FR-36
    @Test
    fun `an older acknowledgement keeps a newer pending rank-up, and acknowledgements cannot run ahead`() {
        seedTo(500)
        hq().read<Int>("$.pendingRankUp.index") shouldBe 2
        ack(1)
        hq().read<Int>("$.pendingRankUp.index") shouldBe 2
        ack(4)
        hq().read<Any?>("$.pendingRankUp").shouldBeNull()
        seedTo(900)
        hq().read<Int>("$.pendingRankUp.index") shouldBe 3
        mvc
            .post("/api/child/hq/rank-up-ack") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"rankIndex":-1}"""
            }.isProblem(400)
        hq().read<Int>("$.pendingRankUp.index") shouldBe 3
    }

    // ---------------------------------------------------------------- HQ progress (FR-37)

    // @trace FR-37
    @Test
    fun `HQ shows rank, next rank and total from a fresh profile to the top rank`() {
        hq().let {
            it.read<Int>("$.totalXp") shouldBe 0
            it.read<Int>("$.solvedTasks") shouldBe 0
            it.read<Int>("$.rank.index") shouldBe 0
            it.read<Int>("$.rank.minXp") shouldBe 0
            it.read<Int>("$.nextRank.minXp") shouldBe 150
            it.read<Any?>("$.pendingRankUp").shouldBeNull()
        }
        seedTo(275)
        ack(4)
        hq().let {
            it.read<Int>("$.rank.index") shouldBe 1
            it.read<Int>("$.rank.minXp") shouldBe 150
            it.read<Int>("$.nextRank.minXp") shouldBe 400
        }
        seedTo(1600)
        hq().let {
            it.read<Int>("$.rank.index") shouldBe 4
            it.read<Any?>("$.nextRank").shouldBeNull()
        }
    }

    // @trace FR-37
    @Test
    fun `solved tasks count each task once across missions and replays`() {
        val ids = tasks(12)
        val a = mission(ids.take(11))
        val b = mission(listOf(ids[0], ids[11]))
        ids.take(11).forEach { solve(a, it) }
        solve(b, ids[11])
        replay(a)
        solve(a, ids[0])
        hq().read<Int>("$.solvedTasks") shouldBe 12
    }

    // ---------------------------------------------------------------- summary, briefing (FR-32, FR-35)

    // @trace FR-32
    @Test
    fun `the summary of a first completion sums task XP and bonus`() {
        val ids = tasks(10)
        val m = mission(ids)
        ids.take(7).forEach { solve(m, it) }
        ids.subList(7, 9).forEach {
            wrong(m, it)
            solve(m, it)
        }
        reveal(m, ids[9])
        summary(m).let {
            it.read<Int>("$.xpEarned") shouldBe 101
            it.read<Int>("$.firstTryCount") shouldBe 7
            it.read<Int>("$.taskCount") shouldBe 10
            it.read<Boolean>("$.replay") shouldBe false
        }
    }

    // @trace FR-32
    @Test
    fun `a task resolved in another mission adds nothing to the summary`() {
        val ids = tasks(11)
        val other = mission(listOf(ids[0], ids[10]))
        val m = mission(ids.take(10))
        solve(other, ids[0])
        ids.drop(1).take(9).forEach { solve(m, it) }
        summary(m).let {
            it.read<Int>("$.xpEarned") shouldBe 110
            it.read<Int>("$.firstTryCount") shouldBe 9
        }
    }

    // @trace FR-32
    @Test
    fun `the summary is 404 for an unserved mission and 409 before a completed run`() {
        val (t1, t2) = tasks(2)
        val m = mission(listOf(t1, t2))
        mvc.get("/api/child/missions/${UUID.randomUUID()}/summary").isProblem(404)
        mvc.get("/api/child/missions/$m/summary").isProblem(409)
        solve(m, t1)
        mvc.get("/api/child/missions/$m/summary").isProblem(409)
    }

    // @trace FR-35
    @Test
    fun `the briefing shows the XP still available`() {
        val ids = tasks(3)
        val m = mission(ids)
        briefing(m).read<Int>("$.xpAvailable") shouldBe 50
        solve(m, ids[0])
        briefing(m).read<Int>("$.xpAvailable") shouldBe 40
        solve(m, ids[1])
        solve(m, ids[2])
        briefing(m).read<Int>("$.xpAvailable") shouldBe 0
        replay(m)
        briefing(m).read<Int>("$.xpAvailable") shouldBe 0
    }

    // ---------------------------------------------------------------- no negative mechanics (FR-38)

    // @trace FR-38
    @Test
    fun `ledger rows cannot be updated or deleted`() {
        val (t, other) = tasks(2)
        val m = mission(listOf(t, other))
        solve(m, t)
        shouldThrowAny { jdbc.update("UPDATE xp_award SET amount = 0") }
        shouldThrowAny { jdbc.update("DELETE FROM xp_award") }
        ledgerTotal() shouldBe 10
        jdbc.queryForObject("SELECT amount FROM xp_award", Int::class.java) shouldBe 10
    }

    // @trace FR-38
    @Test
    fun `XP never goes down over a mixed sequence`() {
        val ids = List(4) { task(hints(2)) }
        val m = mission(ids)
        val totals = mutableListOf(total())

        fun step(action: () -> Unit) {
            action()
            totals += total()
        }
        step { wrong(m, ids[0]) }
        step { openHints(m, ids[0], 1) }
        step { wrong(m, ids[0]) }
        step { openHints(m, ids[0], 1) }
        step { graded(m, ids[1], """{"a":"5,,2"}""") }
        step { solve(m, ids[1]) }
        step {
            mvc
                .adminJson(
                    "PUT",
                    admin,
                    "/api/admin/tasks/${ids[1]}",
                    taskJson(sourceId, hints(2), "parts" to """[{"key":"a","label":"a","spec":{"type":"INTEGER","accepted":["13"]}}]"""),
                ).andExpect { status { isOk() } }
        }
        step { solve(m, ids[2]) }
        step { reveal(m, ids[3]) }
        step { replay(m) }
        step { wrong(m, ids[0]) }
        step { ack(4) }
        totals shouldBe totals.sorted()
        totals.last() shouldBe 1 + 10 + 10 + 1 + 20
    }
}
