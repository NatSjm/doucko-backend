package cz.doucko.practice.adapter.`in`.web

import cz.doucko.api.AdminStatsApi
import cz.doucko.api.model.LoggedAttempt
import cz.doucko.api.model.LoggedAttemptReevaluation
import cz.doucko.api.model.ReevaluationResult
import cz.doucko.api.model.Report
import cz.doucko.api.model.ReportReason
import cz.doucko.api.model.ReportStatus
import cz.doucko.api.model.Stats
import cz.doucko.api.model.StatsFirstTryRateByTopicInner
import cz.doucko.api.model.TaskState
import cz.doucko.api.model.Verdict
import cz.doucko.api.model.WeekStats
import cz.doucko.practice.application.FlagQueue
import cz.doucko.practice.application.InvalidPracticeInput
import cz.doucko.practice.application.ProgressStats
import cz.doucko.practice.application.ReevaluationOutcome
import cz.doucko.practice.application.ReportView
import cz.doucko.practice.application.StatsData
import cz.doucko.practice.domain.AttemptRecord
import cz.doucko.practice.domain.FieldError
import cz.doucko.practice.domain.StatsRules
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import cz.doucko.practice.domain.ReportStatus as DomainReportStatus

/**
 * `AdminStatsApi` (store spec progress-stats; FR-40, FR-41): the statistics, the flag queue, re-evaluation and
 * dismiss. Admin-only through the admin security chain (session, CSRF on POST), which runs before any input
 * is read. `weeks` and `status` are pattern-checked by the generated interface; they are checked again here
 * so that no other value can ever reach the use cases.
 */
@RestController
class AdminStatsController(
    private val stats: ProgressStats,
    private val queue: FlagQueue,
) : AdminStatsApi {
    override fun getStats(weeks: String?): ResponseEntity<Stats> {
        val count =
            when {
                weeks == null -> StatsRules.DEFAULT_WEEKS
                WEEKS.matches(weeks) -> weeks.toInt()
                else -> throw InvalidPracticeInput(listOf(FieldError("weeks", "weeks")))
            }
        return ResponseEntity.ok(StatsMapping.stats(stats.stats(count)))
    }

    override fun listReports(status: String?): ResponseEntity<List<Report>> {
        val filter =
            status?.let { value ->
                DomainReportStatus.entries.firstOrNull { it.name == value }
                    ?: throw InvalidPracticeInput(listOf(FieldError("status", "status")))
            }
        return ResponseEntity.ok(queue.list(filter).map(StatsMapping::report))
    }

    override fun reevaluateReport(reportId: UUID): ResponseEntity<ReevaluationResult> =
        ResponseEntity.ok(StatsMapping.reevaluation(queue.reevaluate(reportId)))

    override fun dismissReport(reportId: UUID): ResponseEntity<Report> = ResponseEntity.ok(StatsMapping.report(queue.dismiss(reportId)))

    private companion object {
        /** The contract's `weeks` pattern: ASCII 1–52, no sign, no leading zero. */
        val WEEKS = Regex("^([1-9]|[1-4][0-9]|5[0-2])$")
    }
}

/** Statistics and flag-queue views → generated contract models (NFR-12). Times are written in UTC. */
internal object StatsMapping {
    fun stats(data: StatsData) =
        Stats(
            weeks =
                data.weeks.map {
                    WeekStats(it.weekStart, it.sessions, it.qualifyingSessions, it.tasksPerSession, it.hintsUsed, it.revealed)
                },
            firstTryRateByTopic = data.topics.map { StatsFirstTryRateByTopicInner(it.topicId, it.name, it.resolved, it.firstTry) },
            metricMet = data.metricMet,
        )

    fun report(view: ReportView) =
        Report(
            reportId = view.report.id,
            taskId = view.report.taskId,
            reason = ReportReason.forValue(view.report.reason.name),
            note = view.report.note,
            status = ReportStatus.forValue(view.report.status.name),
            createdAt = time(view.report.createdAt),
            attempt = view.attempt?.let(::attempt),
            taskPromptExcerpt = view.taskPromptExcerpt,
        )

    fun reevaluation(outcome: ReevaluationOutcome) =
        ReevaluationResult(
            changed = outcome.changed,
            newTaskState = outcome.newTaskState?.let { TaskState.forValue(it.name) },
            xpAwarded = outcome.xp.xpAwarded,
            report = report(outcome.report),
            bonusXp = outcome.xp.bonusXp,
            completedMissionIds = outcome.xp.completedMissionIds,
        )

    private fun attempt(record: AttemptRecord) =
        LoggedAttempt(
            attemptId = record.id,
            attemptNo = record.attemptNo,
            rawInput = record.parts.mapNotNull { part -> part.raw?.let { part.key to RevealedAnswers.logged(it) } }.toMap(),
            normalized = record.parts.associate { it.key to it.normalized },
            verdicts = record.parts.associate { it.key to Verdict.forValue(it.verdict.name) },
            hintsUsed = record.hintsUsed,
            createdAt = time(record.createdAt),
            replay = record.replay,
            reevaluation =
                record.reevaluation?.let { re ->
                    LoggedAttemptReevaluation(re.verdicts.mapValues { (_, v) -> Verdict.forValue(v.name) }, time(re.at))
                },
        )

    private fun time(at: Instant) = at.atOffset(ZoneOffset.UTC)
}
