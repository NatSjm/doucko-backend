package cz.doucko.practice

import cz.doucko.practice.adapter.out.tutor.DeterministicTutor
import cz.doucko.practice.application.Gamification
import cz.doucko.practice.application.GamificationSettings
import cz.doucko.practice.application.PracticeService
import cz.doucko.practice.application.PracticeStore
import cz.doucko.practice.application.XpLedger
import cz.doucko.practice.domain.AttemptRecord
import cz.doucko.practice.domain.HintRecord
import cz.doucko.practice.domain.ReportRecord
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
}

/** [XpLedger] in memory, for `@WebMvcTest` slices. */
class InMemoryXpLedger : XpLedger {
    val awards = CopyOnWriteArrayList<XpAward>()
    val celebrations = CopyOnWriteArrayList<Int>()

    override fun awards() = awards.toList()

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
@Import(PracticeService::class, DeterministicTutor::class, Gamification::class, GamificationSettings::class)
class PracticeWebTestBeans {
    @Bean
    fun practiceStore() = InMemoryPracticeStore()

    @Bean
    fun xpLedger() = InMemoryXpLedger()
}

/**
 * Empties the practice and gamification tables before content is deleted. The XP ledger refuses DELETE
 * (FR-38), so it is truncated; the other tables keep the slice-7 DELETE order.
 */
fun JdbcTemplate.cleanPractice() {
    execute("TRUNCATE xp_award, rank_celebration")
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
