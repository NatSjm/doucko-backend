package cz.doucko.content.application

import cz.doucko.content.domain.Mission
import cz.doucko.content.domain.Refusal
import cz.doucko.content.domain.Task
import org.springframework.stereotype.Component

/**
 * Turns a domain [Refusal] into the 409 the web adapter renders (catalog key `content.conflict.<code>`).
 * Missions are named by topic and position, tasks by task number and page (design decision 6): names
 * the admin recognises, taken from stored content, never from the refused request (NFR-3).
 */
@Component
class Conflicts(
    private val topics: TopicStore,
) {
    fun of(
        refusal: Refusal,
        mission: Mission? = null,
    ): ContentConflict =
        when (refusal) {
            Refusal.EmptyMission -> {
                ContentConflict("mission-empty", emptyList(), emptyList())
            }

            is Refusal.Unpublishable -> {
                val b = refusal.blockers
                // A task that is both a draft and graphic is named once, as a draft.
                val graphicOnly = b.graphic.filter { g -> b.drafts.none { it.id == g.id } }
                when {
                    b.drafts.isNotEmpty() && graphicOnly.isNotEmpty() -> {
                        ContentConflict("mission-drafts-graphic", listOf(tasks(b.drafts), tasks(graphicOnly)), b.tasks().map { it.id })
                    }

                    b.drafts.isNotEmpty() -> {
                        ContentConflict("mission-drafts", listOf(tasks(b.drafts)), b.drafts.map { it.id })
                    }

                    else -> {
                        ContentConflict("mission-graphic", listOf(tasks(b.graphic)), b.graphic.map { it.id })
                    }
                }
            }

            is Refusal.CannotJoin -> {
                val blocking = refusal.blockers.tasks()
                ContentConflict("mission-join", listOf(tasks(blocking), name(checkNotNull(mission))), blocking.map { it.id })
            }

            Refusal.LastTask -> {
                val m = checkNotNull(mission)
                ContentConflict("mission-last-task", listOf(name(m)), listOf(m.id))
            }

            Refusal.TopicLocked -> {
                // The tasks block the topic change (CA-D10): removing them unlocks it.
                ContentConflict("mission-topic-locked", emptyList(), mission?.content?.taskIds.orEmpty())
            }

            is Refusal.TaskDraftInPublished -> {
                missions("task-draft-in-published", refusal.missions)
            }

            is Refusal.TaskGraphicInPublished -> {
                missions("task-graphic-in-published", refusal.missions)
            }

            is Refusal.TaskTopicInMissions -> {
                missions("task-topic-in-missions", refusal.missions)
            }

            is Refusal.TaskDeleteInPublished -> {
                missions("task-delete-in-published", refusal.missions)
            }

            is Refusal.Used -> {
                ContentConflict(if (mission == null) "task-used" else "mission-used", emptyList(), refusal.taskIds)
            }
        }

    /** "Desetinná čísla, mission 1". */
    fun name(mission: Mission): String {
        val topic = topics.find(mission.content.topicId)?.name ?: "?"
        return "$topic, mission ${mission.content.position}"
    }

    private fun missions(
        code: String,
        missions: List<Mission>,
    ) = ContentConflict(code, listOf(missions.joinToString("; ", transform = ::name)), missions.map { it.id })

    private fun tasks(tasks: List<Task>) = tasks.joinToString("; ", transform = ::name)

    /** "task 4b, page 57". */
    private fun name(task: Task): String {
        val ref = task.content.sourceRef
        return "task ${ref.taskNo}${ref.subPart.orEmpty()}, page ${ref.page}"
    }
}
