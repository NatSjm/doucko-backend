package cz.doucko.practice

import cz.doucko.checking.AnswerSpec
import cz.doucko.checking.AnswerType
import cz.doucko.checking.Verdict
import cz.doucko.content.CatalogPart
import cz.doucko.content.CatalogTask
import cz.doucko.content.CatalogTopic
import cz.doucko.content.ContentCatalog
import cz.doucko.content.ServedContent
import cz.doucko.content.ServedImage
import cz.doucko.content.ServedMission
import cz.doucko.content.ServedTask
import cz.doucko.practice.adapter.`in`.web.AdminStatsController
import cz.doucko.practice.adapter.`in`.web.AdminStatsProblemAdvice
import cz.doucko.practice.adapter.`in`.web.RevealedAnswers
import cz.doucko.practice.application.FlagQueue
import cz.doucko.practice.application.Gamification
import cz.doucko.practice.application.GamificationConfigParser
import cz.doucko.practice.application.InvalidPracticeInput
import cz.doucko.practice.application.PracticeConflict
import cz.doucko.practice.application.PracticeNotFound
import cz.doucko.practice.application.ProgressStats
import cz.doucko.practice.domain.AttemptRecord
import cz.doucko.practice.domain.AwardKind
import cz.doucko.practice.domain.FieldError
import cz.doucko.practice.domain.PartRecord
import cz.doucko.practice.domain.RawAnswer
import cz.doucko.practice.domain.ReportReason
import cz.doucko.practice.domain.ReportRecord
import cz.doucko.practice.domain.ReportStatus
import cz.doucko.practice.domain.Scope
import cz.doucko.practice.domain.TaskState
import cz.doucko.practice.domain.XpAward
import cz.doucko.shared.adapter.`in`.web.ProblemResponses
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import jakarta.validation.ConstraintViolationException
import jakarta.validation.Validation
import jakarta.validation.constraints.Pattern
import org.junit.jupiter.api.Test
import org.springframework.context.support.ResourceBundleMessageSource
import org.springframework.mock.web.MockHttpServletRequest
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.Locale
import java.util.UUID

/**
 * The flag queue, the statistics controller and its problem advice over in-memory stores: the edge paths the
 * HTTP tests cannot reach (a vanished attempt or task, a re-evaluation that improves the outcome without new
 * XP, the controller's own re-check of the query parameters, the raw-input serializer).
 */
class FlagQueueServiceTest {
    private val config = GamificationConfigParser.parse(javaClass.getResource("/gamification.json")!!.readText())
    private val store = InMemoryPracticeStore()
    private val ledger = InMemoryXpLedger()
    private val tasks = mutableMapOf<UUID, CatalogTask>()
    private val catalog =
        object : ContentCatalog {
            override fun topics() = listOf(CatalogTopic(UUID.randomUUID(), "Téma", tasks.keys.toList()))

            override fun task(taskId: UUID) = tasks[taskId]

            override fun prompts(taskIds: Collection<UUID>) = taskIds.mapNotNull { id -> tasks[id]?.let { id to it.prompt } }.toMap()
        }
    private val served =
        object : ServedContent {
            override fun missions() = emptyList<ServedMission>()

            override fun missionsContaining(taskId: UUID) = emptyList<ServedMission>()

            override fun mission(missionId: UUID): ServedMission? = null

            override fun isServed(taskId: UUID) = false

            override fun task(taskId: UUID): ServedTask? = null

            override fun image(imageId: UUID): ServedImage? = null
        }
    private val queue = FlagQueue(store, catalog, Gamification(config, served, store, ledger))
    private val controller = AdminStatsController(ProgressStats(store, catalog), queue)
    private val mission = UUID.randomUUID()

    private fun task(accepted: String = "0.5"): UUID {
        val id = UUID.randomUUID()
        tasks[id] = CatalogTask(id, UUID.randomUUID(), "Zadání", listOf(CatalogPart("a", AnswerSpec(AnswerType.DECIMAL, listOf(accepted)))))
        return id
    }

    private fun attempt(
        task: UUID,
        no: Int,
        hints: Int = 0,
    ) = AttemptRecord(
        UUID.randomUUID(),
        task,
        mission,
        Scope.PRIMARY,
        false,
        no,
        hints,
        false,
        listOf(PartRecord("a", RawAnswer.Text("0,5"), "0.5", Verdict.WRONG, false)),
        Instant.parse("2026-10-06T10:00:00Z").plusSeconds(no.toLong()),
    ).also(store.attempts::add)

    private fun report(
        task: UUID,
        attemptId: UUID?,
        status: ReportStatus = ReportStatus.OPEN,
    ) = ReportRecord(UUID.randomUUID(), task, mission, ReportReason.ANSWER_IS_CORRECT, null, attemptId, Instant.now(), status)
        .also(store.reports::add)

    // @trace FR-41
    @Test
    fun `unknown, closed and attempt-less reports are refused`() {
        shouldThrow<PracticeNotFound> { queue.reevaluate(UUID.randomUUID()) }
        shouldThrow<PracticeNotFound> { queue.dismiss(UUID.randomUUID()) }
        val t = task()
        shouldThrow<PracticeConflict> { queue.dismiss(report(t, null, ReportStatus.FIXED).id) }.code shouldBe "report-not-open"
        shouldThrow<PracticeConflict> { queue.reevaluate(report(t, UUID.randomUUID()).id) }.code shouldBe "report-no-attempt"
    }

