package cz.doucko.practice

import cz.doucko.practice.application.GamificationConfigParser
import cz.doucko.practice.application.GamificationSettings
import cz.doucko.practice.application.InvalidGamificationConfig
import cz.doucko.practice.domain.GamificationConfig
import cz.doucko.practice.domain.TaskState
import cz.doucko.practice.domain.XpRules
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Stream

/**
 * The gamification configuration file (gamification "XP configuration is validated", "Rank ladder",
 * "Swappable skin"; OQ-29): strict JSON integers in range, every key required, the ladder ordered, and a
 * refused start that names the key.
 */
class GamificationConfigTest {
    private fun shipped(): String = javaClass.getResource("/gamification.json")!!.readText()

    private val xpKeys = listOf("firstAttempt", "secondAttempt", "revealed", "hintCost", "minimum", "missionBonus")

    /** The default file with `"key": value` (an xp key, or `minXp` of the 2nd rank) replaced. */
    private fun withXp(
        key: String,
        value: String?,
    ): String {
        val original = Regex("\"$key\": \\d+,?")
        val match = original.find(shipped())!!.value
        val comma = if (match.endsWith(",")) "," else ""
        return if (value == null) {
            shipped().replace(match, "").replace(Regex(",(\\s*)}"), "$1}")
        } else {
            shipped().replace(match, "\"$key\": $value$comma")
        }
    }

    private fun withThresholds(vararg values: String): String {
        val names = listOf("Nováček", "Kadet", "Strážce", "Hrdina", "Legenda")
        val ranks =
            values.mapIndexed { i, v ->
                if (v ==
                    "absent"
                ) {
                    """{"name":"${names[i]}"}"""
                } else {
                    """{"name":"${names[i]}","minXp":$v}"""
                }
            }
        return """{"xp":{"firstAttempt":10,"secondAttempt":5,"revealed":1,"hintCost":2,"minimum":1,"missionBonus":20},"ranks":[${ranks.joinToString(
            ",",
        )}]}"""
    }

    private fun refused(text: String): InvalidGamificationConfig =
        shouldThrow<InvalidGamificationConfig> {
            GamificationConfigParser.parse(text)
        }

    // @trace FR-34, FR-35, FR-36
    @Test
    fun `the shipped file holds the default values and the placeholder ladder`() {
        val config = GamificationConfigParser.parse(shipped())
        with(config.xp) {
            listOf(firstAttempt, secondAttempt, revealed, hintCost, minimum, missionBonus) shouldContainExactly listOf(10, 5, 1, 2, 1, 20)
        }
        config.ladder.ranks.map { it.minXp } shouldContainExactly listOf(0, 150, 400, 800, 1500)
        config.ladder.ranks.map { it.index } shouldContainExactly listOf(0, 1, 2, 3, 4)
        config.ladder.ranks.all { it.name.isNotBlank() } shouldBe true
    }

    // @trace FR-34, FR-35
    @ParameterizedTest(name = "{0} = {1} is refused")
    @MethodSource("outOfRange")
    fun `negative or out-of-range values are refused naming the key`(
        key: String,
        value: String,
    ) {
        refused(withXp(key, value)).message.shouldNotBeNull() shouldContain "xp.$key"
    }

    // @trace FR-34, FR-35
    @ParameterizedTest(name = "{0} = {1} is refused")
    @MethodSource("notIntegers")
    fun `non-integer and locale-formatted values are refused naming the key`(
        key: String,
        value: String,
    ) {
        refused(withXp(key, value)).message.shouldNotBeNull() shouldContain "xp.$key"
    }

    // @trace FR-34, FR-35
    @ParameterizedTest
    @ValueSource(strings = ["firstAttempt", "secondAttempt", "revealed", "hintCost", "minimum", "missionBonus"])
    fun `a missing key is refused instead of falling back`(key: String) {
        val error = refused(withXp(key, null))
        error.message.shouldNotBeNull() shouldContain "xp.$key"
        error.message.shouldNotBeNull() shouldContain "missing"
    }

