package cz.doucko.practice.domain

/**
 * The order of task outcomes for re-evaluation (store spec progress-stats "Re-evaluation of a reported
 * attempt"): unresolved < revealed < correct on the 2nd attempt < correct on the 1st attempt.
 */
object OutcomeOrder {
    fun rank(state: TaskState): Int =
        when (state) {
            TaskState.UNRESOLVED -> 0
            TaskState.REVEALED -> 1
            TaskState.CORRECT_SECOND_TRY -> 2
            TaskState.CORRECT_FIRST_TRY -> 3
        }
}

/** What a re-evaluation does (design decision 8). */
sealed interface ReevaluationDecision {
    /** Nothing is stored; the report stays `OPEN`. */
    data object NoChange : ReevaluationDecision

    /** A first play-through attempt whose new verdict improves the task's outcome to [state]. */
    data class Improved(
        val state: TaskState,
    ) : ReevaluationDecision

    /** A replay attempt that was wrong and is now correct: recorded, no outcome or XP change (OQ-17). */
    data object ReplayCorrected : ReevaluationDecision
}

/** Re-evaluation of the attempt attached to a report (FR-41, OQ-20; design decisions 7 and 8). Pure. */
object ReevaluationRules {
    /**
     * Grades [attempt] again with the task's current [parts]: the parts typed in that attempt with their raw
     * input, its locked parts kept correct. Null when the stored input no longer fits a part's shape.
     */
    fun regrade(
        parts: List<GradablePart>,
        attempt: AttemptRecord,
    ): Grade? {
        val answers = attempt.parts.mapNotNull { part -> part.raw?.let { part.key to it } }.toMap()
        if (Grading.shapeErrors(parts, answers).isNotEmpty()) return null
        return Grading.grade(parts, answers, attempt.parts.filter { it.locked }.associateBy { it.key })
    }

    /**
     * The decision for [attempt] given its new [grade] (null or unreadable: no change) and the stored attempts
     * of its task in the primary scope ([primaryAttempts], re-evaluations included).
     */
    fun decide(
        attempt: AttemptRecord,
        grade: Grade?,
        primaryAttempts: List<AttemptRecord>,
    ): ReevaluationDecision {
        if (grade !is Grade.Graded) return ReevaluationDecision.NoChange
        if (attempt.replay) {
            val wasCorrect = attempt.reevaluation?.correct ?: attempt.correct
            return if (!wasCorrect && grade.correct) ReevaluationDecision.ReplayCorrected else ReevaluationDecision.NoChange
        }
        val before = TaskProgress(primaryAttempts).state
        val recheck = Reevaluation(grade.parts.associate { it.key to it.verdict }, grade.correct, attempt.createdAt)
        val after = TaskProgress(primaryAttempts.map { if (it.id == attempt.id) it.copy(reevaluation = recheck) else it }).state
        return if (OutcomeOrder.rank(after) >
            OutcomeOrder.rank(before)
        ) {
            ReevaluationDecision.Improved(after)
        } else {
            ReevaluationDecision.NoChange
        }
    }
}
