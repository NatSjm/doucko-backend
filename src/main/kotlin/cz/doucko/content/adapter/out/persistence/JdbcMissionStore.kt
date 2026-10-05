package cz.doucko.content.adapter.out.persistence

import cz.doucko.content.application.ContentLock
import cz.doucko.content.application.MissionStore
import cz.doucko.content.domain.Mission
import cz.doucko.content.domain.MissionContent
import cz.doucko.content.domain.PublishStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

/**
 * Missions (V3): one `mission` row and the ordered `mission_task` rows. A task list is rewritten as a
 * whole on save; the `(mission_id, position)` constraint is deferred to the commit, so positions may
 * move within the transaction.
 */
@Repository
class JdbcMissionStore(
    private val jdbc: JdbcClient,
) : MissionStore {
    override fun list(): List<Mission> = withTasks(jdbc.sql("$SELECT ORDER BY t.grade, t.position, m.position").query(::row).list())

    override fun find(id: UUID): Mission? =
        withTasks(
            jdbc
                .sql("$SELECT WHERE m.id = ?")
                .param(id)
                .query(::row)
                .list(),
        ).firstOrNull()

    override fun insert(mission: Mission) {
        jdbc
            .sql("INSERT INTO mission (id, topic_id, position, status) VALUES (?, ?, ?, ?)")
            .params(mission.id, mission.content.topicId, mission.content.position, mission.status.name)
            .update()
        writeTasks(mission.id, mission.content.taskIds)
    }

    override fun update(mission: Mission) {
        jdbc
            .sql("UPDATE mission SET topic_id = ?, position = ?, updated_at = now() WHERE id = ?")
            .params(mission.content.topicId, mission.content.position, mission.id)
            .update()
        jdbc.sql("DELETE FROM mission_task WHERE mission_id = ?").param(mission.id).update()
        writeTasks(mission.id, mission.content.taskIds)
    }

    override fun setStatus(
        id: UUID,
        status: PublishStatus,
    ) {
        jdbc
            .sql("UPDATE mission SET status = ?, updated_at = now() WHERE id = ?")
            .params(status.name, id)
            .update()
    }

    override fun delete(id: UUID) {
        jdbc.sql("DELETE FROM mission WHERE id = ?").param(id).update()
    }

    override fun containing(taskId: UUID): List<Mission> =
        withTasks(
            jdbc
                .sql(
                    "$SELECT WHERE m.id IN (SELECT mission_id FROM mission_task WHERE task_id = ?) ORDER BY t.grade, t.position, m.position",
                ).param(taskId)
                .query(::row)
                .list(),
        )

    override fun positionsTaken(
        topicId: UUID,
        excluding: UUID?,
    ): Set<Int> =
        jdbc
            .sql("SELECT id, position FROM mission WHERE topic_id = ?")
            .param(topicId)
            .query { rs, _ -> rs.getObject("id", UUID::class.java) to rs.getInt("position") }
            .list()
            .filter { it.first != excluding }
            .map { it.second }
            .toSet()

    override fun removeTask(taskId: UUID) {
        val affected =
            jdbc
                .sql("DELETE FROM mission_task WHERE task_id = ? RETURNING mission_id")
                .param(taskId)
                .query(UUID::class.java)
                .list()
                .filterNotNull()
        for (missionId in affected.distinct()) {
            jdbc
                .sql(
                    """
                    UPDATE mission_task mt SET position = r.rn
                    FROM (SELECT task_id, row_number() OVER (ORDER BY position) AS rn FROM mission_task WHERE mission_id = ?) r
                    WHERE mt.mission_id = ? AND mt.task_id = r.task_id
                    """.trimIndent(),
                ).params(missionId, missionId)
                .update()
            jdbc.sql("UPDATE mission SET updated_at = now() WHERE id = ?").param(missionId).update()
        }
    }

    private fun writeTasks(
        missionId: UUID,
        taskIds: List<UUID>,
    ) {
        taskIds.forEachIndexed { index, taskId ->
            jdbc
                .sql("INSERT INTO mission_task (mission_id, task_id, position) VALUES (?, ?, ?)")
                .params(missionId, taskId, index + 1)
                .update()
        }
    }

    private fun withTasks(missions: List<Mission>): List<Mission> {
        if (missions.isEmpty()) return missions
        val tasks =
            jdbc
                .sql("SELECT mission_id, task_id FROM mission_task WHERE mission_id = ANY(?) ORDER BY mission_id, position")
                .param(missions.map { it.id }.toTypedArray())
                .query { rs, _ -> rs.getObject("mission_id", UUID::class.java) to rs.getObject("task_id", UUID::class.java) }
                .list()
                .groupBy({ it.first }, { it.second })
        return missions.map { it.copy(content = it.content.copy(taskIds = tasks[it.id].orEmpty())) }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun row(
        rs: ResultSet,
        row: Int,
    ) = Mission(
        id = rs.getObject("id", UUID::class.java),
        content = MissionContent(rs.getObject("topic_id", UUID::class.java), rs.getInt("position"), emptyList()),
        status = PublishStatus.valueOf(rs.getString("status")),
    )

    private companion object {
        const val SELECT = "SELECT m.id, m.topic_id, m.position, m.status FROM mission m JOIN topic t ON t.id = m.topic_id"
    }
}

/** The content lock as a transaction-scoped Postgres advisory lock on one fixed key (design decision 1). */
@Repository
class JdbcContentLock(
    private val jdbc: JdbcClient,
) : ContentLock {
    override fun exclusive() {
        jdbc
            .sql("SELECT pg_advisory_xact_lock(?)")
            .param(KEY)
            .query { _, _ -> Unit }
            .list()
    }

    private companion object {
        /** Arbitrary constant identifying the content structure lock ("doucko-c" in ASCII). */
        const val KEY = 0x646F75636B6F2D63L
    }
}
