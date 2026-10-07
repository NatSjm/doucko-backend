package cz.doucko.practice

import com.jayway.jsonpath.DocumentContext
import com.jayway.jsonpath.JsonPath
import cz.doucko.TestcontainersConfiguration
import cz.doucko.admin.Admin
import cz.doucko.admin.signedIn
import cz.doucko.content.Files
import cz.doucko.content.SOURCE_FIELDS
import cz.doucko.content.Topics
import cz.doucko.content.adminJson
import cz.doucko.content.taskJson
import cz.doucko.content.uploadSource
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID

/**
 * A second gamification configuration (`gamification-alternate.json`: 1st-attempt award 12, mission bonus
 * 30, a three-rank ladder at 0, 150 and 160 XP with other names): values and names change, behaviour does not
 * (gamification "Values are configuration", "Configured bonus", "Swap the skin", "Bonus crosses two
 * thresholds").
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["doucko.gamification.config=classpath:gamification-alternate.json"],
)
@AutoConfigureMockMvc
class GamificationConfigIT {
    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var jdbc: JdbcTemplate

    lateinit var admin: Admin
    lateinit var sourceId: String
    private var position = 0

    @BeforeEach
    fun setUp() {
        clean()
        admin = mvc.signedIn()
        sourceId =
            JsonPath.read(
                mvc
                    .uploadSource(admin, Files.pdf(120), *SOURCE_FIELDS)
                    .andReturn()
                    .response.contentAsString,
                "$.sourceId",
            )
    }

    @AfterEach
    fun clean() {
        jdbc.cleanPractice()
        for (table in listOf("mission", "task_image", "task", "source")) jdbc.update("DELETE FROM $table")
    }

    private fun read(result: ResultActionsDsl): DocumentContext = JsonPath.parse(result.andReturn().response.contentAsString)

    private fun task(): String {
        val id: String =
            JsonPath.read(
                mvc
                    .adminJson("POST", admin, "/api/admin/tasks", taskJson(sourceId))
                    .andExpect { status { isCreated() } }
                    .andReturn()
                    .response.contentAsString,
                "$.taskId",
            )
        mvc.adminJson("PUT", admin, "/api/admin/tasks/$id/status", """{"status":"PUBLISHED"}""").andExpect { status { isOk() } }
        return id
    }

    private fun mission(ids: List<String>): String {
        val body = """{"topicId":"${Topics.DECIMALS}","position":${++position},"taskIds":${ids.joinToString(
            ",",
            "[",
            "]",
        ) { "\"$it\"" }}}"""
        val id: String =
            JsonPath.read(
                mvc
                    .adminJson("POST", admin, "/api/admin/missions", body)
                    .andExpect { status { isCreated() } }
                    .andReturn()
                    .response.contentAsString,
                "$.missionId",
            )
        mvc.adminJson("PUT", admin, "/api/admin/missions/$id/status", """{"status":"PUBLISHED"}""").andExpect { status { isOk() } }
        return id
    }

    private fun solve(
        m: String,
        t: String,
    ) = read(
        mvc
            .post("/api/child/missions/$m/tasks/$t/attempts") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"answers":{"a":"12"}}"""
            }.andExpect { status { isOk() } },
    )

    private fun hq() = read(mvc.get("/api/child/hq").andExpect { status { isOk() } })

    private fun seedTo(target: Int) {
        val now = jdbc.queryForObject("SELECT coalesce(sum(amount), 0) FROM xp_award", Int::class.java)!!
        if (target > now) {
            jdbc.update(
                "INSERT INTO xp_award (id, kind, mission_id, amount, created_at) VALUES (?, 'MISSION_BONUS', ?, ?, now())",
                UUID.randomUUID(),
                UUID.randomUUID(),
                target - now,
            )
        }
    }

    // @trace FR-34, FR-35
    @Test
    fun `configured award and bonus are paid without a code change`() {
        val t = task()
        val m = mission(listOf(t))
        solve(m, t).let {
            it.read<Int>("$.xpAwarded") shouldBe 12
            it.read<Int>("$.bonusXp") shouldBe 30
        }
        hq().read<Int>("$.totalXp") shouldBe 42
        read(mvc.get("/api/child/missions/$m/summary")).read<Int>("$.xpEarned") shouldBe 42
    }

    // @trace FR-39, FR-37
    @Test
    fun `HQ names the ranks of the configured skin`() {
        hq().let {
            it.read<String>("$.rank.name") shouldBe "Učeň"
            it.read<String>("$.nextRank.name") shouldBe "Pomocník"
            it.read<Int>("$.nextRank.minXp") shouldBe 150
        }
        seedTo(160)
        hq().let {
            it.read<String>("$.rank.name") shouldBe "Ochránce města"
            it.read<Any?>("$.nextRank").shouldBeNull()
        }
    }

    // @trace FR-36
    @Test
    fun `one completion crossing two thresholds celebrates only the highest rank`() {
        val ids = List(2) { task() }
        val m = mission(ids)
        solve(m, ids[0])
        seedTo(140)
        solve(m, ids[1]).let {
            it.read<Boolean>("$.missionComplete") shouldBe true
            it.read<Any?>("$.rankUp").shouldBeNull()
        }
        hq().let {
            it.read<Int>("$.totalXp") shouldBe 182
            it.read<Int>("$.rank.index") shouldBe 2
            it.read<Int>("$.pendingRankUp.index") shouldBe 2
        }
        read(mvc.get("/api/child/missions/$m/summary")).let {
            it.read<Int>("$.rankUp.index") shouldBe 2
            it.read<String>("$.rankUp.name") shouldBe "Ochránce města"
        }
    }
}
