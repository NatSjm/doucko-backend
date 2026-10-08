package cz.doucko.platform

import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The isolated e2e stack tool (tools/e2e-stack.mjs) only ever addresses Compose project
 * `doucko-e2e` with the committed fake test env file, so `down -v` cannot delete the owner's
 * project `doucko` (NFR-3: real data stays out of reach), and publishes only loopback (NFR-2).
 * These checks run the pure parts through `--dry-run`, without Docker.
 */
class E2eStackToolTest {
    private fun node(
        env: Map<String, String> = emptyMap(),
        vararg args: String,
    ): Pair<Int, String> {
        val builder = ProcessBuilder("node", *args).redirectErrorStream(true)
        builder.environment().putAll(env)
        val process = builder.start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(60, TimeUnit.SECONDS)) { "node did not finish" }
        return process.exitValue() to output
    }

    private fun dryRun(
        command: String,
        env: Map<String, String> = emptyMap(),
    ): Map<*, *> {
        val (exit, output) = node(env, "tools/e2e-stack.mjs", command, "--dry-run")
        exit shouldBe 0
        return JsonMapper().readValue(output.trim(), Map::class.java)
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<*, *>.docker() = this["docker"] as List<String>

    // @trace NFR-3
    @Test
    fun `down removes volumes of project doucko-e2e only, even when the environment names doucko`() {
        val plan = dryRun("down", mapOf("COMPOSE_PROJECT_NAME" to "doucko"))
        plan.docker() shouldContainInOrder listOf("compose", "-p", "doucko-e2e")
        plan.docker() shouldContainInOrder listOf("down", "-v")
        plan.docker() shouldNotContain "doucko"
    }

    // @trace NFR-3
    @Test
    fun `every command uses the committed test env file and the e2e override, never dot-env`() {
        for (command in listOf("up", "down", "ps")) {
            val args = dryRun(command).docker()
            args[args.indexOf("--env-file") + 1] shouldEndWith "e2e-stack.env"
            args.filterIndexed { i, _ -> i > 0 && args[i - 1] == "-f" }.map { File(it).name } shouldBe
                listOf("compose.yaml", "compose.e2e.yaml")
        }
    }

    // @trace NFR-3
    @Test
    fun `the guard refuses any project name except doucko-e2e`() {
        val script =
            "import('./tools/lib/e2e-stack.mjs').then(m => { " +
                "for (const p of ['doucko', '', 'doucko-e2e-x']) { try { m.assertE2eProject(p); console.log('ACCEPTED ' + p) } " +
                "catch (e) { console.log('refused ' + p) } } console.log(m.assertE2eProject('doucko-e2e')) })"
        val (exit, output) = node(emptyMap(), "-e", script)
        exit shouldBe 0
        output.lines().map { it.trim() } shouldContainInOrder listOf("refused doucko", "refused", "refused doucko-e2e-x")
        output.contains("ACCEPTED") shouldBe false
        output.trim() shouldEndWith "doucko-e2e"
    }

    // @trace NFR-2
    @Test
    fun `the override publishes only the frontend, on loopback port 18082`() {
        val override = File("compose.e2e.yaml").readText()
        override shouldContain "ports: !override"
        override shouldContain "\"127.0.0.1:18082:80\""
        Regex("""\d+\.\d+\.\d+\.\d+:\d+:""").findAll(override).map { it.value }.toList() shouldBe listOf("127.0.0.1:18082:")
    }

    // @trace NFR-3
    @Test
    fun `the frontend build context follows DOUCKO_FRONTEND_DIR, relative to the backend root`() {
        val root = File(".").absoluteFile.normalize()
        dryRun("up")["frontendDir"] shouldBe File(root.parentFile, "frontend").path
        dryRun("up", mapOf("DOUCKO_FRONTEND_DIR" to "../frontend-wt"))["frontendDir"] shouldBe File(root.parentFile, "frontend-wt").path
    }

    // @trace NFR-3
    @Test
    fun `unknown commands and options are refused with the usage`() {
        node(emptyMap(), "tools/e2e-stack.mjs", "nuke").let { (exit, output) ->
            exit shouldBe 2
            output shouldContain "usage"
        }
        node(emptyMap(), "tools/e2e-stack.mjs", "down", "--project", "doucko").first shouldBe 2
        node(emptyMap(), "tools/e2e-stack.mjs").first shouldBe 2
    }

    // @trace NFR-3
    @Test
    fun `the committed env file holds only clearly fake test values`() {
        val values =
            File("tools/e2e-stack.env")
                .readLines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .associate { it.substringBefore("=") to it.substringAfter("=") }
        values["ADMIN_PASSWORD"] shouldBe "e2e-admin-password-not-secret"
        values["DOUCKO_CHILD_DISPLAY_NAME"] shouldBe "Testovací žák"
        values.getValue("DOUCKO_DB_PASSWORD") shouldContain "not-secret"
    }
}
