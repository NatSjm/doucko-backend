package cz.doucko.practice

import cz.doucko.checking.AnswerSpec
import cz.doucko.checking.AnswerType
import cz.doucko.checking.Verdict
import cz.doucko.practice.domain.AttemptRecord
import cz.doucko.practice.domain.GradablePart
import cz.doucko.practice.domain.Grade
import cz.doucko.practice.domain.OutcomeOrder
import cz.doucko.practice.domain.PartRecord
import cz.doucko.practice.domain.RawAnswer
import cz.doucko.practice.domain.Reevaluation
import cz.doucko.practice.domain.ReevaluationDecision
import cz.doucko.practice.domain.ReevaluationRules
import cz.doucko.practice.domain.Scope
import cz.doucko.practice.domain.TaskProgress
import cz.doucko.practice.domain.TaskState
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The pure re-evaluation rules (store spec progress-stats "Re-evaluation of a reported attempt"; FR-41,
 * OQ-17, OQ-20): re-grading with the current spec, the outcome order and when a re-evaluation changes
 * anything, for first play-through and replay attempts.
 */
class ReevaluationRulesTest {
    private val task = UUID.randomUUID()
    private val half = GradablePart("a", AnswerSpec(AnswerType.DECIMAL, listOf("0.5")))
    private val wrongKey = GradablePart("a", AnswerSpec(AnswerType.DECIMAL, listOf("0.05")))
    private val choice = GradablePart("c", AnswerSpec(AnswerType.CHOICE_SINGLE, listOf("A"), options = listOf("A", "B")))
    private val at = Instant.parse("2026-10-07T10:00:00Z")

    private fun attempt(
        no: Int,
        correct: Boolean,
        raw: String = "0,5",
        replay: Boolean = false,
        reevaluation: Reevaluation? = null,
    ) = AttemptRecord(
        UUID.randomUUID(),
        task,
        UUID.randomUUID(),
        if (replay) UUID.randomUUID() else Scope.PRIMARY,
        replay,
        no,
        0,
        correct,
        listOf(PartRecord("a", RawAnswer.Text(raw), raw.replace(',', '.'), if (correct) Verdict.CORRECT else Verdict.WRONG, false)),
        at.plusSeconds(no.toLong()),
        reevaluation,
    )

    private val correct = Grade.Graded(listOf(PartRecord("a", RawAnswer.Text("0,5"), "0.5", Verdict.CORRECT, false)))
    private val wrong = Grade.Graded(listOf(PartRecord("a", RawAnswer.Text("0,5"), "0.5", Verdict.WRONG, false)))
    private val fixed = Reevaluation(mapOf("a" to Verdict.CORRECT), true, at)

    // @trace FR-41
    @Test
    fun `outcomes are ordered unresolved, revealed, correct on the 2nd, correct on the 1st attempt`() {
        listOf(TaskState.UNRESOLVED, TaskState.REVEALED, TaskState.CORRECT_SECOND_TRY, TaskState.CORRECT_FIRST_TRY)
            .map(OutcomeOrder::rank)
            .zipWithNext()
            .forEach { (a, b) -> (a < b) shouldBe true }
    }

    // @trace FR-41
    @Test
    fun `a revealed task re-evaluated on attempt 2 becomes correct on the 2nd attempt`() {
        val a1 = attempt(1, false)
        val a2 = attempt(2, false)
        ReevaluationRules.decide(a2, correct, listOf(a1, a2)) shouldBe ReevaluationDecision.Improved(TaskState.CORRECT_SECOND_TRY)
    }

    // @trace FR-41
    @Test
    fun `a task in progress re-evaluated on attempt 1 becomes correct on the 1st attempt`() {
        val a1 = attempt(1, false)
        ReevaluationRules.decide(a1, correct, listOf(a1)) shouldBe ReevaluationDecision.Improved(TaskState.CORRECT_FIRST_TRY)
    }

    // @trace FR-41
    @Test
    fun `attempt 1 of a task solved on attempt 2 can still improve it to the 1st attempt`() {
        val a1 = attempt(1, false)
        val a2 = attempt(2, true)
        ReevaluationRules.decide(a1, correct, listOf(a1, a2)) shouldBe ReevaluationDecision.Improved(TaskState.CORRECT_FIRST_TRY)
    }

