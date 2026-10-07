package cz.doucko.practice

import cz.doucko.practice.domain.AwardKind
import cz.doucko.practice.domain.Rank
import cz.doucko.practice.domain.RankLadder
import cz.doucko.practice.domain.RunXp
import cz.doucko.practice.domain.TaskState
import cz.doucko.practice.domain.XpAward
import cz.doucko.practice.domain.XpConfig
import cz.doucko.practice.domain.XpRules
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Instant
import java.util.UUID

/** The pure gamification rules: award table, hint cost, minimum, rank ladder, crossings and run summaries. */
class GamificationRulesTest {
    private val defaults = XpConfig(firstAttempt = 10, secondAttempt = 5, revealed = 1, hintCost = 2, minimum = 1, missionBonus = 20)

    private fun ladder(vararg thresholds: Int) = RankLadder(thresholds.mapIndexed { i, xp -> Rank(i, "Hodnost ${i + 1}", xp) })

    private val placeholder = ladder(0, 150, 400, 800, 1500)

    // ---------------------------------------------------------------- XP per task (FR-34)

    // @trace FR-34
    @ParameterizedTest(name = "{0} after {1} hints earns {2} XP")
    @CsvSource(
        "CORRECT_FIRST_TRY, 0, 10",
        "CORRECT_FIRST_TRY, 2, 6",
        "CORRECT_FIRST_TRY, 5, 1",
        "CORRECT_SECOND_TRY, 0, 5",
        "CORRECT_SECOND_TRY, 1, 3",
        "CORRECT_SECOND_TRY, 3, 1",
        "REVEALED, 0, 1",
        "REVEALED, 3, 1",
    )
    fun `the award table`(
        state: TaskState,
        hints: Int,
        expected: Int,
    ) {
        XpRules.taskAward(defaults, state, hints) shouldBe expected
    }

    // @trace FR-34, FR-38
    @Test
    fun `an unresolved task earns nothing and no award is ever negative`() {
        XpRules.taskAward(defaults, TaskState.UNRESOLVED, 0) shouldBe 0
        for (state in TaskState.entries) {
            for (hints in 0..20) {
                (XpRules.taskAward(defaults, state, hints) >= 0) shouldBe true
            }
        }
    }

    // @trace FR-34
    @Test
    fun `values come from the configuration`() {
        XpRules.taskAward(defaults.copy(firstAttempt = 12), TaskState.CORRECT_FIRST_TRY, 0) shouldBe 12
        // Boundary configuration: hint cost 0, minimum 1, bonus 1000.
        XpRules.taskAward(defaults.copy(hintCost = 0, minimum = 1, missionBonus = 1000), TaskState.CORRECT_FIRST_TRY, 3) shouldBe 10
        XpRules.taskAward(defaults.copy(minimum = 4), TaskState.CORRECT_SECOND_TRY, 3) shouldBe 4
        XpRules.taskAward(defaults.copy(minimum = 3), TaskState.REVEALED, 0) shouldBe 3
        XpRules.taskAward(defaults.copy(revealed = 7), TaskState.REVEALED, 3) shouldBe 7
    }

    // ---------------------------------------------------------------- rank ladder (FR-36, FR-37)

    // @trace FR-36
    @Test
    fun `the rank is the highest threshold reached`() {
        listOf(0, 149, 150, 399, 400, 799, 800, 1499, 1500).map { placeholder.rankFor(it).index } shouldContainExactly
            listOf(0, 0, 1, 1, 2, 2, 3, 3, 4)
    }

    // @trace FR-37
    @Test
    fun `the next rank gives the XP bar and is absent at the top`() {
        val rank = placeholder.rankFor(275)
        rank.index shouldBe 1
        val next = placeholder.nextAfter(rank)!!
        (275 - rank.minXp) shouldBe 125
        (next.minXp - rank.minXp) shouldBe 250
        placeholder.nextAfter(placeholder.rankFor(0))!!.minXp shouldBe 150
        placeholder.nextAfter(placeholder.rankFor(1600)).shouldBeNull()
    }

    // @trace FR-36
    @Test
    fun `a crossing names the highest rank reached`() {
        placeholder.crossing(145, 155) shouldBe placeholder.ranks[1]
        placeholder.crossing(160, 170).shouldBeNull()
        ladder(0, 150, 160).crossing(140, 170)!!.index shouldBe 2
    }

    // ---------------------------------------------------------------- run summaries (FR-32, FR-33, FR-36)

    private var seq = 0L
    private val mission = UUID.randomUUID()

