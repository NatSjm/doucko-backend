package cz.doucko.content

import cz.doucko.checking.AnswerSpec
import java.util.UUID

/** A topic for the practice statistics: its name and every `TEXT` task in it, whatever the task's status. */
data class CatalogTopic(
    val topicId: UUID,
    val name: String,
    val taskIds: List<UUID>,
)

/** An answer part as it is now: its key and the checker's view of its current spec. */
data class CatalogPart(
    val key: String,
    val spec: AnswerSpec,
)

/** A task as it is now, whatever its status: its Czech prompt, its topic and its current parts in order. */
data class CatalogTask(
    val taskId: UUID,
    val topicId: UUID,
    val prompt: String,
    val parts: List<CatalogPart>,
)

/**
 * What the admin's statistics and flag queue read of content (store spec progress-stats; FR-40, FR-41).
 * Public API of the content module, consumed by practice (slice 9). Unlike [ServedContent] it does not
 * filter by publishing status: a reported task may be a draft while the admin fixes its answer spec.
 */
interface ContentCatalog {
    /** Every topic with at least one `PUBLISHED` `TEXT` task, in topic order, with all of its `TEXT` tasks. */
    fun topics(): List<CatalogTopic>

    /** The task with this id, or null when it does not exist. */
    fun task(taskId: UUID): CatalogTask?

    /** The Czech prompts of the existing tasks among [taskIds], in one lookup (the flag queue). */
    fun prompts(taskIds: Collection<UUID>): Map<UUID, String>
}
