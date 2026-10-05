package cz.doucko.practice

import cz.doucko.admin.adapter.`in`.web.AdminSecurityConfiguration
import cz.doucko.admin.isProblem
import cz.doucko.checking.AnswerType
import cz.doucko.content.ContentUsage
import cz.doucko.content.ContentWebTestBeans
import cz.doucko.content.InMemoryFileStorage
import cz.doucko.content.InMemoryMissionStore
import cz.doucko.content.InMemoryTaskStore
import cz.doucko.content.ServedContent
import cz.doucko.content.Topics
import cz.doucko.content.domain.ChoiceOption
import cz.doucko.content.domain.Mission
import cz.doucko.content.domain.MissionContent
import cz.doucko.content.domain.Modality
import cz.doucko.content.domain.Part
import cz.doucko.content.domain.PublishStatus
import cz.doucko.content.domain.SourceRef
import cz.doucko.content.domain.Spec
import cz.doucko.content.domain.Task
import cz.doucko.content.domain.TaskContent
import cz.doucko.content.domain.TaskImage
import cz.doucko.content.domain.Translation
import cz.doucko.practice.adapter.`in`.web.RevealedAnswers
import cz.doucko.practice.application.PracticeContentUsage
import cz.doucko.practice.domain.RawAnswer
import cz.doucko.profile.InMemoryChildProfileStore
import cz.doucko.profile.ProfileWebTestBeans
import cz.doucko.profile.domain.ProfileSettings
import cz.doucko.shared.adapter.`in`.web.SecurityConfiguration
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID

/**
 * The child endpoints as a web slice over in-memory content and practice stores: request bodies the
 * contract refuses, translated choice options, the reveal of a single choice, images whose file or task is
 * not served, and the practice side of `ContentUsage` (FR-26, FR-27, FR-29, FR-56).
 */
@WebMvcTest
@Import(
    SecurityConfiguration::class,
    AdminSecurityConfiguration::class,
    ProfileWebTestBeans::class,
    ContentWebTestBeans::class,
    PracticeWebTestBeans::class,
)
class PracticeApiTest {
    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var tasks: InMemoryTaskStore

    @Autowired
    lateinit var missions: InMemoryMissionStore

    @Autowired
    lateinit var files: InMemoryFileStorage

    @Autowired
    lateinit var profile: InMemoryChildProfileStore

    @Autowired
    lateinit var practice: InMemoryPracticeStore

    @Autowired
    lateinit var served: ServedContent

    private val choiceTask = UUID.randomUUID()
    private val draftTask = UUID.randomUUID()
    private val mission = UUID.randomUUID()
    private val draftMission = UUID.randomUUID()
    private val imageId = UUID.randomUUID()
    private val missingImage = UUID.randomUUID()

    private fun task(
        id: UUID,
        status: PublishStatus,
        images: List<TaskImage> = emptyList(),
    ) = Task(
        id,
        TaskContent(
            prompt = "Vyber správnou možnost.",
            topicId = Topics.DECIMALS,
            grade = 6,
            difficulty = 1,
            modality = Modality.TEXT,
            sourceRef = SourceRef(UUID.randomUUID(), 1, "1", null),
            parts =
                listOf(
                    Part(
                        "a",
                        "a",
                        Spec(AnswerType.CHOICE_SINGLE, listOf("B"), options = listOf(ChoiceOption("A", "jedna"), ChoiceOption("B", "dvě"))),
                    ),
                ),
            hints = emptyList(),
            translations = mapOf("uk" to Translation(null, emptyMap(), emptyList(), mapOf("a.B" to "два"))),
        ),
        status,
        images,
    )

    @BeforeEach
    fun seed() {
        val images = listOf(TaskImage(imageId, 1, "image/png", "images/a.png"), TaskImage(missingImage, 2, "image/png", "images/gone.png"))
        tasks.insert(task(choiceTask, PublishStatus.PUBLISHED, images))
        tasks.insert(task(draftTask, PublishStatus.PUBLISHED))
        files.store("images/a.png", byteArrayOf(1, 2, 3))
        missions.insert(Mission(mission, MissionContent(Topics.DECIMALS, 1, listOf(choiceTask)), PublishStatus.PUBLISHED))
        missions.insert(Mission(draftMission, MissionContent(Topics.DECIMALS, 2, listOf(draftTask)), PublishStatus.DRAFT))
    }

    @AfterEach
    fun reset() {
        tasks.tasks.clear()
        missions.missions.clear()
        files.files.clear()
        practice.attempts.clear()
        practice.hints.clear()
        practice.reports.clear()
        practice.runs.clear()
        profile.reset()
    }

    private fun post(
        path: String,
        body: String,
    ) = mvc.post(path) {
        contentType = MediaType.APPLICATION_JSON
        content = body
    }

    private val attempts get() = "/api/child/missions/$mission/tasks/$choiceTask/attempts"

