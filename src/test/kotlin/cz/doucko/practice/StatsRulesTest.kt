package cz.doucko.practice

import cz.doucko.checking.Verdict
import cz.doucko.practice.domain.AttemptRecord
import cz.doucko.practice.domain.FirstTryCount
import cz.doucko.practice.domain.HintRecord
import cz.doucko.practice.domain.PartRecord
import cz.doucko.practice.domain.PragueWeeks
import cz.doucko.practice.domain.RawAnswer
import cz.doucko.practice.domain.Reevaluation
import cz.doucko.practice.domain.Scope
import cz.doucko.practice.domain.StatsRules
import cz.doucko.practice.domain.TaskState
import cz.doucko.practice.domain.WeekFigures
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * The pure statistics rules (store spec progress-stats "Session definition", "Weekly hint and reveal
 * figures", "Practice statistics page"; FR-40, OQ-14): sessions, Europe/Prague weeks incl. the DST changes,
 * hints and reveals per week, the success metric and the first-try counts.
 */
class StatsRulesTest {
    private val mission = UUID.randomUUID()
    private val replayScope = UUID.randomUUID()

    /** An instant given as Europe/Prague local time. */
    private fun prague(local: String): Instant = LocalDateTime.parse(local).atZone(PragueWeeks.ZONE).toInstant()

    private fun attempt(
        at: Instant,
        task: UUID = UUID.randomUUID(),
        no: Int = 1,
        correct: Boolean = true,
        replay: Boolean = false,
        reevaluation: Reevaluation? = null,
    ) = AttemptRecord(
        UUID.randomUUID(),
        task,
        mission,
        if (replay) replayScope else Scope.PRIMARY,
        replay,
        no,
        0,
        correct,
        listOf(PartRecord("a", RawAnswer.Text("1"), "1", if (correct) Verdict.CORRECT else Verdict.WRONG, false)),
        at,
        reevaluation,
    )

    private fun hint(
        at: Instant,
        task: UUID = UUID.randomUUID(),
        index: Int = 1,
        replay: Boolean = false,
    ) = HintRecord(task, if (replay) replayScope else Scope.PRIMARY, index, mission, at)

    /** [tasks] attempts on distinct tasks, one every [step] from [start]. */
    private fun run(
        start: Instant,
        tasks: Int,
        step: Duration = Duration.ofMinutes(4),
    ) = List(tasks) { attempt(start.plus(step.multipliedBy(it.toLong()))) }

    /** A Wednesday noon, so "now" is never near a week boundary. */
    private val now = prague("2026-10-07T12:00:00")
    private val currentMonday = LocalDate.parse("2026-10-05")

    private fun weeks(
        attempts: List<AttemptRecord>,
        hints: List<HintRecord> = emptyList(),
        count: Int = 4,
        at: Instant = now,
    ) = StatsRules.weeks(attempts, hints, at, count)

    private fun week(
        figures: List<WeekFigures>,
        monday: String,
    ) = figures.single { it.weekStart == LocalDate.parse(monday) }

    /** [sessions] qualifying sessions (10 tasks) in the week starting [monday], one a day from Monday 16:00. */
    private fun qualifying(
        monday: LocalDate,
        sessions: Int,
    ) = (0 until sessions).flatMap { day -> run(prague("${monday.plusDays(day.toLong())}T16:00:00"), 10, Duration.ofMinutes(2)) }

    // ---------------------------------------------------------------- sessions

    // @trace FR-40
    @Test
    fun `a 20-minute gap keeps one session`() {
        val attempts =
            run(prague("2026-10-06T16:00:00"), 6) + run(prague("2026-10-06T16:40:00"), 5, Duration.ofMinutes(3) + Duration.ofSeconds(45))
        val w = week(weeks(attempts), "2026-10-05")
        w.sessions shouldBe 1
        w.qualifyingSessions shouldBe 1
        w.tasksPerSession shouldContainExactly listOf(11)
    }

    // @trace FR-40
    @Test
    fun `a 35-minute gap splits the session`() {
        val attempts =
            run(prague("2026-10-06T16:00:00"), 6) + run(prague("2026-10-06T16:55:00"), 5, Duration.ofMinutes(3) + Duration.ofSeconds(45))
        val w = week(weeks(attempts), "2026-10-05")
        w.sessions shouldBe 2
        w.qualifyingSessions shouldBe 0
        w.tasksPerSession shouldContainExactly listOf(6, 5)
    }

    // @trace FR-40
    @Test
    fun `exactly 30 minutes is still the same session, one second more is not`() {
        val same = listOf(attempt(prague("2026-10-06T16:20:00")), attempt(prague("2026-10-06T16:50:00")))
        week(weeks(same), "2026-10-05").tasksPerSession shouldContainExactly listOf(2)
        val split = listOf(attempt(prague("2026-10-06T16:20:00")), attempt(prague("2026-10-06T16:50:01")))
        week(weeks(split), "2026-10-05").tasksPerSession shouldContainExactly listOf(1, 1)
    }

    // @trace FR-40
    @Test
    fun `two attempts on one task count once`() {
        val tasks = List(10) { UUID.randomUUID() }
        val start = prague("2026-10-06T16:00:00")
        val attempts =
            tasks.flatMapIndexed { i, t ->
                listOf(
                    attempt(start.plus(Duration.ofMinutes(3L * i)), t, 1, correct = false),
                    attempt(start.plus(Duration.ofMinutes(3L * i + 1)), t, 2),
                )
            }
        val w = week(weeks(attempts), "2026-10-05")
        w.sessions shouldBe 1
        w.tasksPerSession shouldContainExactly listOf(10)
        w.qualifyingSessions shouldBe 1
    }

    // @trace FR-40
    @Test
    fun `sessions are built from the log in time order, whatever order it comes in`() {
        val attempts = run(prague("2026-10-06T16:00:00"), 10, Duration.ofMinutes(2)).shuffled()
        StatsRules.sessions(attempts).map { it.answeredTasks } shouldContainExactly listOf(10)
        StatsRules.sessions(attempts).single().start shouldBe prague("2026-10-06T16:00:00")
    }

    // @trace FR-40
    @Test
    fun `attempts at the same instant belong to one session`() {
        val at = prague("2026-10-06T16:00:00")
        StatsRules.sessions(List(3) { attempt(at) }).map { it.answeredTasks } shouldContainExactly listOf(3)
        StatsRules.SESSION_GAP shouldBe Duration.ofMinutes(30)
    }

    // @trace FR-40
    @Test
    fun `replay attempts count towards sessions and answered tasks`() {
        val start = prague("2026-10-06T16:00:00")
        val attempts = run(start, 5) + List(5) { attempt(start.plus(Duration.ofMinutes(20L + it)), replay = true) }
        week(weeks(attempts), "2026-10-05").tasksPerSession shouldContainExactly listOf(10)
    }

    // ---------------------------------------------------------------- weeks

    // @trace FR-40
    @Test
    fun `a session from Sunday 23-40 to Monday 00-15 counts once, in the week ending on that Sunday`() {
        val start = prague("2026-09-27T23:40:00")
        val attempts = List(4) { attempt(start.plus(Duration.ofMinutes(12L * it))) }
        attempts.last().createdAt shouldBe prague("2026-09-28T00:16:00")
        val figures = weeks(attempts)
        week(figures, "2026-09-21").sessions shouldBe 1
        week(figures, "2026-09-21").tasksPerSession shouldContainExactly listOf(4)
        week(figures, "2026-09-28").sessions shouldBe 0
    }

    // @trace FR-40
    @Test
    fun `weeks are Prague weeks across the spring DST change`() {
        // Monday 2026-03-30 00:30 CEST is Sunday 22:30 UTC: a UTC week would put it in the week before.
        val sunday = attempt(prague("2026-03-29T23:00:00"))
        val monday = attempt(prague("2026-03-30T00:31:00"))
        monday.createdAt shouldBe Instant.parse("2026-03-29T22:31:00Z")
        val figures = weeks(listOf(sunday, monday), count = 3, at = prague("2026-04-01T12:00:00"))
        figures.map { it.weekStart } shouldContainExactly listOf("2026-03-16", "2026-03-23", "2026-03-30").map(LocalDate::parse)
        week(figures, "2026-03-23").sessions shouldBe 1
        week(figures, "2026-03-30").sessions shouldBe 1
        PragueWeeks.weekStart(Instant.parse("2026-03-29T21:59:59Z")) shouldBe LocalDate.parse("2026-03-23")
        PragueWeeks.weekStart(Instant.parse("2026-03-29T22:00:00Z")) shouldBe LocalDate.parse("2026-03-30")
    }

    // @trace FR-40
    @Test
    fun `weeks are Prague weeks across the autumn DST change`() {
        // Monday 2026-10-26 00:30 CET is Sunday 23:30 UTC.
        PragueWeeks.weekStart(Instant.parse("2026-10-25T22:59:59Z")) shouldBe LocalDate.parse("2026-10-19")
        PragueWeeks.weekStart(Instant.parse("2026-10-25T23:00:00Z")) shouldBe LocalDate.parse("2026-10-26")
        val figures =
            weeks(
                listOf(attempt(prague("2026-10-25T23:00:00")), attempt(prague("2026-10-26T00:31:00"))),
                count = 2,
                at = prague("2026-10-28T12:00:00"),
            )
        figures.map { it.sessions } shouldContainExactly listOf(1, 1)
    }

    // @trace FR-40
    @Test
    fun `the session gap is measured in real time across the autumn clock change`() {
        // 02:50 CEST and 02:10 CET on 2026-10-25 are 20 real minutes apart although the clock went back.
        val first = attempt(Instant.parse("2026-10-25T00:50:00Z"))
        val second = attempt(Instant.parse("2026-10-25T01:10:00Z"))
        StatsRules.sessions(listOf(first, second)).map { it.answeredTasks } shouldContainExactly listOf(2)
    }

    // @trace FR-40
    @Test
    fun `the requested weeks end with the current week and empty weeks are zeros`() {
        weeks(emptyList(), count = 1).map { it.weekStart } shouldContainExactly listOf(currentMonday)
        val all = weeks(emptyList(), count = 52)
        all shouldHaveSize 52
        all.first().weekStart shouldBe currentMonday.minusWeeks(51)
        all.last().weekStart shouldBe currentMonday
        all.forEach { it shouldBe WeekFigures(it.weekStart, 0, 0, emptyList(), 0, 0) }
        weeks(emptyList()).map { it.weekStart } shouldContainExactly (3 downTo 0).map { currentMonday.minusWeeks(it.toLong()) }
    }

    // @trace FR-40
    @Test
    fun `Monday 00-00 belongs to the new week`() {
        PragueWeeks.weekStart(prague("2026-10-05T00:00:00")) shouldBe LocalDate.parse("2026-10-05")
        PragueWeeks.weekStart(prague("2026-10-04T23:59:59")) shouldBe LocalDate.parse("2026-09-28")
        PragueWeeks.recent(prague("2026-10-05T00:00:00"), 2) shouldContainExactly listOf("2026-09-28", "2026-10-05").map(LocalDate::parse)
    }

    // ---------------------------------------------------------------- hints and reveals

    // @trace FR-40
    @Test
    fun `hint usage and reveals per week`() {
        val day = prague("2026-10-06T16:00:00")
        val hints = List(7) { hint(day.plus(Duration.ofMinutes(it.toLong()))) }
        val revealed = List(3) { attempt(day.plus(Duration.ofMinutes(10L + it)), no = 2, correct = false) }
        val solved = attempt(day.plus(Duration.ofMinutes(20)), no = 2, correct = true)
        val wrongFirst = attempt(day.plus(Duration.ofMinutes(21)), no = 1, correct = false)
        val w = week(weeks(revealed + solved + wrongFirst, hints), "2026-10-05")
        w.hintsUsed shouldBe 7
        w.revealed shouldBe 3
    }

    // @trace FR-40
    @Test
    fun `a hint counts in the week it was opened`() {
        val task = UUID.randomUUID()
        val hints = listOf(hint(prague("2026-09-27T23:50:00"), task, 1), hint(prague("2026-09-27T23:51:00"), task, 2))
        val figures = weeks(listOf(attempt(prague("2026-09-28T00:05:00"), task)), hints)
        week(figures, "2026-09-21").hintsUsed shouldBe 2
        week(figures, "2026-09-21").sessions shouldBe 0
        week(figures, "2026-09-28").hintsUsed shouldBe 0
        week(figures, "2026-09-28").sessions shouldBe 1
    }

    // @trace FR-40
    @Test
    fun `a hint without a later attempt counts`() {
        val w = week(weeks(emptyList(), listOf(hint(prague("2026-10-06T10:00:00")))), "2026-10-05")
        w.hintsUsed shouldBe 1
        w.sessions shouldBe 0
    }

    // @trace FR-40
    @Test
    fun `replay hints and reveals count in their week`() {
        val day = prague("2026-10-06T10:00:00")
        val figures =
            weeks(
                listOf(
                    attempt(day, no = 1, correct = false, replay = true),
                    attempt(day.plusSeconds(60), no = 2, correct = false, replay = true),
                ),
                listOf(hint(day.minusSeconds(60), replay = true)),
            )
        week(figures, "2026-10-05").hintsUsed shouldBe 1
        week(figures, "2026-10-05").revealed shouldBe 1
    }

    // @trace FR-40
    @Test
    fun `a re-evaluation does not remove a reveal from its week`() {
        val reevaluated = Reevaluation(mapOf("a" to Verdict.CORRECT), true, prague("2026-10-07T09:00:00"))
        val reveal = attempt(prague("2026-09-29T10:00:00"), no = 2, correct = false, reevaluation = reevaluated)
        reveal.effectiveCorrect shouldBe true
        week(weeks(listOf(reveal)), "2026-09-28").revealed shouldBe 1
    }

    // ---------------------------------------------------------------- success metric

    private fun completed(weeksBack: Int) = currentMonday.minusWeeks(weeksBack.toLong())

    // @trace FR-40
    @Test
    fun `success metric met with 3 qualifying sessions in each of the 4 completed weeks`() {
        val attempts = (1..4).flatMap { qualifying(completed(it), 3) }
        StatsRules.metricMet(attempts, now) shouldBe true
        (1..4).forEach { week(weeks(attempts, count = 5), completed(it).toString()).qualifyingSessions shouldBe 3 }
    }

    // @trace FR-40
    @Test
    fun `success metric not met when one completed week has 2`() {
        val attempts = (1..3).flatMap { qualifying(completed(it), 3) } + qualifying(completed(4), 2)
        StatsRules.metricMet(attempts, now) shouldBe false
        week(weeks(attempts, count = 5), completed(4).toString()).qualifyingSessions shouldBe 2
    }

    // @trace FR-40
    @Test
    fun `the current week does not count towards the metric`() {
        val attempts = (1..4).flatMap { qualifying(completed(it), 3) }
        week(weeks(attempts), currentMonday.toString()).qualifyingSessions shouldBe 0
        StatsRules.metricMet(attempts, now) shouldBe true
        StatsRules.metricMet((0..3).flatMap { qualifying(completed(it), 3) }, now) shouldBe false
    }

    // @trace FR-40
    @Test
    fun `less than 4 completed weeks of history is not met`() {
        StatsRules.metricMet((1..2).flatMap { qualifying(completed(it), 5) }, now) shouldBe false
        StatsRules.metricMet(emptyList(), now) shouldBe false
    }

    // @trace FR-40
    @Test
    fun `the metric ignores the 5th completed week`() {
        val attempts = (1..4).flatMap { qualifying(completed(it), 3) }
        StatsRules.metricMet(attempts, now) shouldBe true
        weeks(attempts, count = 1) shouldHaveSize 1
        weeks(attempts, count = 12) shouldHaveSize 12
        week(weeks(attempts, count = 12), completed(5).toString()).qualifyingSessions shouldBe 0
    }

    // @trace FR-40
    @Test
    fun `a session of 9 tasks does not qualify`() {
        val attempts = (1..4).flatMap { w -> (0 until 3).flatMap { d -> run(prague("${completed(w).plusDays(d.toLong())}T16:00:00"), 9) } }
        StatsRules.metricMet(attempts, now) shouldBe false
        week(weeks(attempts), completed(1).toString()).tasksPerSession shouldContainExactly listOf(9, 9, 9)
    }

    // ---------------------------------------------------------------- first try

    // @trace FR-40
    @Test
    fun `first-try counts per topic`() {
        val tasks = List(10) { UUID.randomUUID() }
        val states =
            tasks.take(6).associateWith { TaskState.CORRECT_FIRST_TRY } +
                mapOf(tasks[6] to TaskState.CORRECT_SECOND_TRY, tasks[7] to TaskState.REVEALED, tasks[8] to TaskState.UNRESOLVED)
        StatsRules.firstTry(tasks, states) shouldBe FirstTryCount(8, 6)
        StatsRules.firstTry(tasks, emptyMap()) shouldBe FirstTryCount(0, 0)
        StatsRules.firstTry(emptyList(), states) shouldBe FirstTryCount(0, 0)
    }
}
