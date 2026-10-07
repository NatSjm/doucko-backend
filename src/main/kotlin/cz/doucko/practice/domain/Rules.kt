package cz.doucko.practice.domain

import cz.doucko.checking.AnswerChecker
import cz.doucko.checking.AnswerSpec
import cz.doucko.checking.AnswerType
import cz.doucko.checking.RawInput
import cz.doucko.checking.Verdict
import java.util.UUID

/**
 * The two-attempt state machine of one task in one scope (FR-27, OQ-11; design decision 2), derived from
 * the stored attempts only: nothing about a task's state is stored besides the attempt log. A first
 * play-through attempt counts with its re-evaluated verdict when an admin recorded one (progress-stats, FR-41).
 */
class TaskProgress(
    attempts: List<AttemptRecord>,
) {
    val attempts: List<AttemptRecord> = attempts.sortedBy { it.attemptNo }

    val state: TaskState =
        when {
            this.attempts.isEmpty() -> TaskState.UNRESOLVED
            this.attempts[0].effectiveCorrect -> TaskState.CORRECT_FIRST_TRY
            this.attempts.size == 1 -> TaskState.UNRESOLVED
            this.attempts[1].effectiveCorrect -> TaskState.CORRECT_SECOND_TRY
            else -> TaskState.REVEALED
        }

    val attemptsUsed: Int get() = attempts.size

    val attemptsLeft: Int get() = if (state.resolved) 0 else MAX_ATTEMPTS - attempts.size

    val latest: AttemptRecord? get() = attempts.lastOrNull()

    /** Parts correct on attempt 1, locked for attempt 2 (OQ-11). Empty before attempt 1 and once resolved. */
    val locked: Map<String, PartRecord>
        get() =
            if (state != TaskState.UNRESOLVED || attempts.isEmpty()) {
                emptyMap()
            } else {
                attempts[0].parts.filter { it.verdict == Verdict.CORRECT }.associateBy { it.key }
            }

    companion object {
        const val MAX_ATTEMPTS = 2
    }
}

/** A part as the grader needs it: its key and the checker's spec. */
data class GradablePart(
    val key: String,
    val spec: AnswerSpec,
)

/** An unreadable part of a refused submission: its key and the checker's Czech format tip. */
data class UnreadablePart(
    val key: String,
    val formatTip: String,
)

/** The result of grading one submission. */
sealed interface Grade {
    /** At least one non-locked part could not be read: nothing is graded or stored (FR-22, OQ-12). */
    data class Unreadable(
        val parts: List<UnreadablePart>,
    ) : Grade

    /** Every part graded; [parts] in task order, locked parts included. */
    data class Graded(
        val parts: List<PartRecord>,
    ) : Grade {
        val correct: Boolean get() = parts.all { it.verdict == Verdict.CORRECT }
    }
}

/** Grading of a submission against a task's parts (FR-22, FR-27, OQ-11, OQ-12). */
object Grading {
    private val CHOICE = setOf(AnswerType.CHOICE_SINGLE, AnswerType.CHOICE_MULTI)

    /**
     * Request-shape errors that need the task: an unknown part key, or a string for a `CHOICE_*` part and
     * an array for any other (400 on `answers.<key>`). Checked for every submitted key, locked parts too.
     */
    fun shapeErrors(
        parts: List<GradablePart>,
        answers: Map<String, RawAnswer>,
    ): List<FieldError> =
        answers.mapNotNull { (key, answer) ->
            val part = parts.firstOrNull { it.key == key } ?: return@mapNotNull FieldError("answers.$key", "unknown-part")
            if ((part.spec.type in CHOICE) != (answer is RawAnswer.Keys)) FieldError("answers.$key", "answer-shape") else null
        }

