package cz.doucko.content.application

import cz.doucko.content.CatalogPart
import cz.doucko.content.CatalogTask
import cz.doucko.content.CatalogTopic
import cz.doucko.content.ContentCatalog
import cz.doucko.content.domain.Modality
import cz.doucko.content.domain.PublishStatus
import cz.doucko.content.domain.TaskFilter
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** [ContentCatalog] over the content stores (progress-stats design decisions 5 and 7). Read-only. */
@Service
@Transactional(readOnly = true)
class ContentCatalogService(
    private val tasks: TaskStore,
    private val topics: TopicStore,
) : ContentCatalog {
    override fun topics(): List<CatalogTopic> {
        val text = tasks.list(TaskFilter(null, null, null, null, Modality.TEXT, null, null))
        val byTopic = text.groupBy { it.content.topicId }
        return topics.all().mapNotNull { topic ->
            val list = byTopic[topic.id].orEmpty()
            if (list.none { it.status == PublishStatus.PUBLISHED }) null else CatalogTopic(topic.id, topic.name, list.map { it.id })
        }
    }

    override fun prompts(taskIds: Collection<UUID>): Map<UUID, String> = tasks.prompts(taskIds)

    override fun task(taskId: UUID): CatalogTask? =
        tasks.find(taskId)?.let { task ->
            CatalogTask(
                task.id,
                task.content.topicId,
                task.content.prompt,
                task.content.parts.map { CatalogPart(it.key, it.spec.toChecker()) },
            )
        }
}
