package cz.doucko.content

import com.jayway.jsonpath.JsonPath
import cz.doucko.admin.Admin
import cz.doucko.admin.AdminWebTestBeans
import cz.doucko.admin.adapter.`in`.web.AdminSecurityConfiguration
import cz.doucko.admin.signedIn
import cz.doucko.content.domain.PublishStatus
import cz.doucko.profile.ProfileWebTestBeans
import cz.doucko.shared.adapter.`in`.web.SecurityConfiguration
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.post
import java.util.UUID

/**
 * content-authoring missions over the web layer, with practice usage from [FakeContentUsage]: mission
 * CRUD and field rules, every publishing rule, the task guards that keep published missions valid, the
 * delete rules and the served query (FR-9, FR-12, FR-55, FR-56, FR-57).
 */
@WebMvcTest
@Import(
    SecurityConfiguration::class,
    AdminSecurityConfiguration::class,
    AdminWebTestBeans::class,
    ProfileWebTestBeans::class,
    ContentWebTestBeans::class,
)
class MissionApiTest {
    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var storage: InMemoryFileStorage

    @Autowired
    lateinit var sources: InMemorySourceStore

    @Autowired
    lateinit var tasks: InMemoryTaskStore

    @Autowired
    lateinit var missions: InMemoryMissionStore

    @Autowired
    lateinit var usage: FakeContentUsage

    @Autowired
    lateinit var served: ServedContent

    lateinit var admin: Admin
    lateinit var sourceId: String