    private fun row(
        amount: Int,
        attempt: UUID?,
        kind: AwardKind = AwardKind.TASK,
    ) = XpAward(
        ++seq,
        UUID.randomUUID(),
        kind,
        if (kind == AwardKind.TASK) UUID.randomUUID() else null,
        mission,
        attempt,
        amount,
        Instant.EPOCH,
    )

    // @trace FR-32
    @Test
    fun `the summary sums the task XP and the bonus of the run`() {
        val attempts = List(10) { UUID.randomUUID() }
        val amounts = List(7) { 10 } + List(2) { 5 } + 1
        val ledger = attempts.zip(amounts).map { (a, xp) -> row(xp, a) } + row(20, attempts.last(), AwardKind.MISSION_BONUS)
        RunXp.summarize(ledger, attempts.toSet(), attempts.last(), placeholder, 0).xpEarned shouldBe 101
    }

    // @trace FR-32
    @Test
    fun `a task resolved in another mission contributes nothing`() {
        val other = row(10, UUID.randomUUID())
        val attempts = List(9) { UUID.randomUUID() }
        val ledger = listOf(other) + attempts.map { row(10, it) } + row(20, attempts.last(), AwardKind.MISSION_BONUS)
        RunXp.summarize(ledger, attempts.toSet(), attempts.last(), placeholder, 0).xpEarned shouldBe 110
    }

    // @trace FR-33
    @Test
    fun `a replay run earns nothing and shows no rank`() {
        val ledger = List(3) { row(10, UUID.randomUUID()) }
        val summary = RunXp.summarize(ledger, setOf(UUID.randomUUID()), UUID.randomUUID(), placeholder, 0)
        summary.xpEarned shouldBe 0
        summary.rankUp.shouldBeNull()
        summary.rankReachedDuringRun.shouldBeNull()
    }

    // @trace FR-36
    @Test
    fun `a completing crossing is offered until celebrated, then shown as a static line`() {
        val earlier = row(140, UUID.randomUUID())
        val last = UUID.randomUUID()
        val ledger = listOf(earlier, row(10, last), row(20, last, AwardKind.MISSION_BONUS))
        val fresh = RunXp.summarize(ledger, setOf(last), last, placeholder, 0)
        fresh.rankUp shouldBe placeholder.ranks[1]
        fresh.rankReachedDuringRun.shouldBeNull()
        fresh.xpEarned shouldBe 30
        val acknowledged = RunXp.summarize(ledger, setOf(last), last, placeholder, 1)
        acknowledged.rankUp.shouldBeNull()
        acknowledged.rankReachedDuringRun shouldBe placeholder.ranks[1]
    }

    // @trace FR-36
    @Test
    fun `a crossing earlier in the run is a static line on the summary`() {
        val start = row(140, UUID.randomUUID())
        val third = UUID.randomUUID()
        val last = UUID.randomUUID()
        val ledger = listOf(start, row(10, third), row(10, last), row(20, last, AwardKind.MISSION_BONUS))
        val summary = RunXp.summarize(ledger, setOf(third, last), last, placeholder, 1)
        summary.rankUp.shouldBeNull()
        summary.rankReachedDuringRun shouldBe placeholder.ranks[1]
    }

    // @trace FR-36
    @Test
    fun `no crossing in the run shows no rank`() {
        val last = UUID.randomUUID()
        val ledger = listOf(row(160, UUID.randomUUID()), row(10, last))
        val summary = RunXp.summarize(ledger, setOf(last), last, placeholder, 1)
        summary.rankUp.shouldBeNull()
        summary.rankReachedDuringRun.shouldBeNull()
    }

    // @trace FR-36
    @Test
    fun `one completion crossing two thresholds names only the highest`() {
        val three = ladder(0, 150, 160)
        val last = UUID.randomUUID()
        val ledger = listOf(row(140, UUID.randomUUID()), row(10, last), row(20, last, AwardKind.MISSION_BONUS))
        RunXp.summarize(ledger, setOf(last), last, three, 0).rankUp!!.index shouldBe 2
    }

    // @trace FR-36
    @Test
    fun `an earlier crossing stays a static line next to a new completing rank-up`() {
        val third = UUID.randomUUID()
        val last = UUID.randomUUID()
        val ledger = listOf(row(140, UUID.randomUUID()), row(10, third), row(240, last), row(20, last, AwardKind.MISSION_BONUS))
        val summary = RunXp.summarize(ledger, setOf(third, last), last, placeholder, 1)
        summary.rankUp shouldBe placeholder.ranks[2]
        summary.rankReachedDuringRun shouldBe placeholder.ranks[1]
    }
}