    // @trace FR-34
    @Test
    fun `an unknown key or a file that is not one JSON object is refused`() {
        refused(shipped().replace("\"minimum\"", "\"minimun\"")).message.shouldNotBeNull() shouldContain "xp.minimun"
        refused(shipped().replace("\"xp\": {", "\"extra\": 1, \"xp\": {")).message.shouldNotBeNull() shouldContain "extra"
        refused("[]").message.shouldNotBeNull() shouldContain "JSON object"
        refused("{\"xp\":").message.shouldNotBeNull() shouldContain "JSON object"
        refused(shipped().replace("\"hintCost\": 2,", "\"hintCost\": 2, \"hintCost\": 3,")).key shouldBe "(file)"
    }

    // @trace FR-34, FR-35
    @Test
    fun `boundary values are accepted`() {
        val config =
            GamificationConfigParser.parse(
                withXp("hintCost", "0").let { it.replace("\"missionBonus\": 20", "\"missionBonus\": 1000") },
            )
        config.xp.hintCost shouldBe 0
        config.xp.missionBonus shouldBe 1000
        config.xp.minimum shouldBe 1
        XpRules.taskAward(config.xp, TaskState.CORRECT_FIRST_TRY, 3) shouldBe 10
        GamificationConfigParser.parse(withXp("firstAttempt", "1000")).xp.firstAttempt shouldBe 1000
        GamificationConfigParser.parse(withXp("revealed", "0")).xp.revealed shouldBe 0
    }

    // @trace FR-36
    @Test
    fun `a ladder out of order names the faulty rank entry`() {
        refused(withThresholds("10", "150", "400")).message.shouldNotBeNull() shouldContain "ranks[0]"
        refused(withThresholds("0", "400", "150")).message.shouldNotBeNull() shouldContain "ranks[2]"
        refused(withThresholds("0", "150", "150")).message.shouldNotBeNull() shouldContain "ranks[2]"
        GamificationConfigParser
            .parse(withThresholds("0", "150", "160"))
            .ladder.ranks.size shouldBe 3
    }

    // @trace FR-36
    @ParameterizedTest(name = "2nd threshold {0} is refused")
    @ValueSource(strings = ["150.5", "\"150\"", "\"1 500\"", "\"1\u00a0500\"", "\"1,5\"", "-150", "1000001", "null", "absent", "true"])
    fun `an invalid threshold names the 2nd rank entry`(value: String) {
        val error = refused(withThresholds("0", value, "1500"))
        error.message.shouldNotBeNull() shouldContain "ranks[1]"
        error.message.shouldNotBeNull() shouldContain "rank 2"
    }

    // @trace FR-39
    @Test
    fun `an incomplete skin ladder is refused naming the missing entry`() {
        refused(shipped().replace(Regex(",\\s*\"ranks\": \\[[\\s\\S]*]"), "")).message.shouldNotBeNull() shouldContain "ranks"
        refused(shipped().replace(Regex("\"ranks\": \\[[\\s\\S]*]"), "\"ranks\": []")).message.shouldNotBeNull() shouldContain "ranks"
        refused(shipped().replace("\"name\": \"Kadet\", ", "")).message.shouldNotBeNull() shouldContain "ranks[1] (rank 2).name"
        refused(shipped().replace("\"Kadet\"", "\"   \"")).message.shouldNotBeNull() shouldContain "ranks[1] (rank 2).name"
        refused(shipped().replace("\"Kadet\"", "\"${"x".repeat(51)}\"")).message.shouldNotBeNull() shouldContain "ranks[1] (rank 2).name"
        // Review F06: the documented limit is 1 to 50 characters, counted in code points.
        GamificationConfigParser
            .parse(shipped().replace("\"Kadet\"", "\"${"ž".repeat(50)}\""))
            .ladder.ranks[1]
            .name shouldBe "ž".repeat(50)
        refused(shipped().replace(Regex(",\\s*\"xp\": \\{[^}]*}|\"xp\": \\{[^}]*},"), "")).message.shouldNotBeNull() shouldContain "xp"
    }

