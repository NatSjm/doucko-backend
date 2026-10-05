package cz.doucko.practice

import cz.doucko.checking.AnswerSpec
import cz.doucko.checking.AnswerType
import cz.doucko.checking.Verdict
import cz.doucko.practice.domain.AttemptRecord
import cz.doucko.practice.domain.FieldError
import cz.doucko.practice.domain.GradablePart
import cz.doucko.practice.domain.Grade
import cz.doucko.practice.domain.Grading
import cz.doucko.practice.domain.JsonText
import cz.doucko.practice.domain.MissionProgress
import cz.doucko.practice.domain.MissionState
import cz.doucko.practice.domain.PartRecord
import cz.doucko.practice.domain.PlaceholderLadder
import cz.doucko.practice.domain.RawAnswer
import cz.doucko.practice.domain.ReportInput
import cz.doucko.practice.domain.ReportReason
import cz.doucko.practice.domain.ReportRules
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

/** The pure practice rules: the two-attempt state machine, grading with locks, HQ progress and reports. */
class PracticeRulesTest {
    private val task = UUID.randomUUID()
    private val integer12 = GradablePart("a", AnswerSpec(AnswerType.INTEGER, listOf("12")))
    private val decimalHalf = GradablePart("b", AnswerSpec(AnswerType.DECIMAL, listOf("0.5")))
    private val choice = GradablePart("c", AnswerSpec(AnswerType.CHOICE_MULTI, listOf("A", "C"), options = listOf("A", "B", "C")))

    private fun part(
        key: String,
        verdict: Verdict,
        normalized: String? = "x",
    ) = PartRecord(key, RawAnswer.Text("x"), normalized, verdict, locked = false)

    private fun attempt(
        no: Int,
        vararg parts: PartRecord,
    ) = AttemptRecord(
        UUID.randomUUID(),
        task,
        UUID.randomUUID(),
        Scope.PRIMARY,
        false,
        no,
        0,
        parts.all { it.verdict == Verdict.CORRECT },
        parts.toList(),
        Instant.now(),
    )

    private fun text(value: String) = RawAnswer.Text(value)

    // @trace FR-27
    @Test
    fun `the task state follows the two-attempt table`() {
        val right = part("a", Verdict.CORRECT)
        val wrong = part("a", Verdict.WRONG)
        TaskProgress(emptyList()).let {
            it.state shouldBe TaskState.UNRESOLVED
            it.attemptsUsed shouldBe 0
            it.attemptsLeft shouldBe 2
        }
        TaskProgress(listOf(attempt(1, right))).let {
            it.state shouldBe TaskState.CORRECT_FIRST_TRY
            it.attemptsLeft shouldBe 0
        }
        TaskProgress(listOf(attempt(1, wrong))).let {
            it.state shouldBe TaskState.UNRESOLVED
            it.attemptsUsed shouldBe 1
            it.attemptsLeft shouldBe 1
        }
        TaskProgress(listOf(attempt(2, right), attempt(1, wrong))).let {
            it.state shouldBe TaskState.CORRECT_SECOND_TRY
            it.attemptsUsed shouldBe 2
            it.attemptsLeft shouldBe 0
            it.latest?.attemptNo shouldBe 2
        }
        TaskProgress(listOf(attempt(1, wrong), attempt(2, wrong))).state shouldBe TaskState.REVEALED
    }

    // @trace FR-27
    @Test
    fun `parts correct on attempt 1 are locked only while the task is unresolved`() {
        val first = attempt(1, part("a", Verdict.CORRECT, "12"), part("b", Verdict.WRONG))
        TaskProgress(listOf(first)).locked.keys shouldContainExactly setOf("a")
        TaskProgress(emptyList()).locked shouldBe emptyMap()
        TaskProgress(listOf(first, attempt(2, part("a", Verdict.CORRECT), part("b", Verdict.WRONG)))).locked shouldBe emptyMap()
    }

    // @trace FR-27
    @Test
    fun `a locked part is not re-graded and keeps its attempt-1 value`() {
        val locked = mapOf("a" to PartRecord("a", text("12"), "12", Verdict.CORRECT, false))
        val grade = Grading.grade(listOf(integer12, decimalHalf), mapOf("a" to text("13"), "b" to text("0,5")), locked)
        grade.shouldBeInstanceOf<Grade.Graded>()
        grade.correct shouldBe true
        grade.parts shouldContainExactly
            listOf(
                PartRecord("a", null, "12", Verdict.CORRECT, locked = true),
                PartRecord("b", text("0,5"), "0.5", Verdict.CORRECT, locked = false),
            )
    }

    // @trace FR-22
    @Test
    fun `one unreadable part refuses the whole submission and names only that part`() {
        val grade = Grading.grade(listOf(integer12, decimalHalf), mapOf("a" to text("12"), "b" to text("3,,4")), emptyMap())
        grade.shouldBeInstanceOf<Grade.Unreadable>()
        grade.parts.map { it.key } shouldContainExactly listOf("b")
        (
            grade.parts
                .single()
                .formatTip
                .isNotBlank()
        ) shouldBe true
    }

