package cz.doucko.practice.domain

import cz.doucko.checking.Verdict
import java.time.Instant
import java.util.UUID

/** The contract's `TaskState`: a task is resolved when it was answered correctly or revealed (OQ-13). */
enum class TaskState {
    UNRESOLVED,
    CORRECT_FIRST_TRY,
    CORRECT_SECOND_TRY,
    REVEALED,
    ;

    val resolved: Boolean get() = this != UNRESOLVED
}

/** The contract's `MissionState`. */
enum class MissionState { NEW, IN_PROGRESS, COMPLETE }

/** The contract's `ReportReason` (FR-30). */
enum class ReportReason { ANSWER_IS_CORRECT, UNCLEAR_PROMPT, OTHER }

/**
 * Where practice state lives (design decision 1): the primary scope is the child's first play-through,
 * shared by every mission that contains a task (OQ-15); a replay run is its own scope (OQ-17).
 */
object Scope {
    val PRIMARY: UUID = UUID(0, 0)
}

/** Raw input of one part as the child sent it: a string, or a selection of option keys (CHOICE_*). */
sealed interface RawAnswer {
    data class Text(
        val value: String,
    ) : RawAnswer

    data class Keys(
        val keys: List<String>,
    ) : RawAnswer
}

/**
 * One part of a stored attempt (FR-42): the raw input verbatim (null for a part locked as correct on
 * attempt 1, which is not re-entered), the normalized interpretation and the verdict.
 */
data class PartRecord(
    val key: String,
    val raw: RawAnswer?,
    val normalized: String?,
    val verdict: Verdict,
    val locked: Boolean,
)

/** A graded attempt in the append-only log (FR-31, FR-42). [correct] means every part is correct. */
data class AttemptRecord(
    val id: UUID,
    val taskId: UUID,
    val missionId: UUID,
    val scope: UUID,
    val replay: Boolean,
    val attemptNo: Int,
    val hintsUsed: Int,
    val correct: Boolean,
    val parts: List<PartRecord>,
    val createdAt: Instant,
)

/** An opened hint (FR-28): 1-based [index], opened once per task and scope. */
data class HintRecord(
    val taskId: UUID,
    val scope: UUID,
    val index: Int,
    val missionId: UUID,
    val openedAt: Instant,
)

/** A problem report (FR-30) with the latest attempt of the current run attached, if any. */
data class ReportRecord(
    val id: UUID,
    val taskId: UUID,
    val missionId: UUID,
    val reason: ReportReason,
    val note: String?,
    val attemptId: UUID?,
    val createdAt: Instant,
)

/** A replay run of a mission (OQ-17); its id is the run's scope. */
data class RunRecord(
    val id: UUID,
    val missionId: UUID,
    val startedAt: Instant,
)

/** An invalid request field: the contract's dotted path and a catalog code (`practice.error.<code>`). */
data class FieldError(
    val field: String,
    val code: String,
    val args: List<Any> = emptyList(),
)

/** A rank of the placeholder ladder (contract `Rank`). */
data class Rank(
    val index: Int,
    val name: String,
    val minXp: Int,
)

/**
 * The placeholder rank ladder of gamification ("Rank ladder": five ranks at 0, 150, 400, 800 and 1500 XP
 * with placeholder names). XP is slice 8, so every child is on the first rank until then (design decision 7).
 */
object PlaceholderLadder {
    val ranks =
        listOf(
            Rank(0, "Nováček", 0),
            Rank(1, "Stopař", 150),
            Rank(2, "Pátrač", 400),
            Rank(3, "Detektiv", 800),
            Rank(4, "Mistr detektiv", 1500),
        )

    fun rankFor(xp: Int): Rank = ranks.last { it.minXp <= xp }

    fun nextAfter(rank: Rank): Rank? = ranks.getOrNull(rank.index + 1)
}
