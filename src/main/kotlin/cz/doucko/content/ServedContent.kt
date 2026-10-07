package cz.doucko.content

import cz.doucko.checking.AnswerSpec
import java.util.UUID

/** A published mission as the child may see it: its topic, its HQ position and its tasks in order. */
data class ServedMission(
    val missionId: UUID,
    val topicId: UUID,
    val position: Int,
    val taskIds: List<UUID>,
    val topicName: String = "",
)

/** One choice option of a served part: key `A`, `B`, … in display order. */
data class ServedOption(
    val key: String,
    val text: String,
)

/**
 * One answer part of a served task: what the child sees (key, label, unit, options) and the checker's view
 * of its spec. [spec] is the answer key: practice may use it only to grade and, after the reveal, to show
 * the first accepted answer (FR-27).
 */
data class ServedPart(
    val key: String,
    val label: String,
    val spec: AnswerSpec,
    val unit: String?,
    val options: List<ServedOption>,
)

/**
 * The translation of a served task into one configured task locale (FR-8, FR-29): a missing text falls back
 * to Czech. [options] is keyed `<partKey>.<optionKey>`.
 */
data class ServedTranslation(
    val prompt: String?,
    val partLabels: Map<String, String>,
    val hints: List<String?>,
    val options: Map<String, String>,
)

/**
 * A task the child may be served (FR-12, FR-26): its Czech prompt, parts, hints in order, the non-empty
 * translations in configured task locales and its image ids in display order. Never its source reference.
 */
data class ServedTask(
    val taskId: UUID,
    val prompt: String,
    val parts: List<ServedPart>,
    val hints: List<String>,
    val translations: Map<String, ServedTranslation>,
    val imageIds: List<UUID>,
)

/** The bytes of an image of a served task. */
class ServedImage(
    val mediaType: String,
    val content: ByteArray,
)

/**
 * What the child may be served (store spec content-authoring "Draft and published status",
 * "Graphic tasks are never served in MVP"; FR-12, FR-5). Public API of the content module, consumed by
 * practice (slice 7): only `PUBLISHED` `TEXT` tasks inside `PUBLISHED`, non-empty missions.
 */
interface ServedContent {
    /** Every served mission, by topic order and then position. */
    fun missions(): List<ServedMission>

    /** The served missions that contain [taskId] (gamification: completion on a task's first resolution, OQ-15). */
    fun missionsContaining(taskId: UUID): List<ServedMission>

    /** The served mission with this id, or null when it is a draft, empty or unknown. */
    fun mission(missionId: UUID): ServedMission?

    /** True when the task is `PUBLISHED`, `TEXT` and in at least one `PUBLISHED` mission. */
    fun isServed(taskId: UUID): Boolean

    /** The served task with this id, or null when [isServed] is false. */
    fun task(taskId: UUID): ServedTask?

    /** An image of a served task, or null when the image is unknown or its task is not served. */
    fun image(imageId: UUID): ServedImage?
}
