package cz.doucko.practice.application

import cz.doucko.content.PublishedMissionChanged
import cz.doucko.content.ServedContent
import cz.doucko.content.ServedMission
import cz.doucko.practice.domain.AttemptRecord
import cz.doucko.practice.domain.AwardKind
import cz.doucko.practice.domain.GamificationConfig
import cz.doucko.practice.domain.Rank
import cz.doucko.practice.domain.RunRanks
import cz.doucko.practice.domain.RunXp
import cz.doucko.practice.domain.Scope
import cz.doucko.practice.domain.TaskProgress
import cz.doucko.practice.domain.TaskState
import cz.doucko.practice.domain.XpAward
import cz.doucko.practice.domain.XpRules
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/** What one attempt earned (contract `AttemptResult` XP fields); [crossing] is the highest rank its awards reached, if any. */
data class XpOutcome(
    val xpAwarded: Int,
    val bonusXp: Int,
    val completedMissionIds: List<UUID>,
    val crossing: Rank?,
) {
    companion object {
        val NONE = XpOutcome(0, 0, emptyList(), null)
    }
}

/** HQ progress (contract `HqView`): total, rank, next rank (null at the top) and the uncelebrated rank-up. */
data class XpProgress(
    val totalXp: Int,
    val rank: Rank,
    val nextRank: Rank?,
    val pendingRankUp: Rank?,
)

/**
 * Gamification (store spec gamification; FR-32..FR-38; design decisions 3–9): task awards and completion
 * bonuses into the append-only ledger, ranks, one-shot rank-up celebrations and the XP of summaries and
 * briefings. Writers are called with the practice lock held, so every check-then-insert is serialized.
 */
@Component
@Transactional(readOnly = true)
class Gamification(
    private val config: GamificationConfig,
    private val served: ServedContent,
    private val store: PracticeStore,
    private val ledger: XpLedger,
) {
    private val ladder get() = config.ladder

    /**
     * Awards for a stored attempt (caller holds the lock): only a primary-scope attempt that resolved its
     * task earns, once per task; then every served mission containing the task that is now complete and was
     * never paid gets its bonus (OQ-15, OQ-28).
     */
    @Transactional
    fun onAttempt(
        attempt: AttemptRecord,
        state: TaskState,
    ): XpOutcome {
        if (attempt.scope != Scope.PRIMARY || !state.resolved) return XpOutcome.NONE
        val awards = ledger.awards()
        if (awards.any { it.kind == AwardKind.TASK && it.taskId == attempt.taskId }) return XpOutcome.NONE
        val before = awards.sumOf { it.amount }
        val amount = XpRules.taskAward(config.xp, state, attempt.hintsUsed)
        ledger.insert(award(AwardKind.TASK, attempt.taskId, attempt.missionId, attempt.id, amount, attempt.createdAt))
        val paid = awards.filter { it.kind == AwardKind.MISSION_BONUS }.map { it.missionId }.toSet()
        val completed =
            served
                .missionsContaining(attempt.taskId)
                .filter { attempt.taskId in it.taskIds && it.missionId !in paid && complete(it) }
                .map { it.missionId }
        completed.forEach { ledger.insert(award(AwardKind.MISSION_BONUS, null, it, attempt.id, config.xp.missionBonus, attempt.createdAt)) }
        val bonus = completed.size * config.xp.missionBonus
        return XpOutcome(amount, bonus, completed, ladder.crossing(before, before + amount + bonus))
    }

    /** Stores that the celebration of [rank] played now (a mid-mission `AttemptResult.rankUp`). */
    @Transactional
    fun celebrated(
        rank: Rank,
        at: Instant,
    ) {
        if (rank.index > ledger.celebrated()) ledger.celebrate(rank.index, at)
    }

    /** OQ-28: a mission published (or edited while published) when all its tasks are resolved earns its bonus now. */
    @EventListener
    @Transactional
    fun onPublishedMissionChanged(event: PublishedMissionChanged) {
        store.lock()
        val mission = served.mission(event.missionId) ?: return
        val paid = ledger.awards().any { it.kind == AwardKind.MISSION_BONUS && it.missionId == mission.missionId }
        if (!paid && complete(mission)) {
            ledger.insert(award(AwardKind.MISSION_BONUS, null, mission.missionId, null, config.xp.missionBonus, Instant.now()))
        }
    }

    fun progress(): XpProgress {
        val total = ledger.awards().sumOf { it.amount }
        val rank = ladder.rankFor(total)
        return XpProgress(total, rank, ladder.nextAfter(rank), rank.takeIf { it.index > ledger.celebrated() })
    }

    /** `ackRankUp` (design decision 6): clears the pending rank-up when its index is at most [rankIndex]. */
    @Transactional
    fun acknowledge(rankIndex: Int) {
        store.lock()
        val current = ladder.rankFor(ledger.awards().sumOf { it.amount })
        val index = minOf(rankIndex, current.index)
        if (index > ledger.celebrated()) ledger.celebrate(index, Instant.now())
    }

    /** XP still earnable in [mission] (contract `MissionBriefing.xpAvailable`, design decision 8). */
    fun available(mission: ServedMission): Int {
        val states = primaryStates(mission.taskIds)
        val open = mission.taskIds.count { states[it]?.resolved != true }
        val paid = ledger.awards().any { it.kind == AwardKind.MISSION_BONUS && it.missionId == mission.missionId }
        return open * config.xp.firstAttempt + if (paid) 0 else config.xp.missionBonus
    }

    /** The XP and ranks of a completed run of [mission] in [scope] (design decision 7). */
    fun summary(
        mission: ServedMission,
        scope: UUID,
    ): RunRanks {
        val attempts = store.attempts(scope, mission.taskIds)
        val runAttempts = attempts.filter { it.missionId == mission.missionId }.map { it.id }.toSet()
        val completing =
            attempts
                .filter { it.correct || it.attemptNo == TaskProgress.MAX_ATTEMPTS }
                .maxByOrNull { it.createdAt }
                ?.id
        return RunXp.summarize(ledger.awards(), runAttempts, completing, ladder, ledger.celebrated())
    }

    /** The stored XP of one attempt, for `TaskView.lastFeedback` (design decision 9); never a rank-up. */
    fun ofAttempt(attemptId: UUID): XpOutcome {
        val rows = ledger.awards().filter { it.attemptId == attemptId }
        val bonuses = rows.filter { it.kind == AwardKind.MISSION_BONUS }
        return XpOutcome(
            rows
                .filter {
                    it.kind == AwardKind.TASK
                }.sumOf { it.amount },
            bonuses.sumOf { it.amount },
            bonuses.map { it.missionId },
            null,
        )
    }

    private fun complete(mission: ServedMission): Boolean {
        val states = primaryStates(mission.taskIds)
        return mission.taskIds.all { states[it]?.resolved == true }
    }

    private fun primaryStates(taskIds: List<UUID>): Map<UUID, TaskState> =
        store.attempts(Scope.PRIMARY, taskIds).groupBy { it.taskId }.mapValues { (_, list) -> TaskProgress(list).state }

    private fun award(
        kind: AwardKind,
        taskId: UUID?,
        missionId: UUID,
        attemptId: UUID?,
        amount: Int,
        at: Instant,
    ) = XpAward(0, UUID.randomUUID(), kind, taskId, missionId, attemptId, amount, at)
}
