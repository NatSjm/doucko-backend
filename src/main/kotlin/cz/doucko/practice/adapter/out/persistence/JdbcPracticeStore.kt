package cz.doucko.practice.adapter.out.persistence

import cz.doucko.checking.Verdict
import cz.doucko.practice.application.PracticeStore
import cz.doucko.practice.domain.AttemptRecord
import cz.doucko.practice.domain.HintRecord
import cz.doucko.practice.domain.PartRecord
import cz.doucko.practice.domain.RawAnswer
import cz.doucko.practice.domain.Reevaluation
import cz.doucko.practice.domain.ReportReason
import cz.doucko.practice.domain.ReportRecord
import cz.doucko.practice.domain.ReportStatus
import cz.doucko.practice.domain.RunRecord
import org.springframework.jdbc.core.SqlParameterValue
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.JsonNodeFactory
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
import java.util.UUID

/**
 * [PracticeStore] on Postgres (`V4__practice.sql`, `V6__progress_stats.sql`). Insert-only (FR-42), except that a replay run is
 * marked finished once and an `OPEN` report is closed once (FR-41). Attempts are read with their re-evaluation. Attempt parts are one JSONB
 * array: `key`, `raw` (string, array of option keys, or null for a locked part), `normalized`, `verdict`,
 * `locked`. Values are always bound parameters; nothing here logs (NFR-3).
 */
