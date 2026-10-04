package cz.doucko.content

import java.util.UUID

/**
 * How practice uses tasks (store spec content-authoring "Delete only unused content"; FR-56, CA-D22).
 * Public API of the content module: practice (slice 7) implements it, content asks it before a delete
 * and for `AdminTask.attemptCount`. Content never depends on practice.
 *
 * Until an implementation is registered, content uses [NONE]: no attempt, report or opened hint exists
 * before slice 7.
 */
interface ContentUsage {
    /** The number of recorded attempts per task; tasks without attempts may be left out. */
    fun attemptCounts(taskIds: Collection<UUID>): Map<UUID, Int>

    /** The tasks among [taskIds] that any attempt, problem report or opened hint references. */
    fun usedTasks(taskIds: Collection<UUID>): Set<UUID>

    companion object {
        /** No usage at all: the answer while no practice data can exist. */
        val NONE: ContentUsage =
            object : ContentUsage {
                override fun attemptCounts(taskIds: Collection<UUID>): Map<UUID, Int> = emptyMap()

                override fun usedTasks(taskIds: Collection<UUID>): Set<UUID> = emptySet()
            }
    }
}
