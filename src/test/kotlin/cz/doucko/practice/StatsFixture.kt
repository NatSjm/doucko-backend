package cz.doucko.practice

import com.jayway.jsonpath.DocumentContext
import com.jayway.jsonpath.JsonPath
import cz.doucko.admin.Admin
import cz.doucko.admin.signedIn
import cz.doucko.content.Files
import cz.doucko.content.SOURCE_FIELDS
import cz.doucko.content.Topics
import cz.doucko.content.adminGet
import cz.doucko.content.adminJson
import cz.doucko.content.taskJson
import cz.doucko.content.uploadSource
import jakarta.servlet.http.Cookie
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.sql.Timestamp
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.UUID

/**
 * HTTP and database helpers for the progress-stats integration tests (slice 9): authoring through the admin
 * API, practice through the child API, the admin statistics and flag queue, and attempts or hints placed at
 * chosen times directly in the log (the only way to have history in past weeks).
 */
class StatsFixture(
    private val mvc: MockMvc,
    private val jdbc: JdbcTemplate,
) {
    lateinit var admin: Admin
    private lateinit var sourceId: String
    private var position = 0

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

    fun clean() {
        jdbc.cleanPractice()
        for (table in listOf("mission", "task_image", "task", "source")) jdbc.update("DELETE FROM $table")
    }

    fun read(result: ResultActionsDsl): DocumentContext = JsonPath.parse(result.andReturn().response.contentAsString)

    // ---------------------------------------------------------------- authoring

    fun parts(
        type: String = "INTEGER",
        accepted: String = "12",
        key: String = "a",
    ) = """[{"key":"$key","label":"$key","spec":{"type":"$type","accepted":["$accepted"]}}]"""

    fun task(
        vararg overrides: Pair<String, String>,
        publish: Boolean = true,
    ): String {
        val id: String =
            JsonPath.read(
                mvc
                    .adminJson("POST", admin, "/api/admin/tasks", taskJson(sourceId, *overrides))
                    .andExpect { status { isCreated() } }
                    .andReturn()
                    .response.contentAsString,
                "$.taskId",
            )
        if (publish) {
            mvc.adminJson("PUT", admin, "/api/admin/tasks/$id/status", """{"status":"PUBLISHED"}""").andExpect { status { isOk() } }
        }
        return id
    }

    /** Replaces the task's content (an answer-spec fix); the task keeps its status. */
    fun edit(
        taskId: String,
        vararg overrides: Pair<String, String>,
    ) = mvc.adminJson("PUT", admin, "/api/admin/tasks/$taskId", taskJson(sourceId, *overrides)).andExpect { status { isOk() } }

    fun mission(
        ids: List<String>,
        topic: UUID = Topics.DECIMALS,
    ): String {
        val body = """{"topicId":"$topic","position":${++position},"taskIds":${ids.joinToString(",", "[", "]") { "\"$it\"" }}}"""
        val id: String =
            JsonPath.read(
                mvc
                    .adminJson("POST", admin, "/api/admin/missions", body)
                    .andExpect { status { isCreated() } }
                    .andReturn()
                    .response.contentAsString,
                "$.missionId",
            )
        mvc.adminJson("PUT", admin, "/api/admin/missions/$id/status", """{"status":"PUBLISHED"}""").andExpect { status { isOk() } }
        return id
    }

    // ---------------------------------------------------------------- child practice

    fun attempt(
        m: String,
        t: String,
        answers: String,
    ) = mvc.post("/api/child/missions/$m/tasks/$t/attempts") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"answers":$answers}"""
    }

    fun graded(
        m: String,
        t: String,
        answers: String,
    ) = read(attempt(m, t, answers).andExpect { status { isOk() } })

    fun answer(
        m: String,
        t: String,
        value: String,
    ) = graded(m, t, """{"a":"$value"}""")

    fun solve(
        m: String,
        t: String,
    ) = answer(m, t, "12")

    fun wrong(
        m: String,
        t: String,
    ) = answer(m, t, "1")

    fun reveal(
        m: String,
        t: String,
    ) {
        wrong(m, t)
        wrong(m, t)
    }

    fun openHints(
        m: String,
        t: String,
        n: Int,
    ) = repeat(n) { mvc.post("/api/child/missions/$m/tasks/$t/hints").andExpect { status { isOk() } } }

    fun replay(m: String) = read(mvc.post("/api/child/missions/$m/replay").andExpect { status { isOk() } })

    /** Files a report on the task and returns its id. */
    fun report(
        m: String,
        t: String,
        reason: String = "ANSWER_IS_CORRECT",
        note: String? = null,
    ): String {
        mvc
            .post("/api/child/missions/$m/tasks/$t/reports") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"reason":"$reason"${note?.let { ",\"note\":\"$it\"" } ?: ""}}"""
            }.andExpect { status { isCreated() } }
        return jdbc.queryForObject("SELECT id::text FROM problem_report ORDER BY created_at DESC LIMIT 1", String::class.java)!!
    }

    fun hq() = read(mvc.get("/api/child/hq").andExpect { status { isOk() } })

    fun total(): Int = hq().read("$.totalXp")

    // ---------------------------------------------------------------- admin stats

    fun stats(vararg params: Pair<String, String>) = mvc.adminGet(admin, "/api/admin/stats", *params)

    fun statsOk(vararg params: Pair<String, String>) = read(stats(*params).andExpect { status { isOk() } })

    fun reports(vararg params: Pair<String, String>) = mvc.adminGet(admin, "/api/admin/reports", *params)

    fun reportsOk(vararg params: Pair<String, String>) = read(reports(*params).andExpect { status { isOk() } })

    fun post(
        path: String,
        session: Boolean = true,
        csrf: Boolean = true,
    ) = mvc.post(path) {
        if (session) this.session = admin.session
        cookie(Cookie("XSRF-TOKEN", admin.xsrf))
        if (csrf) header("X-XSRF-TOKEN", admin.xsrf)
    }

    fun reevaluate(
        reportId: String,
        session: Boolean = true,
        csrf: Boolean = true,
    ) = post("/api/admin/reports/$reportId/reevaluate", session, csrf)

    fun reevaluateOk(reportId: String) = read(reevaluate(reportId).andExpect { status { isOk() } })

    fun dismiss(
        reportId: String,
        session: Boolean = true,
        csrf: Boolean = true,
    ) = post("/api/admin/reports/$reportId/dismiss", session, csrf)

    fun status(reportId: String): String =
        jdbc.queryForObject("SELECT status FROM problem_report WHERE id = ?::uuid", String::class.java, reportId)!!

    fun ledgerTotal(): Int = jdbc.queryForObject("SELECT coalesce(sum(amount), 0) FROM xp_award", Int::class.java)!!

    /** Raises the total to [target] with bonus rows of missions that do not exist (XP credited outside a session). */
    fun seedTo(target: Int) {
        val left = target - ledgerTotal()
        if (left > 0) {
            jdbc.update(
                "INSERT INTO xp_award (id, kind, mission_id, amount, created_at) VALUES (?, 'MISSION_BONUS', ?, ?, now())",
                UUID.randomUUID(),
                UUID.randomUUID(),
                left,
            )
        }
    }

    // ---------------------------------------------------------------- the log at chosen times

    /**
     * A logged attempt at [at] in a replay scope of its own (replays count in sessions like any attempt), so
     * the same task can appear in many sessions.
     */
    fun logAttempt(
        taskId: String,
        at: Instant,
        no: Int = 1,
        correct: Boolean = true,
    ) {
        val verdict = if (correct) "CORRECT" else "WRONG"
        jdbc.update(
            "INSERT INTO attempt (id, task_id, mission_id, scope, replay, attempt_no, hints_used, correct, parts, created_at) " +
                "VALUES (?, ?::uuid, ?, ?, true, ?, 0, ?, ?::jsonb, ?)",
            UUID.randomUUID(),
            taskId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            no,
            correct,
            """[{"key":"a","raw":"12","normalized":"12","verdict":"$verdict","locked":false}]""",
            Timestamp.from(at),
        )
    }

    fun logHint(
        taskId: String,
        at: Instant,
    ) {
        jdbc.update(
            "INSERT INTO opened_hint (task_id, scope, hint_index, mission_id, opened_at) VALUES (?::uuid, ?, 1, ?, ?)",
            taskId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            Timestamp.from(at),
        )
    }

    /** [count] sessions of the [tasks] (one attempt each, a minute apart) on consecutive days of the week of [monday], from 16:00. */
    fun logSessions(
        monday: LocalDate,
        count: Int,
        tasks: List<String>,
    ) {
        repeat(count) { day ->
            val start = prague(monday.plusDays(day.toLong()).atTime(16, 0))
            tasks.forEachIndexed { i, t -> logAttempt(t, start.plusSeconds(60L * i)) }
        }
    }

    companion object {
        val PRAGUE: ZoneId = ZoneId.of("Europe/Prague")

        fun prague(local: LocalDateTime): Instant = local.atZone(PRAGUE).toInstant()

        /** The Monday of the current Europe/Prague week. */
        fun currentMonday(): LocalDate = LocalDate.now(PRAGUE).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    }
}
