package cz.doucko.practice

import cz.doucko.TestcontainersConfiguration
import cz.doucko.admin.isProblem
import cz.doucko.content.Topics
import cz.doucko.content.adminGet
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.LocalDate

/**
 * The admin statistics over HTTP against Postgres 16 (store spec progress-stats "Session definition",
 * "Weekly hint and reveal figures", "Practice statistics page", "Stats and flag queue are admin-only";
 * FR-40). Practice of the current week goes through the child API; history in past weeks is placed in the
 * log at chosen Europe/Prague times.
 */
@Import(TestcontainersConfiguration::class)
@ExtendWith(DenyOutboundNetwork::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class ProgressStatsIT {
    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var jdbc: JdbcTemplate

    lateinit var fx: StatsFixture

    @BeforeEach
    fun setUp() {
        fx = StatsFixture(mvc, jdbc)
        fx.setUp()
    }

    @AfterEach
    fun clean() = fx.clean()

    private val monday get() = StatsFixture.currentMonday()

    private fun completed(weeksBack: Long): LocalDate = monday.minusWeeks(weeksBack)

    private fun weekStarts(doc: com.jayway.jsonpath.DocumentContext): List<String> = doc.read("$.weeks[*].weekStart")

    // ---------------------------------------------------------------- week entries

    // @trace FR-40
    @Test
    fun `without a week count the stats hold exactly 4 weeks, the current week last`() {
        val doc = fx.statsOk()
        weekStarts(doc) shouldContainExactly (3L downTo 0L).map { monday.minusWeeks(it).toString() }
        doc.read<Boolean>("$.metricMet") shouldBe false
    }

    // @trace FR-40
    @Test
    fun `1 and 52 weeks are accepted and weeks before the first attempt are zeros`() {
        val t = List(10) { fx.task() }
        fx.logSessions(completed(1), 1, t)
        fx.logSessions(completed(2), 1, t)
        weekStarts(fx.statsOk("weeks" to "1")) shouldContainExactly listOf(monday.toString())
        val all = fx.statsOk("weeks" to "52")
        all.read<List<Any>>("$.weeks") shouldHaveSize 52
        weekStarts(all).first() shouldBe monday.minusWeeks(51).toString()
        all.read<List<Int>>("$.weeks[0:49].sessions").distinct() shouldContainExactly listOf(0)
        all.read<List<Int>>("$.weeks[0:49].hintsUsed").distinct() shouldContainExactly listOf(0)
        all.read<List<Int>>("$.weeks[0:49].revealed").distinct() shouldContainExactly listOf(0)
        all.read<List<Int>>("$.weeks[49:51].tasksPerSession[0]") shouldContainExactly listOf(10, 10)
    }

    // @trace FR-40
    @Test
    fun `no data yet is zeros everywhere, the metric is not met and nothing fails`() {
        fx.mission(List(2) { fx.task() })
        val doc = fx.statsOk()
        doc.read<List<Int>>("$.weeks[*].sessions") shouldContainExactly listOf(0, 0, 0, 0)
        doc.read<List<Int>>("$.weeks[*].qualifyingSessions") shouldContainExactly listOf(0, 0, 0, 0)
        doc.read<List<Int>>("$.weeks[*].hintsUsed") shouldContainExactly listOf(0, 0, 0, 0)
        doc.read<List<Int>>("$.weeks[*].revealed") shouldContainExactly listOf(0, 0, 0, 0)
        doc.read<List<List<Int>>>("$.weeks[*].tasksPerSession").flatten() shouldHaveSize 0
        doc.read<Boolean>("$.metricMet") shouldBe false
        doc.read<List<String>>("$.firstTryRateByTopic[*].topicName") shouldContainExactly listOf("Desetinná čísla")
        doc.read<Int>("$.firstTryRateByTopic[0].resolved") shouldBe 0
        doc.read<Int>("$.firstTryRateByTopic[0].firstTry") shouldBe 0
    }

    // @trace FR-40
    @Test
    fun `invalid, oversized and locale-formatted week counts are 400 naming weeks`() {
        val values =
            listOf("", "0", "-1", "53", "abc", "4.5", "4,5", "1 000", "1 000", "٤", "2147483648", "1".repeat(40), "04", " 4", "+4")
        for (value in values) {
            fx.stats("weeks" to value).isProblem(400).andExpect {
                jsonPath("$.errors[0].field") { value("weeks") }
                jsonPath("$.weeks") { doesNotExist() }
            }
        }
        mvc
            .get("/api/admin/stats") {
                session = fx.admin.session
                param("weeks", "4", "5")
            }.isProblem(400)
            .andExpect { jsonPath("$.errors[0].field") { value("weeks") } }
    }

    // ---------------------------------------------------------------- live practice in the current week

    // @trace FR-40
    @Test
    fun `practice through the child API shows up in the current week`() {
        val t = List(11) { fx.task("hints" to """["Nápověda."]""") }
        val m = fx.mission(t)
        fx.openHints(m, t[0], 1)
        fx.openHints(m, t[1], 1)
        t.take(9).forEach { fx.solve(m, it) }
        fx.reveal(m, t[9])
        fx.attempt(m, t[10], """{"a":"5,,2"}""").andExpect { jsonPath("$.status") { value("UNPARSEABLE") } }
        val current = fx.statsOk().read<Map<String, Any>>("$.weeks[3]")
        current["weekStart"] shouldBe monday.toString()
        current["sessions"] shouldBe 1
        current["qualifyingSessions"] shouldBe 1
        current["tasksPerSession"] shouldBe listOf(10)
        current["hintsUsed"] shouldBe 2
        current["revealed"] shouldBe 1
    }

    // ---------------------------------------------------------------- sessions and weeks from the log

    // @trace FR-40
    @Test
    fun `20- and 35-minute gaps and a session across Sunday midnight`() {
        val t = List(11) { fx.task() }
        val tue = completed(1).plusDays(1)
        t.take(6).forEachIndexed { i, id -> fx.logAttempt(id, StatsFixture.prague(tue.atTime(16, 4 * i))) }
        t.drop(6).forEachIndexed { i, id -> fx.logAttempt(id, StatsFixture.prague(tue.atTime(16, 40).plusMinutes(3L * i))) }
        val wed = completed(1).plusDays(2)
        t.take(6).forEachIndexed { i, id -> fx.logAttempt(id, StatsFixture.prague(wed.atTime(16, 4 * i))) }
        t.drop(6).forEachIndexed { i, id -> fx.logAttempt(id, StatsFixture.prague(wed.atTime(16, 55).plusMinutes(3L * i))) }
        val sunday = completed(2).plusDays(6)
        fx.logAttempt(t[0], StatsFixture.prague(sunday.atTime(23, 40)))
        fx.logAttempt(t[1], StatsFixture.prague(sunday.plusDays(1).atTime(0, 5)))
        fx.logAttempt(t[2], StatsFixture.prague(sunday.plusDays(1).atTime(0, 15)))
        val doc = fx.statsOk()
        doc.read<List<Int>>("$.weeks[2].tasksPerSession") shouldContainExactly listOf(11, 6, 5)
        doc.read<Int>("$.weeks[2].qualifyingSessions") shouldBe 1
        doc.read<List<Int>>("$.weeks[1].tasksPerSession") shouldContainExactly listOf(3)
    }

    // @trace FR-40
    @Test
    fun `a hint counts in the week it was opened, also without a later attempt`() {
        val t = fx.task()
        val sunday = completed(2).plusDays(6)
        fx.logHint(t, StatsFixture.prague(sunday.atTime(23, 50)))
        fx.logHint(t, StatsFixture.prague(sunday.atTime(23, 51)))
        fx.logAttempt(t, StatsFixture.prague(sunday.plusDays(1).atTime(0, 5)))
        val doc = fx.statsOk()
        doc.read<Int>("$.weeks[1].hintsUsed") shouldBe 2
        doc.read<Int>("$.weeks[1].sessions") shouldBe 0
        doc.read<Int>("$.weeks[2].hintsUsed") shouldBe 0
        doc.read<Int>("$.weeks[2].sessions") shouldBe 1
    }

    // ---------------------------------------------------------------- success metric

    // @trace FR-40
    @Test
    fun `the metric is met with 3 qualifying sessions in each completed week, whatever week count is asked`() {
        val t = List(10) { fx.task() }
        (1L..4L).forEach { fx.logSessions(completed(it), 3, t) }
        for (weeks in listOf("1", "12")) {
            val doc = fx.statsOk("weeks" to weeks)
            doc.read<Boolean>("$.metricMet") shouldBe true
            doc.read<List<Any>>("$.weeks") shouldHaveSize weeks.toInt()
        }
        fx.statsOk("weeks" to "12").read<Int>("$.weeks[6].qualifyingSessions") shouldBe 0
        fx.statsOk().read<Int>("$.weeks[3].qualifyingSessions") shouldBe 0
    }

    // @trace FR-40
    @Test
    fun `the metric is not met when one completed week has 2, and that week shows 2`() {
        val t = List(10) { fx.task() }
        (1L..3L).forEach { fx.logSessions(completed(it), 3, t) }
        fx.logSessions(completed(4), 2, t)
        val doc = fx.statsOk("weeks" to "5")
        doc.read<Boolean>("$.metricMet") shouldBe false
        doc.read<List<Int>>("$.weeks[*].qualifyingSessions") shouldContainExactly listOf(2, 3, 3, 3, 0)
    }

    // @trace FR-40
    @Test
    fun `less than 4 completed weeks of history is not met`() {
        val t = List(10) { fx.task() }
        (1L..2L).forEach { fx.logSessions(completed(it), 5, t) }
        val doc = fx.statsOk()
        doc.read<Boolean>("$.metricMet") shouldBe false
        doc.read<List<Int>>("$.weeks[*].qualifyingSessions") shouldContainExactly listOf(0, 5, 5, 0)
    }

    // ---------------------------------------------------------------- first-try rate

    private fun decimals(): Pair<Int, Int> {
        val doc = fx.statsOk()
        val names = doc.read<List<String>>("$.firstTryRateByTopic[*].topicName")
        val i = names.indexOf("Desetinná čísla")
        return doc.read<Int>("$.firstTryRateByTopic[$i].resolved") to doc.read<Int>("$.firstTryRateByTopic[$i].firstTry")
    }

    // @trace FR-40
    @Test
    fun `first-try rate per topic counts resolved tasks and those correct on the 1st attempt`() {
        val t = List(9) { fx.task() }
        val m = fx.mission(t)
        t.take(6).forEach { fx.solve(m, it) }
        fx.wrong(m, t[6])
        fx.solve(m, t[6])
        fx.reveal(m, t[7])
        fx.wrong(m, t[8])
        decimals() shouldBe (8 to 6)
    }

    // @trace FR-40
    @Test
    fun `a replay does not change the first-try rate but counts towards sessions`() {
        val t = fx.task()
        val m = fx.mission(listOf(t))
        fx.reveal(m, t)
        decimals() shouldBe (1 to 0)
        fx.replay(m)
        fx.solve(m, t)
        decimals() shouldBe (1 to 0)
        fx.statsOk().read<List<Int>>("$.weeks[3].tasksPerSession") shouldContainExactly listOf(1)
    }

    // @trace FR-40
    @Test
    fun `the re-evaluated outcome is used for the first-try rate`() {
        val good = List(2) { fx.task() }
        val revealed = fx.task("parts" to fx.parts("DECIMAL", "0.05"))
        val other = fx.task()
        val m = fx.mission(good + revealed + other)
        good.forEach { fx.solve(m, it) }
        fx.reveal(m, other)
        fx.answer(m, revealed, "0,5")
        val reportId = fx.report(m, revealed)
        fx.answer(m, revealed, "0,5")
        decimals() shouldBe (4 to 2)
        fx.edit(revealed, "parts" to fx.parts("DECIMAL", "0.5"))
        fx.reevaluateOk(reportId).read<String>("$.newTaskState") shouldBe "CORRECT_FIRST_TRY"
        decimals() shouldBe (4 to 3)
    }

    // @trace FR-40
    @Test
    fun `topics are listed only when they have a published task, and a topic without resolved tasks shows 0 of 0`() {
        fx.mission(listOf(fx.task()))
        fx.task("topicId" to "\"${Topics.NATURAL}\"", publish = false)
        val doc = fx.statsOk()
        doc.read<List<String>>("$.firstTryRateByTopic[*].topicId") shouldContainExactly listOf(Topics.DECIMALS.toString())
        doc.read<List<String>>("$.firstTryRateByTopic[*].topicId") shouldNotContain Topics.NATURAL.toString()
        decimals() shouldBe (0 to 0)
    }

    // ---------------------------------------------------------------- admin only

    // @trace FR-40
    @Test
    fun `the stats need an admin session, which is checked before the week count`() {
        mvc.adminGet(null, "/api/admin/stats").isProblem(401).andExpect { jsonPath("$.weeks") { doesNotExist() } }
        mvc.adminGet(null, "/api/admin/stats", "weeks" to "abc").isProblem(401)
        mvc.adminGet(null, "/api/admin/reports").isProblem(401)
    }
}