    // @trace FR-41
    @Test
    fun `re-evaluation never lowers an outcome or repeats one`() {
        val a1 = attempt(1, true)
        ReevaluationRules.decide(a1, wrong, listOf(a1)) shouldBe ReevaluationDecision.NoChange
        ReevaluationRules.decide(a1, correct, listOf(a1)) shouldBe ReevaluationDecision.NoChange
        val r1 = attempt(1, false, reevaluation = fixed)
        val r2 = attempt(2, false)
        ReevaluationRules.decide(r2, correct, listOf(r1, r2)) shouldBe ReevaluationDecision.NoChange
        ReevaluationRules.decide(r1, correct, listOf(r1, r2)) shouldBe ReevaluationDecision.NoChange
    }

    // @trace FR-41
    @Test
    fun `a verdict that stays wrong changes nothing`() {
        val a1 = attempt(1, false)
        val a2 = attempt(2, false)
        ReevaluationRules.decide(a2, wrong, listOf(a1, a2)) shouldBe ReevaluationDecision.NoChange
        ReevaluationRules.decide(a1, wrong, listOf(a1)) shouldBe ReevaluationDecision.NoChange
    }

    // @trace FR-41
    @Test
    fun `an unreadable or ungradable re-check changes nothing`() {
        val a1 = attempt(1, false)
        ReevaluationRules.decide(a1, null, listOf(a1)) shouldBe ReevaluationDecision.NoChange
        ReevaluationRules.decide(a1, Grade.Unreadable(emptyList()), listOf(a1)) shouldBe ReevaluationDecision.NoChange
    }

    // @trace FR-41
    @Test
    fun `a replay attempt now correct is recorded without changing the outcome`() {
        val replay = attempt(1, false, replay = true)
        ReevaluationRules.decide(replay, correct, emptyList()) shouldBe ReevaluationDecision.ReplayCorrected
        ReevaluationRules.decide(replay, wrong, emptyList()) shouldBe ReevaluationDecision.NoChange
        ReevaluationRules.decide(attempt(1, true, replay = true), correct, emptyList()) shouldBe ReevaluationDecision.NoChange
        ReevaluationRules.decide(attempt(1, false, replay = true, reevaluation = fixed), correct, emptyList()) shouldBe
            ReevaluationDecision.NoChange
    }

    // @trace FR-41
    @Test
    fun `a replay re-evaluation never changes the replay run's own state`() {
        val replay = attempt(1, false, replay = true, reevaluation = fixed)
        replay.effectiveCorrect shouldBe false
        TaskProgress(listOf(replay)).state shouldBe TaskState.UNRESOLVED
    }

    // @trace FR-41
    @Test
    fun `practice state reads the re-evaluated verdict of a first play-through attempt`() {
        TaskProgress(listOf(attempt(1, false, reevaluation = fixed))).state shouldBe TaskState.CORRECT_FIRST_TRY
        TaskProgress(listOf(attempt(1, false), attempt(2, false, reevaluation = fixed))).state shouldBe TaskState.CORRECT_SECOND_TRY
    }

    // @trace FR-41
    @Test
    fun `re-grading uses the current spec, the raw input as typed and the locked parts`() {
        val a = attempt(1, false)
        val regraded = ReevaluationRules.regrade(listOf(half), a).shouldBeInstanceOf<Grade.Graded>()
        regraded.correct shouldBe true
        regraded.parts.single().normalized shouldBe "0.5"
        ReevaluationRules.regrade(listOf(wrongKey), a).shouldBeInstanceOf<Grade.Graded>().correct shouldBe false

        val second =
            AttemptRecord(
                UUID.randomUUID(),
                task,
                UUID.randomUUID(),
                Scope.PRIMARY,
                false,
                2,
                0,
                false,
                listOf(
                    PartRecord("c", null, "A", Verdict.CORRECT, locked = true),
                    PartRecord("a", RawAnswer.Text("0,5"), "0.5", Verdict.WRONG, false),
                ),
                at,
            )
        val both = ReevaluationRules.regrade(listOf(choice, half), second).shouldBeInstanceOf<Grade.Graded>()
        both.parts.map { it.key to it.verdict } shouldContainExactly listOf("c" to Verdict.CORRECT, "a" to Verdict.CORRECT)
        both.parts.first().locked shouldBe true
    }

    // @trace FR-41
    @Test
    fun `input that no longer fits the spec is not graded`() {
        val text = attempt(1, false, raw = "abc")
        ReevaluationRules
            .regrade(listOf(GradablePart("a", AnswerSpec(AnswerType.INTEGER, listOf("3")))), text)
            .shouldBeInstanceOf<Grade.Unreadable>()
        ReevaluationRules.regrade(listOf(GradablePart("a", choice.spec)), text).shouldBeNull()
    }
}
