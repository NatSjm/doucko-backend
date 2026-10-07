package cz.doucko.content.application

import cz.doucko.content.ServedContent
import cz.doucko.content.ServedImage
import cz.doucko.content.ServedMission
import cz.doucko.content.ServedOption
import cz.doucko.content.ServedPart
import cz.doucko.content.ServedTask
import cz.doucko.content.ServedTranslation
import cz.doucko.content.domain.Mission
import cz.doucko.content.domain.Modality
import cz.doucko.content.domain.PublishStatus
import cz.doucko.content.domain.SpecRules
import cz.doucko.content.domain.Task
import cz.doucko.content.domain.TaskFilter
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
    private val topics: TopicStore,
    private val storage: FileStoragePort,
    private val properties: ContentProperties,
) : ServedContent {
    override fun missions(): List<ServedMission> {
        val servable = servableIds()
        return missions.list().mapNotNull { served(it, servable::contains) }
    }

    override fun missionsContaining(taskId: UUID): List<ServedMission> = missions.containing(taskId).mapNotNull { served(it, ::servable) }

    override fun mission(missionId: UUID): ServedMission? = missions.find(missionId)?.let { served(it, ::servable) }

    override fun isServed(taskId: UUID): Boolean = servable(taskId) && inPublishedMission(taskId)

    override fun task(taskId: UUID): ServedTask? {
        val task = tasks.find(taskId)?.takeIf(::servable) ?: return null
        return if (inPublishedMission(taskId)) served(task) else null
    }

    override fun image(imageId: UUID): ServedImage? {
        val task = tasks.imageOwner(imageId)?.let(tasks::find)?.takeIf(::servable) ?: return null
        if (!inPublishedMission(task.id)) return null
        val image = task.images.firstOrNull { it.id == imageId } ?: return null
        val content = storage.open(image.storageKey)?.use { it.readAllBytes() } ?: return null
        return ServedImage(image.mediaType, content)
    }

    private fun servableIds(): Set<UUID> =
        tasks
            .list(
                TaskFilter(null, null, null, null, Modality.TEXT, PublishStatus.PUBLISHED, null),
            ).filter(::servable)
            .map { it.id }
            .toSet()

    private fun servable(taskId: UUID): Boolean = tasks.find(taskId)?.let(::servable) == true

    private fun servable(task: Task): Boolean = task.status == PublishStatus.PUBLISHED && task.content.modality == Modality.TEXT

    private fun inPublishedMission(taskId: UUID) = missions.containing(taskId).any { it.status == PublishStatus.PUBLISHED }

    private fun served(
        mission: Mission,
        servable: (UUID) -> Boolean,
    ): ServedMission? {
        if (mission.status != PublishStatus.PUBLISHED) return null
        val ids = mission.content.taskIds.filter(servable)
        if (ids.isEmpty()) return null
        val topic = topics.find(mission.content.topicId)?.name.orEmpty()
        return ServedMission(mission.id, mission.content.topicId, mission.content.position, ids, topic)
    }

    private fun served(task: Task): ServedTask {
        val c = task.content
        return ServedTask(
            taskId = task.id,
            prompt = c.prompt,
            parts =
                c.parts.map { part ->
                    ServedPart(
                        key = part.key,
                        label = part.label,
                        spec = part.spec.toChecker(),
                        unit = SpecRules.unit(part.spec),
                        options = part.spec.options.map { ServedOption(it.key, it.text) },
                    )
                },
            hints = c.hints,
            translations =
                c.translations
                    .filter { (locale, translation) -> locale in properties.taskLocales && !translation.isEmpty() }
                    .mapValues { (_, t) -> ServedTranslation(t.prompt, t.partLabels, t.hints, t.options) },
            imageIds = task.images.sortedBy { it.position }.map { it.id },
        )
    }
}
