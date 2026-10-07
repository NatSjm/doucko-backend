package cz.doucko.content

import java.util.UUID

/**
 * A mission was published, or a published mission's task list was saved (public event of the content
 * module). Published synchronously inside the admin's transaction; practice listens to credit the completion
 * bonus of a mission that is complete at that moment (gamification "Mission completion bonus", OQ-28).
 * Content never depends on its listeners.
 */
data class PublishedMissionChanged(
    val missionId: UUID,
)
