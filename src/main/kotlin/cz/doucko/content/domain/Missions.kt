package cz.doucko.content.domain

import java.util.UUID

/** A validated mission (contract `MissionInput`): topic, position within the topic, ordered task ids (FR-9, CA-D17). */
data class MissionContent(
    val topicId: UUID,
    val position: Int,
    val taskIds: List<UUID>,
)

/** A stored mission. A mission has no name in MVP (CA-D17). */
data class Mission(
    val id: UUID,
    val content: MissionContent,
    val status: PublishStatus,
)

/** A submitted `MissionInput`. */
data class MissionDraft(
    val topicId: In<UUID>,
    val position: In<Int>,
    val taskIds: In<List<In<UUID>>>,
)

/**
 * What the mission field rules need beyond the draft: the chosen [topic] (null when unknown), the
 * positions other missions of that topic already use, the referenced tasks that exist, and every
 * topic by id (for messages naming a task's topic).
 */
data class MissionContext(
    val topic: Topic?,
    val takenPositions: Set<Int>,
    val tasks: Map<UUID, Task>,
    val topics: Map<UUID, Topic>,
)

/** Why tasks may not be in a published mission (FR-57): [drafts] are not `PUBLISHED`, [graphic] are `GRAPHIC`. */
data class Blockers(
    val drafts: List<Task>,
    val graphic: List<Task>,
) {
    fun isEmpty() = drafts.isEmpty() && graphic.isEmpty()

    /** Every blocking task once, drafts first. */
    fun tasks() = (drafts + graphic).distinctBy { it.id }
}

/** A state rule that refuses a mission or task change (409), with the entities that block it. */
sealed interface Refusal {
    /** Publishing an empty mission (CA-D7). */
    data object EmptyMission : Refusal

    /** Publishing a mission with draft or graphic tasks (FR-57). */
    data class Unpublishable(
        val blockers: Blockers,
    ) : Refusal

    /** Adding draft or graphic tasks to a published mission (CA-D9). */
    data class CannotJoin(
        val blockers: Blockers,
    ) : Refusal

    /** Removing the last task of a published mission (CA-D7). */
    data object LastTask : Refusal

    /** Changing the topic of a non-empty mission (CA-D10). */
    data object TopicLocked : Refusal

    /** A task in these published missions cannot return to `DRAFT` (FR-57). */
    data class TaskDraftInPublished(
        val missions: List<Mission>,
    ) : Refusal

    /** A task in these published missions cannot become `GRAPHIC` (CA-D8). */
    data class TaskGraphicInPublished(
        val missions: List<Mission>,
    ) : Refusal

    /** A task in these missions cannot change its topic or grade (CA-D10). */
    data class TaskTopicInMissions(
        val missions: List<Mission>,
    ) : Refusal

    /** A task in these published missions cannot be deleted (CA-D6). */
    data class TaskDeleteInPublished(
        val missions: List<Mission>,
    ) : Refusal

    /** These tasks have attempts, problem reports or opened hints (FR-56, CA-D22). */
    data class Used(
        val taskIds: List<UUID>,
    ) : Refusal
}

/**
 * Field rules of missions (store spec content-authoring "Missions", "Field limits"; FR-9, CA-D12,
 * CA-D17, CA-D18) and the state rules that keep "every task in a published mission is `PUBLISHED` and
 * `TEXT`, and the mission is not empty" true after every edit (FR-57, CA-D6..CA-D10), and the delete
 * rules (FR-56, CA-D22). Pure functions: the caller loads the data.
 */
object MissionRules {
    fun validate(
        draft: MissionDraft,
        context: MissionContext,
    ): Validation<MissionContent> {
        val errors = mutableListOf<FieldError>()
        val topic =
            when (val id = draft.topicId) {
                In.Missing -> null.also { errors += FieldError("topicId", "required") }
                In.Invalid -> null.also { errors += FieldError("topicId", "not-uuid") }
                is In.Ok -> context.topic?.takeIf { it.id == id.value } ?: null.also { errors += FieldError("topicId", "topic-unknown") }
            }
        val requested = draft.position.orNull()?.takeIf { it in 1..Limits.POSITION_MAX }
        val position =
            when (requested) {
                null -> null.also { errors += FieldError("position", "position") }
                in context.takenPositions -> null.also { errors += FieldError("position", "position-taken") }
                else -> requested
            }
        val taskIds =
            when (val list = draft.taskIds) {
                In.Missing -> {
                    null.also { errors += FieldError("taskIds", "required") }
                }

                In.Invalid -> {
                    null.also { errors += FieldError("taskIds", "wrong-type") }
                }

                is In.Ok -> {
                    if (list.value.size > Limits.MISSION_TASKS) {
                        null.also { errors += FieldError("taskIds", "mission-tasks-max", listOf(Limits.MISSION_TASKS)) }
                    } else {
                        tasks(list.value, topic, context, errors)
                    }
                }
            }
        return if (errors.isEmpty() && topic != null && position != null && taskIds != null) {
            Validation.Valid(MissionContent(topic.id, position, taskIds))
        } else {
            Validation.Invalid(errors)
        }
    }

