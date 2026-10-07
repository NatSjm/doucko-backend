package cz.doucko.practice

import cz.doucko.practice.adapter.out.tutor.DeterministicTutor
import cz.doucko.practice.application.FlagQueue
import cz.doucko.practice.application.Gamification
import cz.doucko.practice.application.GamificationSettings
import cz.doucko.practice.application.PracticeService
import cz.doucko.practice.application.PracticeStore
import cz.doucko.practice.application.ProgressStats
import cz.doucko.practice.application.XpLedger
import cz.doucko.practice.domain.AttemptRecord
import cz.doucko.practice.domain.AwardKind
import cz.doucko.practice.domain.HintRecord
import cz.doucko.practice.domain.Reevaluation
import cz.doucko.practice.domain.ReportRecord
import cz.doucko.practice.domain.ReportStatus
import cz.doucko.practice.domain.RunRecord
import cz.doucko.practice.domain.XpAward
import org.springframework.boot.logging.LogLevel
import org.springframework.boot.logging.LoggingSystem
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** [PracticeStore] in memory, for `@WebMvcTest` slices (the child controllers need the practice use case). */
class InMemoryPracticeStore : PracticeStore {
    val attempts = CopyOnWriteArrayList<AttemptRecord>()
    val hints = CopyOnWriteArrayList<HintRecord>()
    val reports = CopyOnWriteArrayList<ReportRecord>()
    val runs = CopyOnWriteArrayList<RunRecord>()

    override fun lock() = Unit

    override fun attempts(
        scope: UUID,
        taskIds: Collection<UUID>,
    ) = attempts.filter { it.scope == scope && it.taskId in taskIds }

    override fun attemptsIn(scope: UUID) = attempts.filter { it.scope == scope }

    override fun hints(
        scope: UUID,
        taskIds: Collection<UUID>,
    ) = hints.filter { it.scope == scope && it.taskId in taskIds }.sortedBy { it.index }

    override fun insertAttempt(attempt: AttemptRecord) {
        attempts += attempt
    }

    override fun insertHint(hint: HintRecord) {
        hints += hint
    }

    override fun insertReport(report: ReportRecord) {
        reports += report
    }

    override fun runs(missionId: UUID) = runs.filter { it.missionId == missionId }.sortedBy { it.startedAt }

    override fun insertRun(run: RunRecord) {
        runs += run
    }

    override fun finishRun(
        runId: UUID,
        at: java.time.Instant,
    ) {
        runs.replaceAll { if (it.id == runId && it.finishedAt == null) it.copy(finishedAt = at) else it }
    }

    override fun attemptCounts(taskIds: Collection<UUID>) = attempts.filter { it.taskId in taskIds }.groupingBy { it.taskId }.eachCount()

    override fun usedTasks(taskIds: Collection<UUID>) =
        (attempts.map { it.taskId } + hints.map { it.taskId } + reports.map { it.taskId }).filter { it in taskIds }.toSet()

    override fun allAttempts() = attempts.sortedWith(compareBy({ it.createdAt }, { it.id }))

    override fun allHints() = hints.sortedBy { it.openedAt }

    override fun attempt(id: UUID) = attempts.firstOrNull { it.id == id }

    override fun attemptsById(ids: Collection<UUID>) = attempts.filter { it.id in ids }

    override fun reports(status: ReportStatus?) =
        reports
            .filter { status == null || it.status == status }
            .sortedWith(compareBy({ it.status != ReportStatus.OPEN }, { it.createdAt }, { it.id }))

    override fun report(id: UUID) = reports.firstOrNull { it.id == id }

    override fun insertReevaluation(
        attemptId: UUID,
        reportId: UUID,
        reevaluation: Reevaluation,
    ) {
        attempts.replaceAll { if (it.id == attemptId) it.copy(reevaluation = reevaluation) else it }
    }

    override fun closeReport(
        reportId: UUID,
        status: ReportStatus,
    ): Boolean {
        val open = reports.firstOrNull { it.id == reportId && it.status == ReportStatus.OPEN } ?: return false
        reports.replaceAll { if (it === open) it.copy(status = status) else it }
        return true
    }
}

/** [XpLedger] in memory, for `@WebMvcTest` slices. */
class InMemoryXpLedger : XpLedger {
    val awards = CopyOnWriteArrayList<XpAward>()
    val celebrations = CopyOnWriteArrayList<Int>()

    override fun awards() = awards.toList()

    override fun awardsOf(attemptId: UUID) = awards.filter { it.attemptId == attemptId }

    override fun total() = awards.sumOf { it.amount }

    override fun taskAwarded(taskId: UUID) = awards.any { it.kind != AwardKind.MISSION_BONUS && it.taskId == taskId }

    override fun taskXp(taskId: UUID) = awards.filter { it.kind != AwardKind.MISSION_BONUS && it.taskId == taskId }.sumOf { it.amount }

    override fun bonusesPaid(missionIds: Collection<UUID>) =
        awards.filter { it.kind == AwardKind.MISSION_BONUS && it.missionId in missionIds }.map { it.missionId }.toSet()

    override fun insert(award: XpAward) {
        awards += award.copy(seq = awards.size + 1L)
    }

    override fun celebrated() = celebrations.maxOrNull() ?: 0

    override fun celebrate(
        index: Int,
        at: java.time.Instant,
    ) {
        celebrations += index
    }
}

/** The practice use case over an in-memory store, for `@WebMvcTest` slices (every child controller needs it). */
@TestConfiguration(proxyBeanMethods = false)
@Import(
    PracticeService::class,
    DeterministicTutor::class,
    Gamification::class,
    GamificationSettings::class,
    ProgressStats::class,
    FlagQueue::class,
)
class PracticeWebTestBeans {
    @Bean
    fun practiceStore() = InMemoryPracticeStore()

    @Bean
    fun xpLedger() = InMemoryXpLedger()
}

/**
 * Empties the practice and gamification tables before content is deleted. The XP ledger and the
 * re-evaluations refuse DELETE (FR-38, FR-42), so they are truncated; the other tables keep the slice-7 DELETE order.
 */
fun JdbcTemplate.cleanPractice() {
    execute("TRUNCATE xp_award, rank_celebration, attempt_reevaluation")
    for (table in listOf("problem_report", "opened_hint", "attempt", "practice_run")) update("DELETE FROM $table")
}

/**
 * Puts the loggers a practice test may raise back to the configured levels. Log levels are JVM-global, so a
 * TRACE context (PracticeLogCaptureIT) would otherwise slow down every later test in the same JVM.
 */
fun configuredLogLevels(loader: ClassLoader) {
    val logging = LoggingSystem.get(loader)
    logging.setLogLevel(LoggingSystem.ROOT_LOGGER_NAME, LogLevel.INFO)
    listOf(
        "cz.doucko",
        "org.springframework",
        "org.springframework.jdbc",
        "org.springframework.transaction",
        "org.hibernate",
        "org.postgresql",
    ).forEach { logging.setLogLevel(it, null) }
    listOf("org.springframework.web", "org.springframework.security").forEach { logging.setLogLevel(it, LogLevel.INFO) }
}
