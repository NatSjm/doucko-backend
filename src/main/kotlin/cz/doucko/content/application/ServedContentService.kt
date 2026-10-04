package cz.doucko.content.application

import cz.doucko.content.ServedContent
import cz.doucko.content.ServedMission
import cz.doucko.content.domain.Mission
import cz.doucko.content.domain.Modality
import cz.doucko.content.domain.PublishStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * The child-servable content (FR-12, FR-5; design decision 3): `PUBLISHED`, non-empty missions and the
 * `PUBLISHED` `TEXT` tasks in them. Status and modality are re-checked here although the publishing
 * rules already keep them true, so a broken invariant can never serve a draft or graphic task.
 */
@Service
@Transactional(readOnly = true)
class ServedContentService(
    private val missions: MissionStore,
    private val tasks: TaskStore,
) : ServedContent {
    override fun missions(): List<ServedMission> = missions.list().mapNotNull(::served)

    override fun mission(missionId: UUID): ServedMission? = missions.find(missionId)?.let(::served)

    override fun isServed(taskId: UUID): Boolean =
        servable(taskId) && missions.containing(taskId).any { it.status == PublishStatus.PUBLISHED }

    private fun servable(taskId: UUID): Boolean =
        tasks.find(taskId)?.let { it.status == PublishStatus.PUBLISHED && it.content.modality == Modality.TEXT } == true

    private fun served(mission: Mission): ServedMission? {
        if (mission.status != PublishStatus.PUBLISHED) return null
        val ids = mission.content.taskIds.filter(::servable)
        if (ids.isEmpty()) return null
        return ServedMission(mission.id, mission.content.topicId, mission.content.position, ids)
    }
}