@Repository
class JdbcPracticeStore(
    private val jdbc: JdbcClient,
    private val json: JsonMapper,
) : PracticeStore {
    override fun lock() {
        jdbc
            .sql("SELECT pg_advisory_xact_lock(?)")
            .param(KEY)
            .query { _, _ -> Unit }
            .list()
    }

    override fun attempts(
        scope: UUID,
        taskIds: Collection<UUID>,
    ): List<AttemptRecord> {
        if (taskIds.isEmpty()) return emptyList()
        return jdbc
            .sql(
                "$ATTEMPTS WHERE a.scope = :scope AND a.task_id IN (:ids) ORDER BY a.created_at, a.attempt_no",
            ).param("scope", scope)
            .param("ids", taskIds.distinct())
            .query { rs, _ -> attempt(rs) }
            .list()
    }

    override fun attemptsIn(scope: UUID): List<AttemptRecord> =
        jdbc
            .sql(
                "$ATTEMPTS WHERE a.scope = :scope ORDER BY a.created_at, a.attempt_no",
            ).param("scope", scope)
            .query { rs, _ -> attempt(rs) }
            .list()

    override fun hints(
        scope: UUID,
        taskIds: Collection<UUID>,
    ): List<HintRecord> {
        if (taskIds.isEmpty()) return emptyList()
        return jdbc
            .sql(
                "SELECT task_id, scope, hint_index, mission_id, opened_at FROM opened_hint WHERE scope = :scope AND task_id IN (:ids) ORDER BY hint_index",
            ).param("scope", scope)
            .param("ids", taskIds.distinct())
            .query { rs, _ ->
                HintRecord(
                    rs.getObject("task_id", UUID::class.java),
                    rs.getObject("scope", UUID::class.java),
                    rs.getInt("hint_index"),
                    rs.getObject("mission_id", UUID::class.java),
                    rs.getTimestamp("opened_at").toInstant(),
                )
            }.list()
    }

    override fun insertAttempt(attempt: AttemptRecord) {
        jdbc
            .sql(
                """
                INSERT INTO attempt (id, task_id, mission_id, scope, replay, attempt_no, hints_used, correct, parts, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """.trimIndent(),
            ).params(
                attempt.id,
                attempt.taskId,
                attempt.missionId,
                attempt.scope,
                attempt.replay,
                attempt.attemptNo,
                attempt.hintsUsed,
                attempt.correct,
                json.writeValueAsString(parts(attempt.parts)),
                Timestamp.from(attempt.createdAt),
            ).update()
    }

    override fun insertHint(hint: HintRecord) {
        jdbc
            .sql("INSERT INTO opened_hint (task_id, scope, hint_index, mission_id, opened_at) VALUES (?, ?, ?, ?, ?)")
            .params(hint.taskId, hint.scope, hint.index, hint.missionId, Timestamp.from(hint.openedAt))
            .update()
    }

    override fun insertReport(report: ReportRecord) {
        jdbc
            .sql(
                "INSERT INTO problem_report (id, task_id, mission_id, reason, note, attempt_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
            ).param(report.id)
            .param(report.taskId)
            .param(report.missionId)
            .param(report.reason.name)
            .param(SqlParameterValue(Types.VARCHAR, report.note))
            .param(SqlParameterValue(Types.OTHER, report.attemptId))
            .param(Timestamp.from(report.createdAt))
            .update()
    }

    override fun runs(missionId: UUID): List<RunRecord> =
        jdbc
            .sql("SELECT id, mission_id, started_at, finished_at FROM practice_run WHERE mission_id = ? ORDER BY started_at, id")
            .param(missionId)
            .query { rs, _ ->
                RunRecord(
                    rs.getObject("id", UUID::class.java),
                    rs.getObject("mission_id", UUID::class.java),
                    rs.getTimestamp("started_at").toInstant(),
                    rs.getTimestamp("finished_at")?.toInstant(),
                )
            }.list()

    override fun insertRun(run: RunRecord) {
        jdbc
            .sql("INSERT INTO practice_run (id, mission_id, started_at) VALUES (?, ?, ?)")
            .params(run.id, run.missionId, Timestamp.from(run.startedAt))
            .update()
    }

    override fun finishRun(
        runId: UUID,
        at: java.time.Instant,
    ) {
        jdbc
            .sql("UPDATE practice_run SET finished_at = ? WHERE id = ? AND finished_at IS NULL")
            .params(Timestamp.from(at), runId)
            .update()
    }

    override fun attemptCounts(taskIds: Collection<UUID>): Map<UUID, Int> =
        jdbc
            .sql("SELECT task_id, count(*) AS n FROM attempt WHERE task_id IN (:ids) GROUP BY task_id")
            .param("ids", taskIds.distinct())
            .query { rs, _ -> rs.getObject("task_id", UUID::class.java) to rs.getInt("n") }
            .list()
            .toMap()

    override fun usedTasks(taskIds: Collection<UUID>): Set<UUID> =
        jdbc
            .sql(
                """
                SELECT task_id FROM attempt WHERE task_id IN (:ids)
                UNION SELECT task_id FROM opened_hint WHERE task_id IN (:ids)
                UNION SELECT task_id FROM problem_report WHERE task_id IN (:ids)
                """.trimIndent(),
            ).param("ids", taskIds.distinct())
            .query(UUID::class.java)
            .list()
            .filterNotNull()
            .toSet()

    override fun allAttempts(): List<AttemptRecord> =
        jdbc
            .sql("$ATTEMPTS ORDER BY a.created_at, a.id")
            .query { rs, _ -> attempt(rs) }
            .list()

    override fun allHints(): List<HintRecord> =
        jdbc
            .sql("SELECT task_id, scope, hint_index, mission_id, opened_at FROM opened_hint ORDER BY opened_at")
            .query { rs, _ -> hint(rs) }
            .list()

    override fun attempt(id: UUID): AttemptRecord? =
        jdbc
            .sql("$ATTEMPTS WHERE a.id = ?")
            .param(id)
            .query { rs, _ -> attempt(rs) }
            .optional()
            .orElse(null)

    override fun reports(status: ReportStatus?): List<ReportRecord> =
        jdbc
            .sql(
                """
                $REPORTS WHERE (CAST(:status AS text) IS NULL OR status = CAST(:status AS text))
                ORDER BY (status <> 'OPEN'), created_at, id
                """.trimIndent(),
            ).param("status", SqlParameterValue(Types.VARCHAR, status?.name))
            .query { rs, _ -> report(rs) }
            .list()

    override fun report(id: UUID): ReportRecord? =
        jdbc
            .sql("$REPORTS WHERE id = ?")
            .param(id)
            .query { rs, _ -> report(rs) }
            .optional()
            .orElse(null)

    override fun insertReevaluation(
        attemptId: UUID,
        reportId: UUID,
        reevaluation: Reevaluation,
    ) {
        val verdicts = JsonNodeFactory.instance.objectNode().apply { reevaluation.verdicts.forEach { (key, v) -> put(key, v.name) } }
        jdbc
            .sql(
                "INSERT INTO attempt_reevaluation (attempt_id, report_id, verdicts, correct, reevaluated_at) VALUES (?, ?, ?::jsonb, ?, ?)",
            ).params(attemptId, reportId, json.writeValueAsString(verdicts), reevaluation.correct, Timestamp.from(reevaluation.at))
            .update()
    }

    override fun closeReport(
        reportId: UUID,
        status: ReportStatus,
    ): Boolean =
        jdbc
            .sql("UPDATE problem_report SET status = ? WHERE id = ? AND status = 'OPEN'")
            .params(status.name, reportId)
            .update() == 1

    private fun hint(rs: ResultSet) =
        HintRecord(
            rs.getObject("task_id", UUID::class.java),
            rs.getObject("scope", UUID::class.java),
            rs.getInt("hint_index"),
            rs.getObject("mission_id", UUID::class.java),
            rs.getTimestamp("opened_at").toInstant(),
        )

    private fun report(rs: ResultSet) =
        ReportRecord(
            id = rs.getObject("id", UUID::class.java),
            taskId = rs.getObject("task_id", UUID::class.java),
            missionId = rs.getObject("mission_id", UUID::class.java),
            reason = ReportReason.valueOf(rs.getString("reason")),
            note = rs.getString("note"),
            attemptId = rs.getObject("attempt_id", UUID::class.java),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            status = ReportStatus.valueOf(rs.getString("status")),
        )

    private fun attempt(rs: ResultSet) =
        AttemptRecord(
            id = rs.getObject("id", UUID::class.java),
            taskId = rs.getObject("task_id", UUID::class.java),
            missionId = rs.getObject("mission_id", UUID::class.java),
            scope = rs.getObject("scope", UUID::class.java),
            replay = rs.getBoolean("replay"),
            attemptNo = rs.getInt("attempt_no"),
            hintsUsed = rs.getInt("hints_used"),
            correct = rs.getBoolean("correct"),
            parts = json.readTree(rs.getString("parts")).values().map(::part),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            reevaluation =
                rs.getString("re_verdicts")?.let { verdicts ->
                    Reevaluation(
                        json.readTree(verdicts).properties().associate { (key, value) -> key to Verdict.valueOf(value.asString()) },
                        rs.getBoolean("re_correct"),
                        rs.getTimestamp("re_at").toInstant(),
                    )
                },
        )

    private fun parts(parts: List<PartRecord>): JsonNode {
        val nodes = JsonNodeFactory.instance
        return nodes.arrayNode().apply {
            parts.forEach { part ->
                add(
                    nodes.objectNode().apply {
                        put("key", part.key)
                        when (val raw = part.raw) {
                            null -> putNull("raw")
                            is RawAnswer.Text -> put("raw", raw.value)
                            is RawAnswer.Keys -> putArray("raw").apply { raw.keys.forEach(::add) }
                        }
                        if (part.normalized == null) putNull("normalized") else put("normalized", part.normalized)
                        put("verdict", part.verdict.name)
                        put("locked", part.locked)
                    },
                )
            }
        }
    }

    private fun part(node: JsonNode): PartRecord {
        val raw = node.get("raw")
        return PartRecord(
            key = node.get("key").asString(),
            raw =
                when {
                    raw.isNull -> null
                    raw.isArray -> RawAnswer.Keys(raw.values().map { it.asString() })
                    else -> RawAnswer.Text(raw.asString())
                },
            normalized = node.get("normalized").let { if (it.isNull) null else it.asString() },
            verdict = Verdict.valueOf(node.get("verdict").asString()),
            locked = node.get("locked").asBoolean(),
        )
    }

    private companion object {
        /** Arbitrary constant identifying the practice lock ("doucko-p" in ASCII; design decision 3). */
        const val KEY = 0x646F75636B6F2D70L

        /** An attempt with its re-evaluation, if any (`V6__progress_stats.sql`). */
        const val ATTEMPTS =
            "SELECT a.id, a.task_id, a.mission_id, a.scope, a.replay, a.attempt_no, a.hints_used, a.correct, " +
                "a.parts::text AS parts, a.created_at, r.verdicts::text AS re_verdicts, r.correct AS re_correct, " +
                "r.reevaluated_at AS re_at FROM attempt a LEFT JOIN attempt_reevaluation r ON r.attempt_id = a.id"

        const val REPORTS = "SELECT id, task_id, mission_id, reason, note, attempt_id, created_at, status FROM problem_report"
    }
}