    /** Each id well formed, known, listed once (CA-D12) and of the mission topic (FR-9). */
    private fun tasks(
        items: List<In<UUID>>,
        topic: Topic?,
        context: MissionContext,
        errors: MutableList<FieldError>,
    ): List<UUID> {
        val seen = mutableSetOf<UUID>()
        items.forEachIndexed { index, item ->
            val field = "taskIds[$index]"
            val id = item.orNull()
            val task = id?.let(context.tasks::get)
            when {
                id == null -> {
                    errors += FieldError(field, "not-uuid")
                }

                !seen.add(id) -> {
                    errors += FieldError(field, "task-repeated")
                }

                task == null -> {
                    errors += FieldError(field, "task-unknown")
                }

                topic != null && task.content.topicId != topic.id -> {
                    val own = context.topics[task.content.topicId]?.name ?: "?"
                    errors += FieldError(field, "task-topic", listOf(own, topic.name))
                }
            }
        }
        return items.mapNotNull { it.orNull() }
    }

    /** The tasks among [tasks] that may not be in a published mission (FR-57, CA-D8, CA-D9). */
    fun blockers(tasks: List<Task>): Blockers =
        Blockers(
            drafts = tasks.filter { it.status != PublishStatus.PUBLISHED },
            graphic = tasks.filter { it.content.modality != Modality.TEXT },
        )

    /** Publishing a mission whose tasks are [tasks], in order (FR-57, CA-D7). */
    fun publish(tasks: List<Task>): Refusal? {
        if (tasks.isEmpty()) return Refusal.EmptyMission
        val blockers = blockers(tasks)
        return if (blockers.isEmpty()) null else Refusal.Unpublishable(blockers)
    }

    /** CA-D10: a stored mission with tasks keeps its topic. */
    fun topicChange(
        stored: Mission,
        topicId: UUID,
    ): Refusal? = if (topicId != stored.content.topicId && stored.content.taskIds.isNotEmpty()) Refusal.TopicLocked else null

    /**
     * Saving [updated] over [stored] (CA-D7, CA-D9); [tasks] holds at least every task of [updated]. A draft
     * mission takes any list the field rules accept; a published one stays non-empty and admits only
     * published text tasks. Reordering and removing (while one remains) are always allowed.
     */
    fun edit(
        stored: Mission,
        updated: MissionContent,
        tasks: Map<UUID, Task>,
    ): Refusal? {
        if (stored.status != PublishStatus.PUBLISHED) return null
        if (updated.taskIds.isEmpty()) return Refusal.LastTask
        val added = updated.taskIds.filter { it !in stored.content.taskIds }.mapNotNull(tasks::get)
        val blockers = blockers(added)
        return if (blockers.isEmpty()) null else Refusal.CannotJoin(blockers)
    }

    /** Setting a task to [status] while it is in [missions] (FR-57). */
    fun taskStatus(
        status: PublishStatus,
        missions: List<Mission>,
    ): Refusal? {
        if (status != PublishStatus.DRAFT) return null
        val published = missions.published()
        return if (published.isEmpty()) null else Refusal.TaskDraftInPublished(published)
    }

    /** Saving [updated] over [current] while the task is in [missions] (CA-D8, CA-D10). */
    fun taskEdit(
        current: TaskContent,
        updated: TaskContent,
        missions: List<Mission>,
    ): Refusal? {
        if (missions.isNotEmpty() && (updated.topicId != current.topicId || updated.grade != current.grade)) {
            return Refusal.TaskTopicInMissions(missions)
        }
        val published = missions.published()
        if (updated.modality == Modality.GRAPHIC && current.modality != Modality.GRAPHIC && published.isNotEmpty()) {
            return Refusal.TaskGraphicInPublished(published)
        }
        return null
    }

    /** Deleting a task that is in [missions]; [used] when practice references it (CA-D6, FR-56, CA-D22). */
    fun taskDelete(
        taskId: UUID,
        missions: List<Mission>,
        used: Boolean,
    ): Refusal? {
        val published = missions.published()
        return when {
            published.isNotEmpty() -> Refusal.TaskDeleteInPublished(published)
            used -> Refusal.Used(listOf(taskId))
            else -> null
        }
    }

    /** Deleting a mission whose tasks [used] are referenced by practice (FR-56, CA-D22). */
    fun missionDelete(used: Set<UUID>): Refusal? = if (used.isEmpty()) null else Refusal.Used(used.toList())

    private fun List<Mission>.published() = filter { it.status == PublishStatus.PUBLISHED }
}