    // @trace FR-22
    @Test
    fun `a missing non-locked part and a 101-code-point answer are unreadable, a missing locked part is fine`() {
        Grading.grade(listOf(integer12, decimalHalf), mapOf("a" to text("12")), emptyMap()).shouldBeInstanceOf<Grade.Unreadable>()
        Grading.grade(listOf(integer12), mapOf("a" to text("1".repeat(101))), emptyMap()).shouldBeInstanceOf<Grade.Unreadable>()
        Grading
            .grade(listOf(choice), emptyMap(), emptyMap())
            .shouldBeInstanceOf<Grade.Unreadable>()
        val locked = mapOf("a" to PartRecord("a", text("12"), "12", Verdict.CORRECT, false))
        Grading.grade(listOf(integer12, decimalHalf), mapOf("b" to text("0.5")), locked).shouldBeInstanceOf<Grade.Graded>()
    }

    // @trace FR-27
    @Test
    fun `locale-formatted numbers are correct and a huge integer is wrong, not unreadable`() {
        val thousand = GradablePart("d", AnswerSpec(AnswerType.INTEGER, listOf("1000")))
        val ok = Grading.grade(listOf(decimalHalf, thousand), mapOf("b" to text("0,5"), "d" to text("1 000")), emptyMap())
        ok.shouldBeInstanceOf<Grade.Graded>()
        ok.correct shouldBe true
        val huge = Grading.grade(listOf(integer12), mapOf("a" to text("99999999999999999999")), emptyMap())
        huge.shouldBeInstanceOf<Grade.Graded>()
        huge.parts.single().verdict shouldBe Verdict.WRONG
        huge.parts.single().raw shouldBe text("99999999999999999999")
    }

    // @trace FR-26
    @Test
    fun `an unknown part key and a shape that does not fit the part type are request errors`() {
        Grading.shapeErrors(
            listOf(integer12, choice),
            mapOf("a" to RawAnswer.Keys(listOf("A")), "c" to text("A"), "z" to text("1")),
        ) shouldContainExactly
            listOf(
                FieldError("answers.a", "answer-shape"),
                FieldError("answers.c", "answer-shape"),
                FieldError("answers.z", "unknown-part"),
            )
        Grading.shapeErrors(listOf(integer12, choice), mapOf("a" to text("1"), "c" to RawAnswer.Keys(emptyList()))) shouldBe emptyList()
    }

    // @trace FR-25
    @Test
    fun `mission states count resolved and revealed tasks, and activity without a resolution is in progress`() {
        val ids = List(10) { UUID.randomUUID() }
        MissionProgress.of(ids, emptyMap(), emptySet()).let {
            it.state shouldBe MissionState.NEW
            it.resolvedCount shouldBe 0
            it.firstUnresolved shouldBe ids[0]
        }
        val four = ids.take(4).associateWith { TaskState.CORRECT_FIRST_TRY }
        MissionProgress.of(ids, four, emptySet()).let {
            it.state shouldBe MissionState.IN_PROGRESS
            it.resolvedCount shouldBe 4
            it.firstUnresolved shouldBe ids[4]
        }
        val all = ids.dropLast(1).associateWith { TaskState.CORRECT_SECOND_TRY } + (ids.last() to TaskState.REVEALED)
        MissionProgress.of(ids, all, emptySet()).let {
            it.state shouldBe MissionState.COMPLETE
            it.resolvedCount shouldBe 10
            it.firstUnresolved.shouldBeNull()
        }
        MissionProgress.of(ids, mapOf(ids[0] to TaskState.UNRESOLVED), setOf(ids[0])).let {
            it.state shouldBe MissionState.IN_PROGRESS
            it.resolvedCount shouldBe 0
        }
    }

    // @trace FR-30
    @Test
    fun `a report needs a reason, OTHER needs a note, and a note has at most 1000 code points`() {
        fun errors(
            reason: JsonText,
            note: JsonText,
        ) = ReportRules.validate(reason, note).second.map { it.field to it.code }
        val other = JsonText.Value("OTHER")
        errors(JsonText.Missing, JsonText.Missing) shouldContainExactly listOf("reason" to "required")
        errors(JsonText.Value("ANGRY"), JsonText.Missing) shouldContainExactly listOf("reason" to "reason")
        errors(JsonText.WrongType, JsonText.Missing) shouldContainExactly listOf("reason" to "reason")
        errors(other, JsonText.Missing) shouldContainExactly listOf("note" to "note-required")
        errors(other, JsonText.Value("   ")) shouldContainExactly listOf("note" to "note-required")
        errors(other, JsonText.Value("x".repeat(1001))) shouldContainExactly listOf("note" to "too-long")
        errors(other, JsonText.WrongType) shouldContainExactly listOf("note" to "wrong-type")
        ReportRules.validate(other, JsonText.Value("  " + "😀".repeat(1000) + " ")).first shouldBe
            ReportInput(ReportReason.OTHER, "😀".repeat(1000))
        ReportRules.validate(JsonText.Value("UNCLEAR_PROMPT"), JsonText.Value("  ")).first shouldBe
            ReportInput(ReportReason.UNCLEAR_PROMPT, null)
    }

    // @trace FR-24
    @Test
    fun `the placeholder ladder puts every child on the first rank until XP exists`() {
        PlaceholderLadder.ranks.map { it.minXp } shouldContainExactly listOf(0, 150, 400, 800, 1500)
        PlaceholderLadder.rankFor(0).index shouldBe 0
        PlaceholderLadder.rankFor(1500).index shouldBe 4
        PlaceholderLadder.nextAfter(PlaceholderLadder.rankFor(0))?.index shouldBe 1
        PlaceholderLadder.nextAfter(PlaceholderLadder.rankFor(1500)).shouldBeNull()
    }
}