    /**
     * Grades every part in task order. A part locked on attempt 1 keeps its verdict and normalized value
     * and is not re-graded, whatever was sent for it. A missing non-locked part is graded as empty input.
     */
    fun grade(
        parts: List<GradablePart>,
        answers: Map<String, RawAnswer>,
        locked: Map<String, PartRecord>,
    ): Grade {
        val records = mutableListOf<PartRecord>()
        val unreadable = mutableListOf<UnreadablePart>()
        for (part in parts) {
            val lockedPart = locked[part.key]
            if (lockedPart != null) {
                records += PartRecord(part.key, null, lockedPart.normalized, Verdict.CORRECT, locked = true)
                continue
            }
            val raw = answers[part.key] ?: if (part.spec.type in CHOICE) RawAnswer.Keys(emptyList()) else RawAnswer.Text("")
            val result = AnswerChecker.check(part.spec, raw.toChecker())
            if (result.verdict == Verdict.UNPARSEABLE) {
                unreadable += UnreadablePart(part.key, result.formatTip!!)
            } else {
                records += PartRecord(part.key, raw, result.normalized, result.verdict, locked = false)
            }
        }
        return if (unreadable.isNotEmpty()) Grade.Unreadable(unreadable) else Grade.Graded(records)
    }

    private fun RawAnswer.toChecker(): RawInput =
        when (this) {
            is RawAnswer.Text -> RawInput.Text(value)
            is RawAnswer.Keys -> RawInput.Selection(keys)
        }
}

/** A mission's progress on HQ (FR-25, OQ-13, OQ-15). */
data class MissionProgress(
    val taskCount: Int,
    val resolvedCount: Int,
    val state: MissionState,
    val firstUnresolved: UUID?,
) {
    companion object {
        /**
         * [states] holds the tasks' states in the scope (absent = unresolved); [active] the tasks with any
         * attempt or opened hint in that scope. Complete when every task is resolved; in progress when not
         * complete and any task is resolved or active; else new.
         */
        fun of(
            taskIds: List<UUID>,
            states: Map<UUID, TaskState>,
            active: Set<UUID>,
        ): MissionProgress {
            val resolved = taskIds.count { states[it]?.resolved == true }
            val state =
                when {
                    resolved == taskIds.size -> MissionState.COMPLETE
                    resolved > 0 || taskIds.any { it in active } -> MissionState.IN_PROGRESS
                    else -> MissionState.NEW
                }
            return MissionProgress(taskIds.size, resolved, state, taskIds.firstOrNull { states[it]?.resolved != true })
        }
    }
}

/** A request field as read from JSON: absent (or null), of the wrong JSON type, or a string. */
sealed interface JsonText {
    data object Missing : JsonText

    data object WrongType : JsonText

    data class Value(
        val text: String,
    ) : JsonText
}

/** A valid report: the reason and the trimmed note (null when absent or blank). */
data class ReportInput(
    val reason: ReportReason,
    val note: String?,
)

/** Report validation (FR-30): a reason is required; `OTHER` needs a note; a note is at most 1000 code points after trimming. */
object ReportRules {
    const val NOTE_MAX = 1000

    fun validate(
        reason: JsonText,
        note: JsonText,
    ): Pair<ReportInput?, List<FieldError>> {
        val errors = mutableListOf<FieldError>()
        val parsed =
            when (reason) {
                is JsonText.Missing -> {
                    null.also { errors += FieldError("reason", "required") }
                }

                is JsonText.WrongType -> {
                    null.also { errors += FieldError("reason", "reason") }
                }

                is JsonText.Value -> {
                    ReportReason.entries.firstOrNull { it.name == reason.text }.also {
                        if (it ==
                            null
                        ) {
                            errors += FieldError("reason", "reason")
                        }
                    }
                }
            }
        val text =
            when (note) {
                is JsonText.Missing -> null
                is JsonText.WrongType -> null.also { errors += FieldError("note", "wrong-type") }
                is JsonText.Value -> note.text.trim().ifEmpty { null }
            }
        if (text != null && text.codePointCount(0, text.length) > NOTE_MAX) errors += FieldError("note", "too-long", listOf(NOTE_MAX))
        if (parsed == ReportReason.OTHER && text == null && note !is JsonText.WrongType) errors += FieldError("note", "note-required")
        // A missing or unknown reason always adds an error, so no error means a parsed reason.
        return if (errors.isEmpty()) ReportInput(parsed!!, text) to emptyList() else null to errors
    }
}