    // ---------------------------------------------------------------- startup (the context refuses to start)

    private fun start(location: String) =
        ApplicationContextRunner()
            .withUserConfiguration(GamificationSettings::class.java)
            .withPropertyValues("doucko.gamification.config=$location")

    private fun Throwable.allText(): String = generateSequence(this) { it.cause }.joinToString("\n") { it.toString() }

    // @trace FR-34, FR-35
    @Test
    fun `an invalid file stops the start and names the key`(
        @TempDir dir: Path,
    ) {
        val file = dir.resolve("gamification.json")
        Files.writeString(file, withXp("missionBonus", "\"20\""))
        start(file.toUri().toString()).run { context ->
            context.startupFailure.shouldNotBeNull().allText() shouldContain "xp.missionBonus"
        }
        start(dir.resolve("absent.json").toUri().toString()).run { context ->
            context.startupFailure.shouldNotBeNull().allText() shouldContain "doucko.gamification.config"
        }
        // Review F02: a path that exists but cannot be read (a directory) names the key too.
        start(dir.toUri().toString()).run { context ->
            context.startupFailure.shouldNotBeNull().allText() shouldContain "doucko.gamification.config"
        }
    }

    // @trace FR-34, FR-39
    @Test
    fun `a valid file starts and its values are used`(
        @TempDir dir: Path,
    ) {
        val file = dir.resolve("gamification.json")
        Files.writeString(file, withXp("firstAttempt", "12").replace("\"Kadet\"", "\"Učeň\""))
        start(file.toUri().toString()).run { context ->
            val config = context.getBean(GamificationConfig::class.java)
            config.xp.firstAttempt shouldBe 12
            config.ladder.ranks[1].name shouldBe "Učeň"
        }
        ApplicationContextRunner().withUserConfiguration(GamificationSettings::class.java).run { context ->
            context.getBean(GamificationConfig::class.java).xp.missionBonus shouldBe 20
        }
    }

    // ---------------------------------------------------------------- no borrowed IP (FR-39)

    private fun denyList(): List<String> {
        val file =
            generateSequence(Path.of("").toAbsolutePath()) {
                it.parent
            }.map { it.resolve("contract/docs/skin/ip-denylist.txt") }.first(Files::exists)
        return Files.readAllLines(file).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    }

    /** The deny-list's matching rule: case-insensitive, whole word or phrase (no letter or digit around it). */
    private fun matches(text: String): List<String> =
        denyList().filter { term ->
            Regex("(?<![\\p{L}\\p{N}])${Regex.escape(term)}(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE).containsMatchIn(text)
        }

    // @trace FR-39
    @Test
    fun `the shipped rank names and keys use no borrowed IP`() {
        matches(shipped()).shouldBeEmpty()
    }

    // @trace FR-39
    @Test
    fun `the deny-list catches a borrowed term in a rank name`() {
        matches(shipped().replace("\"Kadet\"", "\"Akademie Plus Ultra\"")) shouldContainExactly listOf("Plus Ultra")
    }

    companion object {
        @JvmStatic
        fun outOfRange(): Stream<Arguments> =
            Stream.of(
                Arguments.of("firstAttempt", "-1"),
                Arguments.of("hintCost", "-2"),
                Arguments.of("minimum", "0"),
                Arguments.of("missionBonus", "1001"),
                Arguments.of("firstAttempt", "2147483648"),
                Arguments.of("secondAttempt", "1001"),
                Arguments.of("revealed", "-1"),
                Arguments.of("minimum", "1001"),
            )

        @JvmStatic
        fun notIntegers(): Stream<Arguments> =
            listOf("firstAttempt", "secondAttempt", "revealed", "hintCost", "minimum", "missionBonus")
                .flatMap { key ->
                    listOf("2.5", "10.0", "\"10,5\"", "\"1 500\"", "\"1\u00a0500\"", "\"10\"", "\"deset\"", "true", "null", "{}", "[10]")
                        .map { Arguments.of(key, it) }
                }.stream()
    }
}
