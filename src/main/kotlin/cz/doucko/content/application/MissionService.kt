package cz.doucko.content.application

import cz.doucko.content.ContentUsage
import cz.doucko.content.domain.In
import cz.doucko.content.domain.Mission
import cz.doucko.content.domain.MissionContent
import cz.doucko.content.domain.MissionContext
import cz.doucko.content.domain.MissionDraft
import cz.doucko.content.domain.MissionRules
import cz.doucko.content.domain.PublishStatus
import cz.doucko.content.domain.Task
import cz.doucko.content.domain.Validation
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** A mission with what practice knows about its tasks (contract `AdminMission.hasAttempts`, `deletable`). */
data class MissionView(
    val mission: Mission,
    val hasAttempts: Boolean,
    val deletable: Boolean,
)

/**
 * Missions (store spec content-authoring "Missions", "Mission publishing rules", "Edit existing content",
 * "Delete only unused content"; FR-9, FR-55, FR-56, FR-57). Every write takes the content lock first
 * and reads after it (design decision 1); the rules are [MissionRules]. A refused write stores nothing.
 */
@Service
class MissionService(
    private val missions: MissionStore,
    private val tasks: TaskStore,
    private val topics: TopicStore,
    private val lock: ContentLock,
    private val conflicts: Conflicts,
    private val usageProvider: ObjectProvider<ContentUsage>,
) {
    private val usage: ContentUsage get() = usageProvider.getIfAvailable { ContentUsage.NONE }

    fun list(): List<MissionView> = missions.list().map(::view)

    fun get(id: UUID): MissionView = view(find(id))

    @Transactional
    fun create(draft: MissionDraft): MissionView {
        lock.exclusive()
        val mission = Mission(UUID.randomUUID(), validate(draft, null), PublishStatus.DRAFT)
        missions.insert(mission)
        return view(mission)
    }

    @Transactional
    fun update(
        id: UUID,
        draft: MissionDraft,
    ): MissionView {
        lock.exclusive()
        val stored = find(id)
        val topicId = draft.topicId.orNull()
        if (topicId != null && topics.find(topicId) != null) {
            MissionRules.topicChange(stored, topicId)?.let { throw conflicts.of(it, stored) }
        }
        val content = validate(draft, stored)
        MissionRules.edit(stored, content, taskMap(stored.content.taskIds + content.taskIds))?.let { throw conflicts.of(it, stored) }
        val updated = stored.copy(content = content)
        missions.update(updated)
        return view(updated)
    }

    @Transactional
    fun setStatus(
        id: UUID,
        status: PublishStatus,
    ): MissionView {
        lock.exclusive()
        val stored = find(id)
        if (status == PublishStatus.PUBLISHED && stored.status != PublishStatus.PUBLISHED) {
            val list = stored.content.taskIds.mapNotNull(tasks::find)
            MissionRules.publish(list)?.let { throw conflicts.of(it, stored) }
        }
        if (stored.status != status) missions.setStatus(id, status)
        return view(stored.copy(status = status))
    }

    @Transactional
    fun delete(id: UUID) {
        lock.exclusive()
        val stored = find(id)
        MissionRules.missionDelete(usage.usedTasks(stored.content.taskIds))?.let { throw conflicts.of(it, stored) }
        missions.delete(id)
    }

    private fun find(id: UUID) = missions.find(id) ?: throw ContentNotFound()

    private fun taskMap(ids: Collection<UUID>): Map<UUID, Task> = ids.distinct().mapNotNull(tasks::find).associateBy { it.id }

    private fun validate(
        draft: MissionDraft,
        stored: Mission?,
    ): MissionContent {
        val topic = draft.topicId.orNull()?.let(topics::find)
        val ids = (draft.taskIds as? In.Ok)?.value.orEmpty().mapNotNull { it.orNull() }
        val context =
            MissionContext(
                topic = topic,
                takenPositions = topic?.let { missions.positionsTaken(it.id, stored?.id) }.orEmpty(),
                tasks = taskMap(ids),
                topics = topics.all().associateBy { it.id },
            )
        return when (val result = MissionRules.validate(draft, context)) {
            is Validation.Invalid -> throw InvalidContent(result.errors)
            is Validation.Valid -> result.value
        }
    }

    private fun view(mission: Mission): MissionView {
        val ids = mission.content.taskIds
        return MissionView(
            mission,
            hasAttempts = usage.attemptCounts(ids).values.any { it > 0 },
            deletable = MissionRules.missionDelete(usage.usedTasks(ids)) == null,
        )
    }
}