    // @trace FR-41
    @Test
    fun `an open report without an attempt can be dismissed and is returned without one`() {
        val t = task()
        val r = report(t, null)
        val view = queue.dismiss(r.id)
        view.report.status shouldBe ReportStatus.DISMISSED
        view.attempt.shouldBeNull()
        view.taskPromptExcerpt shouldBe "Zadání"
        store.report(r.id)!!.status shouldBe ReportStatus.DISMISSED
    }

    // @trace FR-41
    @Test
    fun `a task that no longer exists is no change and lists with an empty excerpt`() {
        val t = task()
        val a = attempt(t, 1)
        val r = report(t, a.id)
        tasks.getValue(t).let {
            it.taskId shouldBe t
            it.topicId shouldNotBe t
        }
        tasks.remove(t)
        queue.reevaluate(r.id).changed shouldBe false
        queue.list(null).single().taskPromptExcerpt shouldBe ""
        store.report(r.id)!!.status shouldBe ReportStatus.OPEN
    }

    // @trace FR-41
    @Test
    fun `an improved outcome already paid in full adds no ledger row`() {
        // Revealed (1 XP) with 5 hints before attempt 2: correct on the 2nd attempt is max(1, 5 - 5 × 2) = 1, so +0.
        val t = task()
        attempt(t, 1)
        val a2 = attempt(t, 2, hints = 5)
        ledger.insert(XpAward(0, UUID.randomUUID(), AwardKind.TASK, t, mission, a2.id, 1, Instant.now()))
        val outcome = queue.reevaluate(report(t, a2.id).id)
        outcome.changed shouldBe true
        outcome.newTaskState shouldBe TaskState.CORRECT_SECOND_TRY
        outcome.xp.xpAwarded shouldBe 0
        ledger.awards.map { it.kind } shouldContainExactly listOf(AwardKind.TASK)
        outcome.report.report.status shouldBe ReportStatus.FIXED
    }

    // @trace FR-41
    @Test
    fun `the excerpt keeps short prompts and cuts by code point`() {
        FlagQueue.excerpt("Krátké") shouldBe "Krátké"
        FlagQueue.excerpt("x".repeat(80)) shouldBe "x".repeat(80)
        FlagQueue.excerpt("𝔸".repeat(81)) shouldBe "𝔸".repeat(80) + "…"
    }

    // @trace FR-40
    @Test
    fun `the controller re-checks the query parameters itself`() {
        shouldThrow<InvalidPracticeInput> { controller.getStats("abc") }.errors shouldBe listOf(FieldError("weeks", "weeks"))
        shouldThrow<InvalidPracticeInput> { controller.getStats("53") }
        controller
            .getStats(null)
            .body!!
            .weeks.size shouldBe 4
        controller
            .getStats("52")
            .body!!
            .weeks.size shouldBe 52
        shouldThrow<InvalidPracticeInput> { controller.listReports("fixed") }.errors shouldBe listOf(FieldError("status", "status"))
        controller.listReports("OPEN").body shouldBe emptyList()
        controller.listReports(null).body shouldBe emptyList()
    }

    private class Probe(
        @field:Pattern(regexp = "a") val other: String,
        @field:Pattern(regexp = "a") val weeks: String,
    )

    // @trace FR-40
    @Test
    fun `the advice names each invalid parameter and never echoes it`() {
        val messages =
            ResourceBundleMessageSource().apply {
                setBasename("messages")
                setDefaultEncoding("UTF-8")
            }
        val advice = AdminStatsProblemAdvice(ProblemResponses(messages, JsonMapper.builder().build()), messages)
        val request = MockHttpServletRequest("GET", "/api/admin/stats").apply { addPreferredLocale(Locale.ENGLISH) }
        val invalid = advice.invalid(InvalidPracticeInput(listOf(FieldError("weeks", "weeks"))), request)
        invalid.statusCode.value() shouldBe 400
        val violations = Validation.buildDefaultValidatorFactory().validator.validate(Probe("b-secret", "b-secret"))
        val body = advice.constraintViolation(ConstraintViolationException(violations), request).body!!

        @Suppress("UNCHECKED_CAST")
        val errors = body.properties!!["errors"] as List<Map<String, String>>
        errors.mapNotNull { it["field"] }.sorted() shouldContainExactly listOf("other", "weeks")
        errors.first { it["field"] == "other" }["message"] shouldBe "This parameter is not valid."
        errors.forEach { it["message"]!!.contains("secret") shouldBe false }
    }

    // @trace FR-41
    @Test
    fun `logged raw input is written as a string or an array of option keys`() {
        val json = JsonMapper.builder().addModule(RevealedAnswers()).build()
        val nbsp = "1" + Char(0xA0) + "000"
        val keys = RevealedAnswers.logged(RawAnswer.Keys(listOf("A", "C")))
        json.writeValueAsString(mapOf("a" to keys, "b" to RevealedAnswers.logged(RawAnswer.Text(nbsp)))) shouldBe
            """{"a":["A","C"],"b":"1""" + Char(0xA0) + """000"}"""
        keys shouldBe RevealedAnswers.logged(RawAnswer.Keys(listOf("A", "C")))
        keys shouldNotBe RevealedAnswers.logged(RawAnswer.Text("A"))
        keys.hashCode() shouldBe RawAnswer.Keys(listOf("A", "C")).hashCode()
        keys.toString() shouldBe "RevealedAnswer"
        RevealedAnswers.held(keys) shouldBe RawAnswer.Keys(listOf("A", "C"))
        shouldThrow<IllegalStateException> { RevealedAnswers.held("not a proxy") }
        (keys == Any()).shouldBe(false)
        store.reports.firstOrNull().shouldBeNull()
    }
}
