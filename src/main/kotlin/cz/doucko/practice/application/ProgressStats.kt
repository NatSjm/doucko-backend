package cz.doucko.practice.application

import cz.doucko.content.ContentCatalog
import cz.doucko.practice.domain.AttemptRecord
import cz.doucko.practice.domain.GradablePart
import cz.doucko.practice.domain.Grade
import cz.doucko.practice.domain.Reevaluation
import cz.doucko.practice.domain.ReevaluationDecision
import cz.doucko.practice.domain.ReevaluationRules
import cz.doucko.practice.domain.ReportRecord
import cz.doucko.practice.domain.ReportStatus
import cz.doucko.practice.domain.Scope
import cz.doucko.practice.domain.StatsRules
import cz.doucko.practice.domain.TaskProgress
import cz.doucko.practice.domain.TaskState
import cz.doucko.practice.domain.WeekFigures
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/** First-try figures of one topic (contract `Stats.firstTryRateByTopic`). */
data class TopicFirstTry(
    val topicId: UUID,
    val name: String,
    val resolved: Int,
    val firstTry: Int,
)

/** The admin statistics (contract `Stats`). */
data class StatsData(
    val weeks: List<WeekFigures>,
    val topics: List<TopicFirstTry>,
    val metricMet: Boolean,
)

/** A flag-queue entry (contract `Report`): the report, the start of its task's prompt and the attached attempt as logged. */
data class ReportView(
    val report: ReportRecord,
    val taskPromptExcerpt: String,
    val attempt: AttemptRecord?,
)

/** The result of a re-evaluation (contract `ReevaluationResult`). */
data class ReevaluationOutcome(
    val changed: Boolean,
    val xp: XpOutcome,
    val report: ReportView,
    val newTaskState: TaskState?,
)

/**
 * The admin practice statistics (store spec progress-stats "Practice statistics page"; FR-40; design
 * decisions 2–5): weekly figures from the whole attempt log and the opened hints, the success metric and the
 * first-try counts per topic from the first play-through, re-evaluations included.
 */
@Service
@Transactional(readOnly = true)
class ProgressStats(
    private val store: PracticeStore,
    private val catalog: ContentCatalog,
) {
    fun stats(
        weeks: Int,
        now: Instant = Instant.now(),
    ): StatsData {
        val attempts = store.allAttempts()
        val primary =
            attempts
                .filter { it.scope == Scope.PRIMARY }
                .groupBy { it.taskId }
                .mapValues { (_, list) -> TaskProgress(list).state }
        val topics =
            catalog.topics().map { topic ->
                val count = StatsRules.firstTry(topic.taskIds, primary)
                TopicFirstTry(topic.topicId, topic.name, count.resolved, count.firstTry)
            }
        return StatsData(StatsRules.weeks(attempts, store.allHints(), now, weeks), topics, StatsRules.metricMet(attempts, now))
    }
}

/**
 * The flag queue (store spec progress-stats "Flag queue", "Re-evaluation of a reported attempt", "Dismissing a
 * report"; FR-41, OQ-20; design decisions 6–10). Writes take the practice lock first, like every practice
 * write, so a re-evaluation never races an attempt or another re-evaluation.
 */
@Service
@Transactional(readOnly = true)
class FlagQueue(
    private val store: PracticeStore,
    private val catalog: ContentCatalog,
    private val gamification: Gamification,
) {
    fun list(status: ReportStatus?): List<ReportView> = store.reports(status).map(::view)

    /** Re-checks the attached attempt with the task's current answer spec; only ever adds XP. */
    @Transactional
    fun reevaluate(
        reportId: UUID,
        now: Instant = Instant.now(),
    ): ReevaluationOutcome {
        store.lock()
        val report = open(reportId)
        val attempt = report.attemptId?.let(store::attempt) ?: throw PracticeConflict("report-no-attempt")
        val parts =
            catalog
                .task(report.taskId)
                ?.parts
                .orEmpty()
                .map { GradablePart(it.key, it.spec) }
        val grade = if (parts.isEmpty()) null else ReevaluationRules.regrade(parts, attempt)
        val decision = ReevaluationRules.decide(attempt, grade, store.attempts(Scope.PRIMARY, listOf(attempt.taskId)))
        if (decision == ReevaluationDecision.NoChange || grade !is Grade.Graded) {
            return ReevaluationOutcome(false, XpOutcome.NONE, view(report), null)
        }
        store.insertReevaluation(attempt.id, report.id, Reevaluation(grade.parts.associate { it.key to it.verdict }, grade.correct, now))
        val (xp, state) =
            when (decision) {
                is ReevaluationDecision.Improved -> gamification.onReevaluation(attempt, decision.state, report.id, now) to decision.state
                else -> XpOutcome.NONE to null
            }
        store.closeReport(report.id, ReportStatus.FIXED)
        return ReevaluationOutcome(true, xp, view(checkNotNull(store.report(report.id))), state)
    }

    /** `OPEN` → `DISMISSED`; nothing else changes. */
    @Transactional
    fun dismiss(reportId: UUID): ReportView {
        store.lock()
        val report = open(reportId)
        store.closeReport(report.id, ReportStatus.DISMISSED)
        return view(checkNotNull(store.report(report.id)))
    }

    private fun open(reportId: UUID): ReportRecord {
        val report = store.report(reportId) ?: throw PracticeNotFound()
        if (report.status != ReportStatus.OPEN) throw PracticeConflict("report-not-open")
        return report
    }

    private fun view(report: ReportRecord) =
        ReportView(report, excerpt(catalog.task(report.taskId)?.prompt.orEmpty()), report.attemptId?.let(store::attempt))

    companion object {
        /** Code points of the prompt shown in `Report.taskPromptExcerpt`. */
        const val EXCERPT_LENGTH = 80

        /** The first [EXCERPT_LENGTH] code points of [prompt], with `…` when it is longer. */
        fun excerpt(prompt: String): String {
            if (prompt.codePointCount(0, prompt.length) <= EXCERPT_LENGTH) return prompt
            return prompt.substring(0, prompt.offsetByCodePoints(0, EXCERPT_LENGTH)).trimEnd() + "…"
        }
    }
}
