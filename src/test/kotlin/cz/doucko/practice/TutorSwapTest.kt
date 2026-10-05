package cz.doucko.practice

import cz.doucko.admin.adapter.`in`.web.AdminSecurityConfiguration
import cz.doucko.checking.AnswerType
import cz.doucko.content.ContentWebTestBeans
import cz.doucko.content.InMemoryMissionStore
import cz.doucko.content.InMemoryTaskStore
import cz.doucko.content.Topics
import cz.doucko.content.domain.Mission
import cz.doucko.content.domain.MissionContent
import cz.doucko.content.domain.Modality
import cz.doucko.content.domain.Part
import cz.doucko.content.domain.PublishStatus
import cz.doucko.content.domain.SourceRef
import cz.doucko.content.domain.Spec
import cz.doucko.content.domain.Task
import cz.doucko.content.domain.TaskContent
import cz.doucko.practice.application.Feedback
import cz.doucko.practice.application.TutorPort
import cz.doucko.profile.ProfileWebTestBeans
import cz.doucko.shared.adapter.`in`.web.SecurityConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.util.UUID

/**
 * practice-flow "Swapping the tutor leaves submission unchanged" (FR-44): with a test tutor in place of the
 * deterministic one, the same `AttemptRequest` body is accepted unchanged and its feedback comes from the
 * swapped tutor.
 */
@WebMvcTest
@Import(
    SecurityConfiguration::class,
    AdminSecurityConfiguration::class,
    ProfileWebTestBeans::class,
    ContentWebTestBeans::class,
    PracticeWebTestBeans::class,
    TutorSwapTest.SwappedTutor::class,
)
class TutorSwapTest {
    @TestConfiguration(proxyBeanMethods = false)
    class SwappedTutor {
        @Bean
        @Primary
        fun swappedTutor() = TutorPort { Feedback(nextHintIndex = 2, revealedAnswers = null) }
    }

    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var tasks: InMemoryTaskStore

    @Autowired
    lateinit var missions: InMemoryMissionStore

    // @trace FR-44
    @Test
    fun `the same attempt body is accepted and the feedback comes from the swapped tutor`() {
        val taskId = UUID.randomUUID()
        val missionId = UUID.randomUUID()
        tasks.insert(
            Task(
                taskId,
                TaskContent(
                    prompt = "Kolik je 3 × 4?",
                    topicId = Topics.DECIMALS,
                    grade = 6,
                    difficulty = 1,
                    modality = Modality.TEXT,
                    sourceRef = SourceRef(UUID.randomUUID(), 1, "1", null),
                    parts = listOf(Part("a", "a", Spec(AnswerType.INTEGER, listOf("12")))),
                    hints = emptyList(),
                    translations = emptyMap(),
                ),
                PublishStatus.PUBLISHED,
                emptyList(),
            ),
        )
        missions.insert(Mission(missionId, MissionContent(Topics.DECIMALS, 1, listOf(taskId)), PublishStatus.PUBLISHED))
        mvc
            .post("/api/child/missions/$missionId/tasks/$taskId/attempts") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"answers":{"a":"7"}}"""
            }.andExpect {
                status { isOk() }
                jsonPath("$.status") { value("GRADED") }
                jsonPath("$.parts[0].verdict") { value("WRONG") }
                jsonPath("$.feedback.nextHintIndex") { value(2) }
            }
    }
}
