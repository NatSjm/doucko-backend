package cz.doucko.content

import java.util.UUID

/** A published mission as the child may see it: its topic, its HQ position and its tasks in order. */
data class ServedMission(
    val missionId: UUID,
    val topicId: UUID,
    val position: Int,
    val taskIds: List<UUID>,
)

/**
 * What the child may be served (store spec content-authoring "Draft and published status",
 * "Graphic tasks are never served in MVP"; FR-12, FR-5). Public API of the content module, consumed by
 * practice (slice 7): only `PUBLISHED` `TEXT` tasks inside `PUBLISHED`, non-empty missions.
 */
interface ServedContent {
    /** Every served mission, by topic order and then position. */
    fun missions(): List<ServedMission>

    /** The served mission with this id, or null when it is a draft, empty or unknown. */
    fun mission(missionId: UUID): ServedMission?

    /** True when the task is `PUBLISHED`, `TEXT` and in at least one `PUBLISHED` mission. */
    fun isServed(taskId: UUID): Boolean
}
