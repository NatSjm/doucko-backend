package cz.doucko.practice

import cz.doucko.checking.Verdict
import cz.doucko.content.PublishedMissionChanged
import cz.doucko.content.ServedContent
import cz.doucko.content.ServedImage
import cz.doucko.content.ServedMission
import cz.doucko.content.ServedTask
import cz.doucko.practice.application.Gamification
import cz.doucko.practice.application.GamificationConfigParser
import cz.doucko.practice.application.InvalidGamificationConfig
import cz.doucko.practice.domain.AttemptRecord
import cz.doucko.practice.domain.PartRecord
import cz.doucko.practice.domain.Rank
import cz.doucko.practice.domain.RankLadder
import cz.doucko.practice.domain.Scope
import cz.doucko.practice.domain.TaskState
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The gamification use case over in-memory stores (the edge paths the HTTP tests do not reach), the ladder's
 * own invariants and the parser's shape errors.
 */
class GamificationServiceTest {
    private val config = GamificationConfigParser.parse(javaClass.getResource("/gamification.json")!!.readText())
    private val store = InMemoryPracticeStore()
    private val ledger = InMemoryXpLedger()
    private val missions = mutableListOf<ServedMission>()
    private val served =
        object : ServedContent {
            override fun missions() = missions.toList()

            override fun missionsContaining(taskId: UUID) = missions.filter { taskId in it.taskIds }

            override fun mission(missionId: UUID) = missions.firstOrNull { it.missionId == missionId }

            override fun isServed(taskId: UUID) = missions.any { taskId in it.taskIds }

            override fun task(taskId: UUID): ServedTask? = null

            override fun image(imageId: UUID): ServedImage? = null
        }
    private val gamification = Gamification(config, served, store, ledger)

    private fun attempt(
        task: UUID,
        mission: UUID,
        no: Int,
        correct: Boolean,
        scope: UUID = Scope.PRIMARY,
        hints: Int = 0,
    ) = AttemptRecord(
        UUID.randomUUID(),
        task,
        mission,
        scope,
        scope != Scope.PRIMARY,
        no,
        hints,
        correct,
        listOf(PartRecord("a", null, "12", if (correct) Verdict.CORRECT else Verdict.WRONG, false)),
        Instant.now(),
    ).also(store.attempts::add)

    // @trace FR-34
    @Test
    fun `only a first primary-scope resolution earns`() {
        val (t, m) = UUID.randomUUID() to UUID.randomUUID()
        missions += ServedMission(m, UUID.randomUUID(), 1, listOf(t, UUID.randomUUID()))
        gamification.onAttempt(attempt(t, m, 1, false), TaskState.UNRESOLVED).xpAwarded shouldBe 0
        gamification
            .onAttempt(
                attempt(t, UUID.randomUUID(), 1, true, scope = UUID.randomUUID()),
                TaskState.CORRECT_FIRST_TRY,
            ).xpAwarded shouldBe
            0
        gamification.onAttempt(attempt(t, m, 2, true, hints = 1), TaskState.CORRECT_SECOND_TRY).xpAwarded shouldBe 3
        gamification.onAttempt(attempt(t, m, 2, true), TaskState.CORRECT_SECOND_TRY).xpAwarded shouldBe 0
        ledger.total() shouldBe 3
    }

    // @trace FR-36
    @Test
    fun `a celebration never moves the celebrated rank down`() {
        gamification.celebrated(config.ladder.ranks[3], Instant.now())
        gamification.celebrated(config.ladder.ranks[1], Instant.now())
        ledger.celebrations shouldContainExactly listOf(3)
    }

    // @trace FR-35
    @Test
    fun `an unserved or incomplete mission earns nothing when it changes`() {
        val (t, m) = UUID.randomUUID() to UUID.randomUUID()
        gamification.onPublishedMissionChanged(PublishedMissionChanged(m))
        missions += ServedMission(m, UUID.randomUUID(), 1, listOf(t))
        gamification.onPublishedMissionChanged(PublishedMissionChanged(m))
        ledger.total() shouldBe 0
        attempt(t, UUID.randomUUID(), 1, true)
        gamification.onPublishedMissionChanged(PublishedMissionChanged(m))
        gamification.onPublishedMissionChanged(PublishedMissionChanged(m))
        ledger.total() shouldBe 20
    }

    // @trace FR-34, FR-35
    @Test
    fun `the reconciliation skips unresolved and already credited tasks`() {
        val (open, revealed, credited, m) = List(4) { UUID.randomUUID() }
        missions += ServedMission(m, UUID.randomUUID(), 1, listOf(open, revealed, credited))
        attempt(open, m, 1, false)
        attempt(revealed, m, 1, false)
        attempt(revealed, m, 2, false, hints = 2)
        gamification.onAttempt(attempt(credited, m, 1, true), TaskState.CORRECT_FIRST_TRY).xpAwarded shouldBe 10
        gamification.reconcile() shouldBe 1
        ledger.total() shouldBe 11
        gamification.available(missions[0]) shouldBe 30
    }

    // @trace FR-32
    @Test
    fun `a summary without a completing attempt has no rank`() {
        val m = ServedMission(UUID.randomUUID(), UUID.randomUUID(), 1, listOf(UUID.randomUUID()))
        gamification.summary(m, Scope.PRIMARY).let {
            it.xpEarned shouldBe 0
            it.rankUp.shouldBeNull()
        }
        gamification.ofAttempt(UUID.randomUUID()).completedMissionIds shouldBe emptyList()
    }

    // @trace FR-36
    @Test
    fun `the ladder refuses a list the parser would never build`() {
        shouldThrow<IllegalArgumentException> { RankLadder(emptyList()) }
        shouldThrow<IllegalArgumentException> { RankLadder(listOf(Rank(0, "A", 5))) }
        shouldThrow<IllegalArgumentException> { RankLadder(listOf(Rank(0, "A", 0), Rank(1, "B", 0))) }
        shouldThrow<IllegalArgumentException> { RankLadder(listOf(Rank(0, "A", 0), Rank(2, "B", 10))) }
        RankLadder(listOf(Rank(0, "A", 0))).nextAfter(Rank(0, "A", 0)).shouldBeNull()
    }

    // @trace FR-34, FR-39
    @Test
    fun `wrong JSON shapes name their key`() {
        val xp = """"xp":{"firstAttempt":10,"secondAttempt":5,"revealed":1,"hintCost":2,"minimum":1,"missionBonus":20}"""

        fun refused(text: String) =
            shouldThrow<InvalidGamificationConfig> { GamificationConfigParser.parse(text) }.message.shouldNotBeNull()
        refused("""{"xp":5,"ranks":[]}""") shouldContain "xp must be an object"
        refused("""{$xp,"ranks":null}""") shouldContain "ranks is missing"
        refused("""{$xp,"ranks":5}""") shouldContain "ranks must be a non-empty array"
        refused("""{$xp,"ranks":[1]}""") shouldContain "ranks[0] (rank 1)"
        refused("""{$xp,"ranks":[{"name":5,"minXp":0}]}""") shouldContain "ranks[0] (rank 1).name"
        refused("""{$xp,"ranks":[{"name":null,"minXp":0}]}""") shouldContain "ranks[0] (rank 1).name"
        refused("""{$xp,"ranks":[{"name":"A","minXp":0,"icon":"x"}]}""") shouldContain "ranks[0] (rank 1).icon"
        refused("") shouldContain "JSON object"
    }
}
