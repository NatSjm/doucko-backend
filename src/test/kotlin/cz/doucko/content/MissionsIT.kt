package cz.doucko.content

import com.jayway.jsonpath.JsonPath
import cz.doucko.TestcontainersConfiguration
import cz.doucko.admin.Admin
import cz.doucko.admin.signedIn
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.containsInAnyOrder
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import java.util.UUID

/**
 * Missions against Postgres 16: the stored order, shared tasks, task deletes that leave draft missions
 * renumbered, mission deletes that keep their tasks, the 50-task limit, positions unique per topic, the
 * usage guard (practice faked until slice 7) and the served query (FR-9, FR-12, FR-55, FR-56, FR-57).
 */
@Import(TestcontainersConfiguration::class, MissionsIT.Usage::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class MissionsIT {
    /** Practice usage as the tests set it; slice 7 replaces it with the real adapter. */
    @TestConfiguration(proxyBeanMethods = false)
    class Usage {
        @Bean
        fun contentUsage() = FakeContentUsage()
    }

    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var usage: FakeContentUsage

    @Autowired
    lateinit var served: ServedContent

    lateinit var admin: Admin
    lateinit var sourceId: String

    @BeforeEach
    fun clean() {
        jdbc.update("DELETE FROM mission")
        jdbc.update("DELETE FROM task_image")
        jdbc.update("DELETE FROM task")
        jdbc.update("DELETE FROM source")
        usage.clear()
        admin = mvc.signedIn()
        sourceId =
            JsonPath.read(
                mvc
                    .uploadSource(admin, Files.pdf(120), *SOURCE_FIELDS)
                    .andExpect { status { isCreated() } }
                    .andReturn()
                    .response.contentAsString,
                "$.sourceId",
            )
    }

    private fun task(
        topic: UUID = Topics.DECIMALS,
        publish: Boolean = true,
    ): String {
        val id: String =
            JsonPath.read(
                mvc
                    .adminJson("POST", admin, "/api/admin/tasks", taskJson(sourceId, "topicId" to "\"$topic\""))
                    .andExpect { status { isCreated() } }
                    .andReturn()
                    .response.contentAsString,
                "$.taskId",
            )
        if (publish) {
            mvc.adminJson("PUT", admin, "/api/admin/tasks/$id/status", """{"status":"PUBLISHED"}""").andExpect { status { isOk() } }
        }
        return id
    }

    private fun body(
        ids: List<String>,
        position: Int,
        topic: UUID = Topics.DECIMALS,
    ) = """{"topicId":"$topic","position":$position,"taskIds":${ids.joinToString(",", "[", "]") { "\"$it\"" }}}"""

    private fun mission(
        ids: List<String>,
        position: Int = 1,
        topic: UUID = Topics.DECIMALS,
    ): String =
        JsonPath.read(
            mvc
                .adminJson("POST", admin, "/api/admin/missions", body(ids, position, topic))
                .andExpect { status { isCreated() } }
                .andReturn()
                .response.contentAsString,
            "$.missionId",
        )

    private fun publish(missionId: String) =
        mvc.adminJson("PUT", admin, "/api/admin/missions/$missionId/status", """{"status":"PUBLISHED"}""")

    private fun rows(missionId: String): List<String> =
        jdbc
            .queryForList(
                "SELECT task_id::text FROM mission_task WHERE mission_id = ?::uuid ORDER BY position",
                String::class.java,
                missionId,
            ).filterNotNull()

    private fun positions(missionId: String): List<Int> =
        jdbc
            .queryForList("SELECT position FROM mission_task WHERE mission_id = ?::uuid ORDER BY position", Int::class.java, missionId)
            .filterNotNull()

    // @trace FR-9
    // @trace FR-55
    @Test
    fun `the task order is stored exactly and a reorder rewrites it`() {
        val ids = List(10) { task() }
        val missionId = mission(ids.reversed())
        rows(missionId) shouldContainExactly ids.reversed()
        positions(missionId) shouldContainExactly (1..10).toList()
        jdbc.queryForObject("SELECT status FROM mission", String::class.java) shouldBe "DRAFT"
        mvc
            .adminJson("PUT", admin, "/api/admin/missions/$missionId", body(listOf(ids[9]) + ids.take(9), 4))
            .andExpect { status { isOk() } }
        rows(missionId) shouldContainExactly listOf(ids[9]) + ids.take(9)
        jdbc.queryForObject("SELECT position FROM mission", Int::class.java) shouldBe 4
    }

    // @trace FR-9
    @Test
    fun `fifty tasks are stored, a 51st is refused and nothing changes`() {
        val ids = List(51) { task() }
        val missionId = mission(ids.take(50))
        rows(missionId).size shouldBe 50
        mvc
            .adminJson("PUT", admin, "/api/admin/missions/$missionId", body(ids, 1))
            .andExpect { status { isBadRequest() } }
        rows(missionId) shouldContainExactly ids.take(50)
    }

    // @trace FR-9
    @Test
    fun `a task is shared by two missions, positions are unique per topic`() {
        val t = task()
        val a = mission(listOf(t), 1)
        val b = mission(listOf(t), 2)
        mission(emptyList(), 1, Topics.ANGLES)
        mvc.adminGet(admin, "/api/admin/tasks/$t").andExpect { jsonPath("$.missionIds") { value(containsInAnyOrder(a, b)) } }
        mvc
            .adminJson("POST", admin, "/api/admin/missions", body(emptyList(), 2))
            .andExpect { jsonPath("$.errors[0].field") { value("position") } }
        mvc.adminGet(admin, "/api/admin/missions").andExpect { jsonPath("$[*].position") { value(contains(1, 2, 1)) } }
        jdbc.queryForObject("SELECT count(*) FROM mission", Int::class.java) shouldBe 3
    }

    // @trace FR-56
    @Test
    fun `a deleted task leaves its draft missions renumbered, a deleted mission keeps its tasks`() {
        val (t1, t2, t3) = List(3) { task() }
        val a = mission(listOf(t1, t2, t3), 1)
        val b = mission(listOf(t2, t3), 2)
        mvc.adminDelete(admin, "/api/admin/tasks/$t2").andExpect { status { isNoContent() } }
        rows(a) shouldContainExactly listOf(t1, t3)
        positions(a) shouldContainExactly listOf(1, 2)
        rows(b) shouldContainExactly listOf(t3)
        positions(b) shouldContainExactly listOf(1)
        mvc.adminDelete(admin, "/api/admin/missions/$a").andExpect { status { isNoContent() } }
        jdbc.queryForObject("SELECT count(*) FROM mission WHERE id = ?::uuid", Int::class.java, a) shouldBe 0
        jdbc.queryForObject("SELECT count(*) FROM task", Int::class.java) shouldBe 2
    }

    // @trace FR-56
    // @trace FR-57
    @Test
    fun `published missions and practice usage block deletes`() {
        val (t1, t2) = List(2) { task() }
        val missionId = mission(listOf(t1, t2))
        publish(missionId).andExpect { status { isOk() } }
        mvc.adminDelete(admin, "/api/admin/tasks/$t1").andExpect { status { isConflict() } }
        mvc.adminJson("PUT", admin, "/api/admin/tasks/$t1/status", """{"status":"DRAFT"}""").andExpect { status { isConflict() } }
        usage.attempts[UUID.fromString(t2)] = 2
        mvc.adminGet(admin, "/api/admin/missions/$missionId").andExpect {
            jsonPath("$.hasAttempts") { value(true) }
            jsonPath("$.deletable") { value(false) }
        }
        mvc.adminDelete(admin, "/api/admin/missions/$missionId").andExpect { status { isConflict() } }
        mvc.adminJson("PUT", admin, "/api/admin/missions/$missionId/status", """{"status":"DRAFT"}""").andExpect { status { isOk() } }
        mvc.adminDelete(admin, "/api/admin/tasks/$t2").andExpect { status { isConflict() } }
        mvc.adminDelete(admin, "/api/admin/tasks/$t1").andExpect { status { isNoContent() } }
        rows(missionId) shouldContainExactly listOf(t2)
        jdbc.queryForObject("SELECT status FROM task WHERE id = ?::uuid", String::class.java, t2) shouldBe "PUBLISHED"
    }

    // @trace FR-57
    @Test
    fun `publishing rules hold against the database`() {
        val draft = task(publish = false)
        val ok = task()
        val missionId = mission(listOf(ok, draft))
        publish(missionId).andExpect {
            status { isConflict() }
            jsonPath("$.conflicts") { value(contains(draft)) }
        }
        jdbc.queryForObject("SELECT status FROM mission", String::class.java) shouldBe "DRAFT"
        mvc.adminJson("PUT", admin, "/api/admin/tasks/$draft/status", """{"status":"PUBLISHED"}""").andExpect { status { isOk() } }
        publish(missionId).andExpect { status { isOk() } }
        jdbc.queryForObject("SELECT status FROM mission", String::class.java) shouldBe "PUBLISHED"
        mvc
            .adminJson("PUT", admin, "/api/admin/tasks/$ok", taskJson(sourceId, "topicId" to "\"${Topics.ANGLES}\""))
            .andExpect { status { isConflict() } }
        jdbc.queryForObject("SELECT topic_id::text FROM task WHERE id = ?::uuid", String::class.java, ok) shouldBe
            Topics.DECIMALS.toString()
    }

    // @trace FR-12
    @Test
    fun `only published text tasks inside published missions are served`() {
        val inDraft = task()
        val graphic: String =
            JsonPath.read(
                mvc
                    .adminJson("POST", admin, "/api/admin/tasks", taskJson(sourceId, "modality" to "\"GRAPHIC\"", "parts" to "[]"))
                    .andReturn()
                    .response.contentAsString,
                "$.taskId",
            )
        mvc.adminJson("PUT", admin, "/api/admin/tasks/$graphic/status", """{"status":"PUBLISHED"}""").andExpect { status { isOk() } }
        val draftMission = mission(listOf(inDraft, graphic), 1)
        val alone = task()
        val (p1, p2) = List(2) { task() }
        val angle = task(Topics.ANGLES)
        val second = mission(listOf(p2, p1), 2)
        val first = mission(listOf(angle), 1, Topics.ANGLES)
        publish(second).andExpect { status { isOk() } }
        publish(first).andExpect { status { isOk() } }
        served.missions().map { it.missionId.toString() } shouldContainExactly listOf(second, first)
        served.mission(UUID.fromString(second))!!.taskIds.map { it.toString() } shouldContainExactly listOf(p2, p1)
        served.mission(UUID.fromString(draftMission)) shouldBe null
        listOf(p1, p2, angle).forEach { served.isServed(UUID.fromString(it)) shouldBe true }
        listOf(inDraft, graphic, alone).forEach { served.isServed(UUID.fromString(it)) shouldBe false }
    }
}