    // @trace FR-26
    @Test
    fun `bodies that are not one JSON object, or lack the answers object, are 400`() {
        mvc.post(attempts) { contentType = MediaType.APPLICATION_JSON }.isProblem(400)
        post(attempts, "{} {}").isProblem(400)
        post(attempts, "[]").isProblem(400)
        post(attempts, "{}").isProblem(400).andExpect { jsonPath("$.errors[0].field") { value("answers") } }
        post(attempts, """{"answers":{"a":[1]}}""").isProblem(400).andExpect { jsonPath("$.errors[0].field") { value("answers.a") } }
        post(attempts, """{"answers":{"a":null}}""").isProblem(400)
        post("/api/child/hq/rank-up-ack", "{}").isProblem(400)
        post("/api/child/hq/rank-up-ack", """{"rankIndex":"1"}""").isProblem(400)
        post("/api/child/hq/rank-up-ack", """{"rankIndex":1.5}""").isProblem(400)
        post("/api/child/missions/$mission/tasks/$choiceTask/reports", """{"reason":5,"note":null}""").isProblem(400)
        practice.attempts.size shouldBe 0
        practice.reports.size shouldBe 0
    }

    // @trace FR-29
    @Test
    fun `translated choice options follow the profile toggle`() {
        mvc.get("/api/child/missions/$mission/tasks/$choiceTask").andExpect {
            jsonPath("$.translationAvailable") { value(true) }
            jsonPath("$.parts[0].options[1].text.secondary") { doesNotExist() }
        }
        profile.current = ProfileSettings.DEFAULTS.copy(secondaryTaskLocale = "uk")
        mvc.get("/api/child/missions/$mission/tasks/$choiceTask").andExpect {
            jsonPath("$.parts[0].options[0].text.secondary") { doesNotExist() }
            jsonPath("$.parts[0].options[1].text.secondary.text") { value("два") }
            jsonPath("$.prompt.secondary") { doesNotExist() }
        }
    }

    // @trace FR-27
    @Test
    fun `a single choice is revealed as its correct option keys`() {
        post(attempts, """{"answers":{"a":["A"]}}""").andExpect { jsonPath("$.parts[0].verdict") { value("WRONG") } }
        post(attempts, """{"answers":{"a":["A","A"]}}""").andExpect {
            jsonPath("$.taskState") { value("REVEALED") }
            jsonPath("$.feedback.revealedAnswers[0].answer[0]") { value("B") }
            jsonPath("$.missionComplete") { value(true) }
        }
        mvc.get("/api/child/missions/$mission/summary").andExpect { jsonPath("$.firstTryCount") { value(0) } }
    }

    // @trace FR-26
    @Test
    fun `images are served only for served tasks and only when the file exists`() {
        mvc.get("/api/child/images/$imageId").andExpect { status { isOk() } }
        mvc.get("/api/child/images/$missingImage").isProblem(404)
        tasks.insert(task(UUID.randomUUID(), PublishStatus.PUBLISHED, listOf(TaskImage(UUID.randomUUID(), 1, "image/png", "images/a.png"))))
        val loose =
            tasks.tasks.values
                .last()
                .images
                .single()
                .id
        mvc.get("/api/child/images/$loose").isProblem(404)
        served.task(draftTask).shouldBeNull()
        served.isServed(draftTask) shouldBe false
        served.task(choiceTask) shouldNotBe null
        mvc.get("/api/child/missions/$draftMission/tasks/$draftTask").isProblem(404)
    }

    // @trace FR-56
    @Test
    fun `the practice usage adapter answers empty queries without the store and sees every kind of use`() {
        val usage: ContentUsage = PracticeContentUsage(practice)
        usage.attemptCounts(emptyList()) shouldBe emptyMap()
        usage.usedTasks(emptyList()) shouldBe emptySet()
        mvc.post("/api/child/missions/$mission/tasks/$choiceTask/hints").isProblem(409)
        post(
            "/api/child/missions/$mission/tasks/$choiceTask/reports",
            """{"reason":"UNCLEAR_PROMPT"}""",
        ).andExpect { status { isCreated() } }
        usage.usedTasks(listOf(choiceTask, draftTask)) shouldBe setOf(choiceTask)
        usage.attemptCounts(listOf(choiceTask)) shouldBe emptyMap()
    }

    // @trace FR-44
    @Test
    fun `a revealed answer compares by value and never prints it`() {
        val a = RevealedAnswers.of(RawAnswer.Text("12"))
        (a == RevealedAnswers.of(RawAnswer.Text("12"))) shouldBe true
        (a == RevealedAnswers.of(RawAnswer.Text("13"))) shouldBe false
        a.equals("12") shouldBe false
        a.hashCode() shouldBe RawAnswer.Text("12").hashCode()
        a.toString() shouldBe "RevealedAnswer"
        RevealedAnswers.held(a) shouldBe RawAnswer.Text("12")
    }
}