    @BeforeEach
    fun setUp() {
        storage.files.clear()
        missions.missions.clear()
        tasks.tasks.clear()
        sources.sources.clear()
        usage.clear()
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

    private fun id(
        result: ResultActionsDsl,
        path: String,
    ): String = JsonPath.read(result.andReturn().response.contentAsString, path)

    /** A task of [topic]; published unless [draft]; GRAPHIC without parts when [graphic]. */
    private fun task(
        topic: UUID = Topics.DECIMALS,
        draft: Boolean = false,
        graphic: Boolean = false,
        taskNo: String = "4",
    ): String {
        val overrides =
            mutableListOf(
                "topicId" to "\"$topic\"",
                "sourceRef" to """{"sourceId":"$sourceId","page":57,"taskNo":"$taskNo","subPart":"b"}""",
            )
        if (graphic) {
            overrides += "modality" to "\"GRAPHIC\""
            overrides += "parts" to "[]"
        }
        val taskId =
            id(
                mvc
                    .adminJson("POST", admin, "/api/admin/tasks", taskJson(sourceId, *overrides.toTypedArray()))
                    .andExpect { status { isCreated() } },
                "$.taskId",
            )
        if (!draft) taskStatus(taskId, "PUBLISHED").andExpect { status { isOk() } }
        return taskId
    }

    private fun tasks(
        n: Int,
        topic: UUID = Topics.DECIMALS,
    ) = List(n) { task(topic, taskNo = "${it + 1}") }

    private fun body(
        ids: List<String>,
        position: String = "1",
        topic: Any = Topics.DECIMALS,
    ) = """{"topicId":"$topic","position":$position,"taskIds":${ids.joinToString(",", "[", "]") { "\"$it\"" }}}"""

    private fun create(
        ids: List<String>,
        position: String = "1",
        topic: Any = Topics.DECIMALS,
    ) = mvc.adminJson("POST", admin, "/api/admin/missions", body(ids, position, topic))

    private fun createId(
        ids: List<String>,
        position: String = "1",
        topic: Any = Topics.DECIMALS,
    ): String = id(create(ids, position, topic).andExpect { status { isCreated() } }, "$.missionId")

    private fun update(
        missionId: String,
        ids: List<String>,
        position: String = "1",
        topic: Any = Topics.DECIMALS,
    ) = mvc.adminJson("PUT", admin, "/api/admin/missions/$missionId", body(ids, position, topic))

    private fun missionStatus(
        missionId: String,
        status: String,
    ) = mvc.adminJson("PUT", admin, "/api/admin/missions/$missionId/status", """{"status":"$status"}""")

    private fun taskStatus(
        taskId: String,
        status: String,
    ) = mvc.adminJson("PUT", admin, "/api/admin/tasks/$taskId/status", """{"status":"$status"}""")

    private fun published(ids: List<String>): String {
        val missionId = createId(ids)
        missionStatus(missionId, "PUBLISHED").andExpect { status { isOk() } }
        return missionId
    }

    private fun storedIds(missionId: String) =
        missions.missions
            .getValue(UUID.fromString(missionId))
            .content.taskIds
            .map { it.toString() }

    private fun storedStatus(missionId: String) = missions.missions.getValue(UUID.fromString(missionId)).status

    // ------------------------------------------------------------------ missions (FR-9, FR-55)

    // @trace FR-9
    @Test
    fun `create a mission as DRAFT with the chosen tasks in the chosen order`() {
        val all = tasks(12)
        val chosen = all.take(10).reversed()
        val missionId =
            id(
                create(chosen).andExpect {
                    status { isCreated() }
                    jsonPath("$.status") { value("DRAFT") }
                    jsonPath("$.topicId") { value(Topics.DECIMALS.toString()) }
                    jsonPath("$.position") { value(1) }
                    jsonPath("$.taskIds") { value(contains(*chosen.toTypedArray())) }
                    jsonPath("$.hasAttempts") { value(false) }
                    jsonPath("$.deletable") { value(true) }
                },
                "$.missionId",
            )
        mvc.adminGet(admin, "/api/admin/missions/$missionId").andExpect {
            status { isOk() }
            jsonPath("$.taskIds") { value(contains(*chosen.toTypedArray())) }
        }
        mvc.adminGet(admin, "/api/admin/missions").andExpect {
            jsonPath("$[*].missionId") { value(contains(missionId)) }
        }
    }

    // @trace FR-9
    // @trace FR-55
    @Test
    fun `any size from 0 to 50 is saved, a 51st task is refused naming the maximum`() {
        val all = tasks(51)
        val missionId = createId(all.take(8))
        update(missionId, all.take(12)).andExpect { jsonPath("$.taskIds") { value(hasSize<Any>(12)) } }
        update(missionId, emptyList()).andExpect { jsonPath("$.taskIds") { value(hasSize<Any>(0)) } }
        update(missionId, all.take(50)).andExpect {
            status { isOk() }
            jsonPath("$.taskIds") { value(hasSize<Any>(50)) }
        }
        update(missionId, all).andExpect {
            status { isBadRequest() }
            jsonPath("$.errors[0].field") { value("taskIds") }
            jsonPath("$.errors[0].message") { value(containsString("50")) }
        }
        storedIds(missionId) shouldBe all.take(50)
        create(all).andExpect { status { isBadRequest() } }
        missions.missions.size shouldBe 1
    }

    // @trace FR-9
    @ParameterizedTest
    @ValueSource(strings = ["ABSENT", "null", "0", "1000", "-1", "\"abc\"", "1.5", "\"1,5\"", "1"])
    fun `a missing, out-of-range, malformed or taken position is refused`(position: String) {
        createId(emptyList(), "1")
        val body =
            if (position == "ABSENT") {
                """{"topicId":"${Topics.DECIMALS}","taskIds":[]}"""
            } else {
                body(emptyList(), position)
            }
        mvc.adminJson("POST", admin, "/api/admin/missions", body).andExpect {
            status { isBadRequest() }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.errors[0].field") { value("position") }
        }
        missions.missions.size shouldBe 1
    }

    // @trace FR-9
    @Test
    fun `positions are unique per topic only, and a mission keeps its own position on edit`() {
        val first = createId(emptyList(), "1")
        createId(emptyList(), "1", Topics.ANGLES)
        createId(emptyList(), "2")
        update(first, emptyList(), "1").andExpect { status { isOk() } }
        update(first, emptyList(), "2").andExpect {
            status { isBadRequest() }
            jsonPath("$.errors[0].field") { value("position") }
        }
        update(first, emptyList(), "3").andExpect { jsonPath("$.position") { value(3) } }
    }

    // @trace FR-9
    @Test
    fun `a missing, malformed or unknown topic is refused on topicId`() {
        for (topic in listOf("\"${UUID.randomUUID()}\"", "\"abc\"", "null", "12")) {
            mvc
                .adminJson("POST", admin, "/api/admin/missions", """{"topicId":$topic,"position":1,"taskIds":[]}""")
                .andExpect {
                    status { isBadRequest() }
                    jsonPath("$.errors[0].field") { value("topicId") }
                }
        }
        missions.missions.size shouldBe 0
    }

    // @trace FR-9
    @Test
    fun `unknown, malformed and repeated task ids are refused on their index`() {
        val t = task()
        mvc
            .adminJson(
                "POST",
                admin,
                "/api/admin/missions",
                """{"topicId":"${Topics.DECIMALS}","position":1,"taskIds":["$t","nope","${UUID.randomUUID()}","$t"]}""",
            ).andExpect {
                status { isBadRequest() }
                jsonPath("$.errors[*].field") { value(contains("taskIds[1]", "taskIds[2]", "taskIds[3]")) }
            }
        mvc
            .adminJson("POST", admin, "/api/admin/missions", """{"topicId":"${Topics.DECIMALS}","position":1,"taskIds":"x"}""")
            .andExpect { jsonPath("$.errors[0].field") { value("taskIds") } }
        missions.missions.size shouldBe 0
    }

    // @trace FR-9
    @Test
    fun `a task from another topic is refused with a message naming both topics`() {
        val decimal = task()
        val angle = task(Topics.ANGLES)
        create(listOf(decimal, angle)).andExpect {
            status { isBadRequest() }
            jsonPath("$.errors[0].field") { value("taskIds[1]") }
            jsonPath("$.errors[0].message") { value(containsString("Úhly")) }
            jsonPath("$.errors[0].message") { value(containsString("Desetinná čísla")) }
        }
    }

    // @trace FR-9
    @Test
    fun `the same task twice is refused and the mission is unchanged`() {
        val (t1, t2) = tasks(2)
        val missionId = createId(listOf(t1))
        update(missionId, listOf(t1, t2, t1)).andExpect {
            status { isBadRequest() }
            jsonPath("$.errors[0].field") { value("taskIds[2]") }
        }
        storedIds(missionId) shouldBe listOf(t1)
    }

    // @trace FR-9
    @Test
    fun `a task can be shared by two missions of its topic`() {
        val t = task()
        val a = createId(listOf(t), "1")
        val b = createId(listOf(t), "2")
        mvc.adminGet(admin, "/api/admin/tasks/$t").andExpect {
            jsonPath("$.missionIds") { value(containsInAnyOrder(a, b)) }
        }
    }

    // @trace FR-55
    @Test
    fun `reorder a mission`() {
        val (t1, t2, t3) = tasks(3)
        val missionId = createId(listOf(t1, t2, t3))
        update(missionId, listOf(t3, t1, t2)).andExpect {
            status { isOk() }
            jsonPath("$.taskIds") { value(contains(t3, t1, t2)) }
        }
        mvc.adminGet(admin, "/api/admin/missions/$missionId").andExpect { jsonPath("$.taskIds") { value(contains(t3, t1, t2)) } }
    }

    // @trace FR-9
    // @trace FR-57
    @Test
    fun `a draft mission can hold draft and graphic tasks`() {
        val draft = task(draft = true)
        val graphic = task(graphic = true)
        create(listOf(draft, graphic)).andExpect {
            status { isCreated() }
            jsonPath("$.status") { value("DRAFT") }
        }
    }

    // ------------------------------------------------------------------ publishing rules (FR-57, FR-12)

    // @trace FR-57
    @Test
    fun `a mission with a draft task is not published, naming the draft task`() {
        val ok = tasks(9)
        val draft = task(draft = true, taskNo = "77")
        val missionId = createId(ok + draft)
        missionStatus(missionId, "PUBLISHED").andExpect {
            status { isConflict() }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.conflicts") { value(contains(draft)) }
            jsonPath("$.detail") { value(containsString("task 77b, page 57")) }
        }
        storedStatus(missionId) shouldBe PublishStatus.DRAFT
    }

    // @trace FR-57
    @Test
    fun `a mission with a graphic task is not published, naming the graphic task`() {
        val graphic = task(graphic = true, taskNo = "88")
        val missionId = createId(listOf(task(), graphic))
        missionStatus(missionId, "PUBLISHED").andExpect {
            status { isConflict() }
            jsonPath("$.conflicts") { value(contains(graphic)) }
            jsonPath("$.detail") { value(containsString("task 88b, page 57")) }
        }
        storedStatus(missionId) shouldBe PublishStatus.DRAFT
    }

    // @trace FR-57
    @Test
    fun `an empty mission cannot be published`() {
        val missionId = createId(emptyList())
        missionStatus(missionId, "PUBLISHED").andExpect {
            status { isConflict() }
            jsonPath("$.detail") { value(containsString("at least one task")) }
        }
        storedStatus(missionId) shouldBe PublishStatus.DRAFT
    }

    // @trace FR-57
    // @trace FR-12
    @Test
    fun `a mission of published text tasks is published and served`() {
        val list = tasks(10)
        val missionId = createId(list)
        served.missions() shouldBe emptyList()
        missionStatus(missionId, "PUBLISHED").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("PUBLISHED") }
        }
        served.missions().map { it.missionId.toString() } shouldBe listOf(missionId)
        served.mission(UUID.fromString(missionId))!!.taskIds.map { it.toString() } shouldBe list
        missionStatus(missionId, "PUBLISHED").andExpect { status { isOk() } }
        missionStatus(missionId, "DRAFT").andExpect { jsonPath("$.status") { value("DRAFT") } }
        served.mission(UUID.fromString(missionId)) shouldBe null
    }

    // @trace FR-57
    @Test
    fun `a task in a published mission cannot return to draft until the mission is unpublished`() {
        val t = task()
        val missionId = published(listOf(t))
        taskStatus(t, "DRAFT").andExpect {
            status { isConflict() }
            jsonPath("$.conflicts") { value(contains(missionId)) }
            jsonPath("$.detail") { value(containsString("Desetinná čísla, mission 1")) }
        }
        tasks.tasks.getValue(UUID.fromString(t)).status shouldBe PublishStatus.PUBLISHED
        missionStatus(missionId, "DRAFT").andExpect { status { isOk() } }
        taskStatus(t, "DRAFT").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("DRAFT") }
        }
    }

    // @trace FR-57
    @Test
    fun `a task in a published mission cannot become graphic`() {
        val t = task()
        val missionId = published(listOf(t))
        mvc
            .adminJson(
                "PUT",
                admin,
                "/api/admin/tasks/$t",
                taskJson(sourceId, "modality" to "\"GRAPHIC\"", "parts" to "[]"),
            ).andExpect {
                status { isConflict() }
                jsonPath("$.conflicts") { value(contains(missionId)) }
                jsonPath("$.detail") { value(containsString("Desetinná čísla, mission 1")) }
            }
        tasks.tasks
            .getValue(UUID.fromString(t))
            .content.modality.name shouldBe "TEXT"
    }

    // @trace FR-57
    @Test
    fun `draft and graphic tasks cannot join a published mission`() {
        val list = tasks(2)
        val missionId = published(list)
        val draft = task(draft = true)
        val graphic = task(graphic = true)
        for (extra in listOf(draft, graphic)) {
            update(missionId, list + extra).andExpect {
                status { isConflict() }
                jsonPath("$.conflicts") { value(contains(extra)) }
                jsonPath("$.detail") { value(containsString("Only published text tasks can join a published mission")) }
            }
        }
        storedIds(missionId) shouldBe list
        storedStatus(missionId) shouldBe PublishStatus.PUBLISHED
    }

    // @trace FR-57
    @Test
    fun `a valid task joins a published mission`() {
        val list = tasks(9)
        val missionId = published(list)
        val extra = task()
        update(missionId, list + extra).andExpect {
            status { isOk() }
            jsonPath("$.status") { value("PUBLISHED") }
            jsonPath("$.taskIds") { value(hasSize<Any>(10)) }
        }
    }

    // @trace FR-57
    @Test
    fun `a task of another topic cannot join a published mission`() {
        val list = tasks(1)
        val missionId = published(list)
        update(missionId, list + task(Topics.ANGLES)).andExpect {
            status { isBadRequest() }
            jsonPath("$.errors[0].message") { value(containsString("Úhly")) }
            jsonPath("$.errors[0].message") { value(containsString("Desetinná čísla")) }
        }
        storedIds(missionId) shouldBe list
    }

    // @trace FR-57
    @Test
    fun `the last task cannot be removed from a published mission, another one can`() {
        val single = tasks(1)
        val one = published(single)
        update(one, emptyList()).andExpect {
            status { isConflict() }
            jsonPath("$.detail") { value(containsString("Unpublish")) }
        }
        storedIds(one) shouldBe single
        val list = tasks(10)
        val ten = createId(list, "2")
        missionStatus(ten, "PUBLISHED").andExpect { status { isOk() } }
        val rest = list.filterIndexed { i, _ -> i != 4 }
        update(ten, rest, "2").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("PUBLISHED") }
            jsonPath("$.taskIds") { value(contains(*rest.toTypedArray())) }
        }
        update(ten, rest.reversed(), "2").andExpect { status { isOk() } }
    }

    // @trace FR-57
    @Test
    fun `the topic of a non-empty mission cannot change, of an empty one it can`() {
        val list = tasks(1)
        val draft = createId(list, "1")
        val publishedId = createId(tasks(1), "2")
        missionStatus(publishedId, "PUBLISHED").andExpect { status { isOk() } }
        for (missionId in listOf(draft, publishedId)) {
            update(missionId, storedIds(missionId), "5", Topics.ANGLES).andExpect {
                status { isConflict() }
                jsonPath("$.detail") { value(containsString("only change while the mission is empty")) }
            }
            missions.missions
                .getValue(UUID.fromString(missionId))
                .content.topicId shouldBe Topics.DECIMALS
        }
        val empty = createId(emptyList(), "3")
        update(empty, emptyList(), "3", Topics.ANGLES).andExpect {
            status { isOk() }
            jsonPath("$.topicId") { value(Topics.ANGLES.toString()) }
        }
    }

    // @trace FR-57
    @Test
    fun `the topic of a task in missions cannot change, naming every mission`() {
        val t = task()
        val a = createId(listOf(t), "1")
        val b = createId(listOf(t), "2")
        missionStatus(b, "PUBLISHED").andExpect { status { isOk() } }
        mvc
            .adminJson("PUT", admin, "/api/admin/tasks/$t", taskJson(sourceId, "topicId" to "\"${Topics.ANGLES}\""))
            .andExpect {
                status { isConflict() }
                jsonPath("$.conflicts") { value(containsInAnyOrder(a, b)) }
                jsonPath("$.detail") { value(containsString("Desetinná čísla, mission 1")) }
                jsonPath("$.detail") { value(containsString("Desetinná čísla, mission 2")) }
            }
        tasks.tasks
            .getValue(UUID.fromString(t))
            .content.topicId shouldBe Topics.DECIMALS
    }

    // ------------------------------------------------------------------ delete rules (FR-56)

    // @trace FR-56
    @Test
    fun `an unused draft task is deleted and leaves its draft mission`() {
        val (t1, t2, t3) = listOf(task(draft = true), task(), task())
        val missionId = createId(listOf(t1, t2, t3))
        mvc.adminGet(admin, "/api/admin/tasks/$t2").andExpect { jsonPath("$.deletable") { value(true) } }
        mvc.adminDelete(admin, "/api/admin/tasks/$t2").andExpect { status { isNoContent() } }
        tasks.tasks.containsKey(UUID.fromString(t2)) shouldBe false
        storedIds(missionId) shouldBe listOf(t1, t3)
        mvc.adminDelete(admin, "/api/admin/tasks/$t1").andExpect { status { isNoContent() } }
        storedIds(missionId) shouldBe listOf(t3)
    }

    // @trace FR-56
    @Test
    fun `a task with attempts cannot be deleted and reports its attempts`() {
        val t = task()
        usage.attempts[UUID.fromString(t)] = 3
        mvc.adminGet(admin, "/api/admin/tasks/$t").andExpect {
            jsonPath("$.attemptCount") { value(3) }
            jsonPath("$.deletable") { value(false) }
        }
        mvc.adminDelete(admin, "/api/admin/tasks/$t").andExpect {
            status { isConflict() }
            jsonPath("$.detail") { value(containsString("Unpublish it instead")) }
            jsonPath("$.conflicts") { value(contains(t)) }
        }
        tasks.tasks.containsKey(UUID.fromString(t)) shouldBe true
    }

    // @trace FR-56
    @Test
    fun `a reported or hinted task cannot be deleted`() {
        val reported = task()
        val hinted = task()
        createId(listOf(reported, hinted))
        usage.reports += UUID.fromString(reported)
        usage.openedHints += UUID.fromString(hinted)
        for (t in listOf(reported, hinted)) {
            mvc.adminGet(admin, "/api/admin/tasks/$t").andExpect {
                jsonPath("$.attemptCount") { value(0) }
                jsonPath("$.deletable") { value(false) }
            }
            mvc.adminDelete(admin, "/api/admin/tasks/$t").andExpect {
                status { isConflict() }
                jsonPath("$.detail") { value(containsString("Unpublish it instead")) }
            }
        }
        tasks.tasks.keys.map { it.toString() } shouldContainExactly listOf(reported, hinted)
        usage.reports shouldBe setOf(UUID.fromString(reported))
        usage.openedHints shouldBe setOf(UUID.fromString(hinted))
    }

    // @trace FR-56
    @Test
    fun `a task in a published mission cannot be deleted`() {
        val (t, other) = tasks(2)
        val missionId = published(listOf(t, other))
        mvc.adminGet(admin, "/api/admin/tasks/$t").andExpect { jsonPath("$.deletable") { value(false) } }
        mvc.adminDelete(admin, "/api/admin/tasks/$t").andExpect {
            status { isConflict() }
            jsonPath("$.conflicts") { value(contains(missionId)) }
            jsonPath("$.detail") { value(containsString("Desetinná čísla, mission 1")) }
        }
        tasks.tasks.containsKey(UUID.fromString(t)) shouldBe true
        storedIds(missionId) shouldBe listOf(t, other)
    }

    // @trace FR-56
    @Test
    fun `a mission with an attempted task cannot be deleted`() {
        val (t1, t2) = tasks(2)
        val missionId = createId(listOf(t1, t2))
        usage.attempts[UUID.fromString(t2)] = 1
        mvc.adminGet(admin, "/api/admin/missions/$missionId").andExpect {
            jsonPath("$.hasAttempts") { value(true) }
            jsonPath("$.deletable") { value(false) }
        }
        mvc.adminDelete(admin, "/api/admin/missions/$missionId").andExpect {
            status { isConflict() }
            jsonPath("$.detail") { value(containsString("Unpublish it instead")) }
            jsonPath("$.conflicts") { value(contains(t2)) }
        }
        missions.missions.containsKey(UUID.fromString(missionId)) shouldBe true
    }

    // @trace FR-56
    @Test
    fun `a mission with a reported or hinted task cannot be deleted`() {
        val (t1, t2) = tasks(2)
        val missionId = createId(listOf(t1, t2))
        usage.openedHints += UUID.fromString(t1)
        mvc.adminGet(admin, "/api/admin/missions/$missionId").andExpect {
            jsonPath("$.hasAttempts") { value(false) }
            jsonPath("$.deletable") { value(false) }
        }
        mvc.adminDelete(admin, "/api/admin/missions/$missionId").andExpect { status { isConflict() } }
    }

    // @trace FR-56
    @Test
    fun `an unused mission is deleted and keeps its tasks`() {
        val list = tasks(3)
        val missionId = published(list)
        mvc.adminDelete(admin, "/api/admin/missions/$missionId").andExpect { status { isNoContent() } }
        missions.missions.isEmpty() shouldBe true
        tasks.tasks.keys.map { it.toString() } shouldContainExactly list
        mvc.adminGet(admin, "/api/admin/missions/$missionId").andExpect { status { isNotFound() } }
    }

    // @trace FR-56
    @Test
    fun `a source referenced by 3 tasks cannot be deleted`() {
        tasks(3)
        mvc.adminDelete(admin, "/api/admin/sources/$sourceId").andExpect {
            status { isConflict() }
            jsonPath("$.detail") { value(containsString("3 tasks")) }
        }
    }

    // ------------------------------------------------------------------ served query (FR-12)

    // @trace FR-12
    @Test
    fun `only published text tasks inside published missions are served`() {
        val inDraft = tasks(2)
        val graphic = task(graphic = true)
        createId(inDraft + graphic, "1")
        val alone = task()
        val list = tasks(2)
        val publishedId = createId(list, "2")
        missionStatus(publishedId, "PUBLISHED").andExpect { status { isOk() } }
        served.missions().map { it.missionId.toString() } shouldBe listOf(publishedId)
        list.forEach { served.isServed(UUID.fromString(it)) shouldBe true }
        (inDraft + graphic + alone).forEach { served.isServed(UUID.fromString(it)) shouldBe false }
        served.isServed(UUID.randomUUID()) shouldBe false
        served.mission(UUID.randomUUID()) shouldBe null
    }

    // ------------------------------------------------------------------ protocol

    // @trace FR-9
    @Test
    fun `mission operations need the session and the CSRF token`() {
        mvc.adminGet(null, "/api/admin/missions").andExpect { status { isUnauthorized() } }
        mvc.adminJson("POST", admin, "/api/admin/missions", body(emptyList()), csrf = false).andExpect { status { isForbidden() } }
        mvc
            .post("/api/admin/missions") {
                contentType = MediaType.APPLICATION_JSON
                content = body(emptyList())
            }.andExpect { status { isUnauthorized() } }
        mvc.delete("/api/admin/missions/${UUID.randomUUID()}").andExpect { status { isUnauthorized() } }
        missions.missions.size shouldBe 0
    }

    // @trace FR-9
    @Test
    fun `unknown and malformed mission ids, bad bodies and statuses are problems, never a 500`() {
        val unknown = UUID.randomUUID()
        mvc.adminGet(admin, "/api/admin/missions/$unknown").andExpect { status { isNotFound() } }
        update(unknown.toString(), emptyList()).andExpect { status { isNotFound() } }
        missionStatus(unknown.toString(), "PUBLISHED").andExpect { status { isNotFound() } }
        mvc.adminDelete(admin, "/api/admin/missions/$unknown").andExpect { status { isNotFound() } }
        mvc.adminGet(admin, "/api/admin/missions/abc").andExpect {
            status { isBadRequest() }
            jsonPath("$.errors[0].field") { value("missionId") }
        }
        val missionId = createId(emptyList())
        missionStatus(missionId, "LIVE").andExpect {
            status { isBadRequest() }
            jsonPath("$.errors[0].field") { value("status") }
        }
        mvc.adminJson("PUT", admin, "/api/admin/missions/$missionId", "[1]").andExpect {
            status { isBadRequest() }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
        }
    }
}
