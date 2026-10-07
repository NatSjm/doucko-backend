package cz.doucko.practice.domain

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.UUID

/**
 * Weeks of the practice statistics (store spec progress-stats "Session definition"; FR-40): Monday 00:00 to
 * the next Monday 00:00 in Europe/Prague local time, identified by their Monday. A week containing a DST
 * change keeps whole local days (167 or 169 hours).
 */
object PragueWeeks {
    val ZONE: ZoneId = ZoneId.of("Europe/Prague")

    /** The Monday of the Prague week [at] falls in. */
    fun weekStart(at: Instant): LocalDate = at.atZone(ZONE).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** The Mondays of the [count] most recent weeks at [now], oldest first, the current week last. */
    fun recent(
        now: Instant,
        count: Int,
    ): List<LocalDate> {
        val current = weekStart(now)
        return (count - 1 downTo 0).map { current.minusWeeks(it.toLong()) }
    }
}

/** A practice session (OQ-14): its first attempt and the number of distinct tasks answered in it. */
data class PracticeSession(
    val start: Instant,
    val answeredTasks: Int,
)

/** The figures of one week (contract `WeekStats`). */
data class WeekFigures(
    val weekStart: LocalDate,
    val sessions: Int,
    val qualifyingSessions: Int,
    val tasksPerSession: List<Int>,
    val hintsUsed: Int,
    val revealed: Int,
)

/** First-try figures of a topic (contract `Stats.firstTryRateByTopic`): resolved tasks and those correct on attempt 1. */
data class FirstTryCount(
    val resolved: Int,
    val firstTry: Int,
)

/** The practice statistics over the attempt log and the opened hints (FR-40; design decisions 2–5). Pure. */
object StatsRules {
    /** A gap of exactly this length keeps the session going; a longer one starts a new session (OQ-14). */
    @JvmField
    val SESSION_GAP: Duration = Duration.ofMinutes(30)

    /** A session with at least this many answered tasks qualifies (product brief §5). */
    const val QUALIFYING_TASKS = 10

    /** The success metric: [METRIC_SESSIONS] qualifying sessions in each of the [METRIC_WEEKS] completed weeks. */
    const val METRIC_WEEKS = 4
    const val METRIC_SESSIONS = 3

    const val DEFAULT_WEEKS = 4

    /** Every logged attempt (all scopes) split into sessions, in chronological order. */
    fun sessions(attempts: List<AttemptRecord>): List<PracticeSession> {
        val sessions = mutableListOf<PracticeSession>()
        var start: Instant? = null
        var last: Instant? = null
        val tasks = mutableSetOf<UUID>()
        for (attempt in attempts.sortedWith(compareBy({ it.createdAt }, { it.id }))) {
            if (last != null && Duration.between(last, attempt.createdAt) > SESSION_GAP) {
                sessions += PracticeSession(start!!, tasks.size)
                tasks.clear()
                start = null
            }
            if (start == null) start = attempt.createdAt
            tasks += attempt.taskId
            last = attempt.createdAt
        }
        if (start != null) sessions += PracticeSession(start, tasks.size)
        return sessions
    }

    /** The figures of the [count] most recent weeks at [now], oldest first. Reveals use the original verdicts. */
    fun weeks(
        attempts: List<AttemptRecord>,
        hints: List<HintRecord>,
        now: Instant,
        count: Int,
    ): List<WeekFigures> {
        val sessions = sessions(attempts).groupBy { PragueWeeks.weekStart(it.start) }
        val hintsByWeek = hints.groupingBy { PragueWeeks.weekStart(it.openedAt) }.eachCount()
        val reveals =
            attempts
                .filter { it.attemptNo == TaskProgress.MAX_ATTEMPTS && !it.correct }
                .groupingBy { PragueWeeks.weekStart(it.createdAt) }
                .eachCount()
        return PragueWeeks.recent(now, count).map { monday ->
            val sizes = sessions[monday].orEmpty().map { it.answeredTasks }
            WeekFigures(monday, sizes.size, sizes.count { it >= QUALIFYING_TASKS }, sizes, hintsByWeek[monday] ?: 0, reveals[monday] ?: 0)
        }
    }

    /** True when each of the [METRIC_WEEKS] weeks before the current week has [METRIC_SESSIONS] qualifying sessions. */
    fun metricMet(
        attempts: List<AttemptRecord>,
        now: Instant,
    ): Boolean {
        val qualifying =
            sessions(attempts)
                .filter { it.answeredTasks >= QUALIFYING_TASKS }
                .groupingBy { PragueWeeks.weekStart(it.start) }
                .eachCount()
        val completed = PragueWeeks.recent(now, METRIC_WEEKS + 1).dropLast(1)
        return completed.all { (qualifying[it] ?: 0) >= METRIC_SESSIONS }
    }

    /** Resolved tasks among [taskIds] and those whose outcome is correct on the 1st attempt ([states]: absent = unresolved). */
    fun firstTry(
        taskIds: Collection<UUID>,
        states: Map<UUID, TaskState>,
    ): FirstTryCount {
        val outcomes = taskIds.distinct().mapNotNull { states[it] }.filter { it.resolved }
        return FirstTryCount(outcomes.size, outcomes.count { it == TaskState.CORRECT_FIRST_TRY })
    }
}
