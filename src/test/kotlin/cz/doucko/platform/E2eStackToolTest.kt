package cz.doucko.platform

import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import org.apache.pdfbox.Loader
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The isolated e2e stack tool (tools/e2e-stack.mjs) only ever addresses Compose project
 * `doucko-e2e` with the committed fake test env file, so `down -v` cannot delete the owner's
 * project `doucko` and its data volume (NFR-10), publishes only loopback (NFR-2), and seeds only
 * self-authored, PII-free content (NFR-3, NFR-4). These checks run the pure parts through
 * `--dry-run` and the seed against an in-memory fake API (tools/test/e2e-seed-harness.mjs), without Docker.
 */
class E2eStackToolTest {
    @TempDir
    lateinit var tmp: File

    private fun node(
        env: Map<String, String> = emptyMap(),
        vararg args: String,
    ): Pair<Int, String> {
        val builder = ProcessBuilder("node", *args).redirectErrorStream(true)
        // The developer's own settings must not change what the test sees.
        builder.environment().keys.removeAll { it.uppercase() == "DOUCKO_FRONTEND_DIR" || it.uppercase().startsWith("COMPOSE_") }
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

    // @trace NFR-10
    @Test
    fun `down removes volumes of project doucko-e2e only, even when the environment names doucko`() {
        val plan = dryRun("down", mapOf("COMPOSE_PROJECT_NAME" to "doucko"))
        plan.docker() shouldContainInOrder listOf("compose", "-p", "doucko-e2e")
        plan.docker() shouldContainInOrder listOf("down", "-v")
        plan.docker() shouldNotContain "doucko"
    }

    // @trace NFR-10
    @Test
    fun `every command uses the committed test env file and the e2e override, never dot-env`() {
        for (command in listOf("up", "down", "ps")) {
            val args = dryRun(command).docker()
            args[args.indexOf("--env-file") + 1] shouldEndWith "e2e-stack.env"
            args.filterIndexed { i, _ -> i > 0 && args[i - 1] == "-f" }.map { File(it).name } shouldBe
                listOf("compose.yaml", "compose.e2e.yaml")
        }
    }

    // @trace NFR-10
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

    // @trace NFR-10
    @Test
    fun `the frontend build context follows DOUCKO_FRONTEND_DIR, relative to the backend root`() {
        val root = File(".").absoluteFile.normalize()
        dryRun("up")["frontendDir"] shouldBe File(root.parentFile, "frontend").path
        dryRun("up", mapOf("DOUCKO_FRONTEND_DIR" to "../frontend-wt"))["frontendDir"] shouldBe File(root.parentFile, "frontend-wt").path
    }

    // @trace NFR-10
    @Test
    fun `unknown commands and options are refused with the usage`() {
        node(emptyMap(), "tools/e2e-stack.mjs", "nuke").let { (exit, output) ->
            exit shouldBe 2
            output shouldContain "usage"
        }
        node(emptyMap(), "tools/e2e-stack.mjs", "down", "--project", "doucko").first shouldBe 2
        node(emptyMap(), "tools/e2e-stack.mjs").first shouldBe 2
    }

    // @trace NFR-10
    @Test
    fun `secrets and compose selectors from the caller's environment never reach compose, whatever their case`() {
        val plan =
            dryRun(
                "up",
                mapOf(
                    "ADMIN_PASSWORD" to "real-one",
                    "DOUCKO_DB_PASSWORD" to "real-one",
                    "Doucko_Child_Display_Name" to "Someone",
                    "compose_file" to "compose.yaml",
                    "COMPOSE_PROJECT_NAME" to "doucko",
                    "DOUCKO_BIND_ADDRESS" to "0.0.0.0",
                ),
            )
        plan["leakedEnv"] shouldBe emptyList<String>()
    }

    // @trace NFR-4
    @Test
    fun `seeding twice leaves one source, three published tasks of different types and one published mission`() {
        val (exit, output) = node(emptyMap(), "tools/test/e2e-seed-harness.mjs", "twice")
        exit shouldBe 0
        val state = JsonMapper().readValue(output.trim(), Map::class.java)
        state["same"] shouldBe true
        state["sources"] shouldBe listOf("E2E Seed Source")
        state["tasks"] shouldBe
            listOf(
                "PUBLISHED INTEGER E2E Seed: Kolik je 6 · 7?",
                "PUBLISHED DECIMAL E2E Seed: Kolik je 1,5 + 2,25?",
                "PUBLISHED CHOICE_SINGLE E2E Seed: Které číslo je sudé?",
            )
        state["missions"] shouldBe listOf("PUBLISHED 1 3")
    }

    // @trace NFR-4
    @Test
    fun `the seed refuses to adopt a mission at its position that holds other tasks`() {
        val (exit, output) = node(emptyMap(), "tools/test/e2e-seed-harness.mjs", "foreign")
        exit shouldBe 0
        output shouldContain "refused: the mission at topic"
        output shouldContain "not a seeded state"
    }

    // @trace NFR-4
    @Test
    fun `the generated seed PDF is a valid blank two-page PDF`() {
        val pdf = File(tmp, "seed.pdf")
        node(emptyMap(), "tools/test/e2e-seed-harness.mjs", "pdf", pdf.path).first shouldBe 0
        Loader.loadPDF(pdf).use { doc ->
            doc.numberOfPages shouldBe 2
            doc.pages.forEach { it.contents.readAllBytes().size shouldBe 0 }
        }
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
