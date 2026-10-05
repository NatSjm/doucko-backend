package cz.doucko.practice.application

import cz.doucko.content.ServedPart
import cz.doucko.practice.domain.AttemptRecord
import cz.doucko.practice.domain.HintRecord
import cz.doucko.practice.domain.RawAnswer
import cz.doucko.practice.domain.ReportRecord
import cz.doucko.practice.domain.RunRecord
import cz.doucko.practice.domain.TaskState
import java.util.UUID

/**
 * Where practice is stored (adapter: Postgres). Insert-only: the attempt log, opened hints and reports are
 * never edited or deleted (FR-42, OQ-20).
 */
interface PracticeStore {
    /** Serializes practice writes until the transaction ends (design decision 3). */
    fun lock()

    /** The attempts of [taskIds] in [scope]. */
    fun attempts(
        scope: UUID,
        taskIds: Collection<UUID>,
    ): List<AttemptRecord>

    /** The opened hints of [taskIds] in [scope]. */
    fun hints(
        scope: UUID,
        taskIds: Collection<UUID>,
    ): List<HintRecord>

    fun insertAttempt(attempt: AttemptRecord)

    fun insertHint(hint: HintRecord)

    fun insertReport(report: ReportRecord)

    /** The replay runs of a mission, oldest first. */
    fun runs(missionId: UUID): List<RunRecord>

    fun insertRun(run: RunRecord)

    /** Stored attempts per task, every scope (FR-56); tasks without attempts may be left out. */
    fun attemptCounts(taskIds: Collection<UUID>): Map<UUID, Int>

    /** The tasks among [taskIds] that any attempt, report or opened hint references (FR-56, CA-D22). */
    fun usedTasks(taskIds: Collection<UUID>): Set<UUID>
}

/** What the tutor sees of a task after an attempt: its parts, its hints and the scope's state. */
data class TutorInput(
    val parts: List<ServedPart>,
    val hintsTotal: Int,
    val hintsOpened: Int,
    val state: TaskState,
)

/** A revealed part: its key and the answer to show (the first accepted value, or the correct option keys). */
data class RevealedAnswer(
    val key: String,
    val answer: RawAnswer,
)

/**
 * Attempt feedback (contract `Feedback`, FR-44): a pointer to the next unopened hint and, once the task is
 * revealed, the answers. Built only by a [TutorPort] (architecture test); no ids, no timestamps.
 */
data class Feedback(
    val nextHintIndex: Int?,
    val revealedAnswers: List<RevealedAnswer>?,
)

/**
 * The tutor seam (FR-44): the only source of attempt feedback. The MVP implementation is deterministic and
 * calls nothing outside the process (TC-6); a later tutor replaces it without changing how attempts are
 * submitted.
 */
fun interface TutorPort {
    fun feedback(input: TutorInput): Feedback
}
