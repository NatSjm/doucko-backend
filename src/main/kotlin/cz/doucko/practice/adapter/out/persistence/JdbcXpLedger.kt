package cz.doucko.practice.adapter.out.persistence

import cz.doucko.practice.application.XpLedger
import cz.doucko.practice.domain.AwardKind
import cz.doucko.practice.domain.XpAward
import org.springframework.jdbc.core.SqlParameterValue
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.util.UUID

/**
 * [XpLedger] on Postgres (`V5__gamification.sql`, `V6__progress_stats.sql`): insert-only. The database assigns `seq` (an inserted
 * award's own `seq` is ignored) and refuses any update or delete of an award (FR-38).
 */
@Repository
class JdbcXpLedger(
    private val jdbc: JdbcClient,
) : XpLedger {
    override fun awards(): List<XpAward> =
        jdbc
            .sql("$SELECT ORDER BY seq")
            .query { rs, _ -> row(rs) }
            .list()

    override fun awardsOf(attemptId: UUID): List<XpAward> =
        jdbc
            .sql("$SELECT WHERE attempt_id = ? ORDER BY seq")
            .param(attemptId)
            .query { rs, _ -> row(rs) }
            .list()

    override fun total(): Int =
        jdbc
            .sql("SELECT coalesce(sum(amount), 0) FROM xp_award")
            .query(Int::class.java)
            .single()

    override fun taskAwarded(taskId: UUID): Boolean =
        jdbc
            .sql("SELECT count(*) FROM xp_award WHERE kind IN ('TASK', 'REEVALUATION') AND task_id = ?")
            .param(taskId)
            .query(Int::class.java)
            .single() > 0

    override fun taskXp(taskId: UUID): Int =
        jdbc
            .sql("SELECT coalesce(sum(amount), 0) FROM xp_award WHERE kind IN ('TASK', 'REEVALUATION') AND task_id = ?")
            .param(taskId)
            .query(Int::class.java)
            .single()

    override fun bonusesPaid(missionIds: Collection<UUID>): Set<UUID> =
        if (missionIds.isEmpty()) {
            emptySet()
        } else {
            jdbc
                .sql("SELECT mission_id FROM xp_award WHERE kind = 'MISSION_BONUS' AND mission_id IN (:ids)")
                .param("ids", missionIds.distinct())
                .query(UUID::class.java)
                .list()
                .filterNotNull()
                .toSet()
        }

    override fun insert(award: XpAward) {
        jdbc
            .sql(
                "INSERT INTO xp_award (id, kind, task_id, mission_id, attempt_id, amount, created_at, report_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            ).param(award.id)
            .param(award.kind.name)
            .param(SqlParameterValue(Types.OTHER, award.taskId))
            .param(award.missionId)
            .param(SqlParameterValue(Types.OTHER, award.attemptId))
            .param(award.amount)
            .param(Timestamp.from(award.createdAt))
            .param(SqlParameterValue(Types.OTHER, award.reportId))
            .update()
    }

    override fun celebrated(): Int =
        jdbc
            .sql("SELECT coalesce(max(rank_index), 0) FROM rank_celebration")
            .query(Int::class.java)
            .single()

    override fun celebrate(
        index: Int,
        at: Instant,
    ) {
        jdbc
            .sql("INSERT INTO rank_celebration (rank_index, celebrated_at) VALUES (?, ?)")
            .params(index, Timestamp.from(at))
            .update()
    }

    private fun row(rs: ResultSet) =
        XpAward(
            seq = rs.getLong("seq"),
            id = rs.getObject("id", UUID::class.java),
            kind = AwardKind.valueOf(rs.getString("kind")),
            taskId = rs.getObject("task_id", UUID::class.java),
            missionId = rs.getObject("mission_id", UUID::class.java),
            attemptId = rs.getObject("attempt_id", UUID::class.java),
            amount = rs.getInt("amount"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            reportId = rs.getObject("report_id", UUID::class.java),
        )

    private companion object {
        const val SELECT = "SELECT seq, id, kind, task_id, mission_id, attempt_id, amount, created_at, report_id FROM xp_award"
    }
}
