package cz.doucko.practice.application

import cz.doucko.content.ContentUsage
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * The content port `ContentUsage` (FR-56, CA-D22) from the practice store: a task is used once any attempt,
 * problem report or opened hint references it, in any scope. Content asks it before a delete and for
 * `AdminTask.attemptCount`.
 */
@Component
@Transactional(readOnly = true)
class PracticeContentUsage(
    private val store: PracticeStore,
) : ContentUsage {
    override fun attemptCounts(taskIds: Collection<UUID>): Map<UUID, Int> =
        if (taskIds.isEmpty()) emptyMap() else store.attemptCounts(taskIds)

    override fun usedTasks(taskIds: Collection<UUID>): Set<UUID> = if (taskIds.isEmpty()) emptySet() else store.usedTasks(taskIds)
}
