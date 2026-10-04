package cz.doucko.checking

import cz.doucko.checking.domain.FormatTips
import cz.doucko.checking.domain.Grader

/**
 * The checker's public API (store spec answer-checking, FR-14 to FR-21): a pure, deterministic function of
 * one part's spec and raw input. It never throws for any input, makes no I/O, and returns a format tip
 * exactly when the verdict is UNPARSEABLE.
 */
object AnswerChecker {
    fun check(
        spec: AnswerSpec,
        input: RawInput,
    ): CheckResult {
        val graded = Grader.grade(spec, input) ?: return CheckResult(Verdict.UNPARSEABLE, null, FormatTips.tipFor(spec))
        return CheckResult(if (graded.correct) Verdict.CORRECT else Verdict.WRONG, graded.normalized, null)
    }

    fun check(
        spec: AnswerSpec,
        input: String,
    ): CheckResult = check(spec, RawInput.Text(input))
}
