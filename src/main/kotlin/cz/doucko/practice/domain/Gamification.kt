package cz.doucko.practice.domain

import java.time.Instant
import java.util.UUID

/**
 * The XP values of gamification "XP per task" and "Mission completion bonus" (FR-34, FR-35), validated
 * when the backend starts (OQ-29): task awards, hint cost and bonus 0..1000, minimum 1..1000.
 */
data class XpConfig(
    val firstAttempt: Int,
    val secondAttempt: Int,
    val revealed: Int,
    val hintCost: Int,
    val minimum: Int,
    val missionBonus: Int,
)

/** XP values and rank ladder: everything gamification reads from its configuration file (design decision 2). */
data class GamificationConfig(
    val xp: XpConfig,
    val ladder: RankLadder,
)

/** The task award table (FR-34, OQ-4; design decision 3). */
object XpRules {
    /**
     * The award for a task's first resolution as [state] with [hints] opened before it: correct awards lose
     * the hint cost per hint but never fall below the minimum; a reveal earns the revealed award (at least
     * the minimum). An unresolved state earns nothing.
     */
    fun taskAward(
        config: XpConfig,
        state: TaskState,
        hints: Int,
    ): Int =
        when (state) {
            TaskState.UNRESOLVED -> 0
            TaskState.CORRECT_FIRST_TRY -> maxOf(config.minimum, config.firstAttempt - hints * config.hintCost)
            TaskState.CORRECT_SECOND_TRY -> maxOf(config.minimum, config.secondAttempt - hints * config.hintCost)
            TaskState.REVEALED -> maxOf(config.minimum, config.revealed)
        }
}

/** A rank of the ladder (contract `Rank`): 0-based [index], name and threshold. */
data class Rank(
    val index: Int,
    val name: String,
    val minXp: Int,
)

/** The rank ladder (FR-36): ordered, first threshold 0, strictly increasing (checked by the config parser). */
class RankLadder(
    val ranks: List<Rank>,
) {
    init {
        require(ranks.isNotEmpty() && ranks[0].minXp == 0) { "ladder must start at 0" }
        require(ranks.zipWithNext().all { (a, b) -> a.minXp < b.minXp }) { "ladder must strictly increase" }
        require(ranks.withIndex().all { (i, r) -> r.index == i }) { "ranks must be indexed in order" }
    }

    /** The highest rank whose threshold [xp] has reached. */
    fun rankFor(xp: Int): Rank = ranks.last { it.minXp <= xp }

    /** The rank after [rank], or null at the top. */
    fun nextAfter(rank: Rank): Rank? = ranks.getOrNull(rank.index + 1)

    /** The highest rank reached when the total moves from [before] to [after], or null when no threshold is crossed. */
    fun crossing(
        before: Int,
        after: Int,
    ): Rank? = rankFor(after).takeIf { it.index > rankFor(before).index }
}

/** What an XP ledger row pays for. */
enum class AwardKind { TASK, MISSION_BONUS }

/**
 * One row of the append-only XP ledger (FR-38; design decision 5). [seq] orders the ledger; [attemptId] is
 * the resolution that caused the row, null for a bonus credited when a mission was published complete.
 * A `TASK` row's [missionId] is the mission the task was resolved through.
 */
data class XpAward(
    val seq: Long,
    val id: UUID,
    val kind: AwardKind,
    val taskId: UUID?,
    val missionId: UUID,
    val attemptId: UUID?,
    val amount: Int,
    val createdAt: Instant,
)

/** The XP part of a mission summary (contract `MissionSummary`, FR-32). */
data class RunRanks(
    val xpEarned: Int,
    val rankUp: Rank?,
    val rankReachedDuringRun: Rank?,
)

/** Summary arithmetic over the ledger (design decision 7). Pure: the caller loads the ledger and the run. */
object RunXp {
    /**
     * [ledger] in `seq` order; [runAttempts] the attempts of the run through this mission; [completing] the
     * latest resolving attempt of the mission's tasks in the run's scope; [celebrated] the highest rank index
     * whose celebration has played.
     */
    fun summarize(
        ledger: List<XpAward>,
        runAttempts: Set<UUID>,
        completing: UUID?,
        ladder: RankLadder,
        celebrated: Int,
    ): RunRanks {
        val ordered = ledger.sortedBy { it.seq }
        val run = ordered.filter { it.attemptId != null && it.attemptId in runAttempts }
        val earned = run.sumOf { it.amount }

        fun totalBefore(seq: Long) = ordered.filter { it.seq < seq }.sumOf { it.amount }

        fun totalThrough(seq: Long) = ordered.filter { it.seq <= seq }.sumOf { it.amount }
        val closing = ordered.filter { completing != null && it.attemptId == completing }
        val rankUp =
            if (closing.isEmpty()) {
                null
            } else {
                ladder.crossing(totalBefore(closing.first().seq), totalThrough(closing.last().seq))?.takeIf { it.index > celebrated }
            }
        val considered = (run + closing).distinctBy { it.seq }.sortedBy { it.seq }
        if (considered.isEmpty()) return RunRanks(earned, null, null)
        val start = totalBefore(considered.first().seq)
        val reached =
            if (rankUp != null) {
                ladder.crossing(start, totalBefore(closing.first().seq))
            } else {
                ladder.crossing(start, totalThrough(considered.last().seq))?.takeIf { it.index <= celebrated }
            }
        return RunRanks(earned, rankUp, reached)
    }
}
