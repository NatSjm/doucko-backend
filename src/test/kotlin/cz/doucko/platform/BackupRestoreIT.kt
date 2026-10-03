package cz.doucko.platform

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * NFR-10: `npm run backup` / `npm run restore` round-trip the database through `docker compose exec`,
 * and the backup survives losing the Docker volume. Runs only the `db` service under its own
 * Compose project, so it never touches the real `doucko` stack or volume.
 */
class BackupRestoreIT {
    private val project = "doucko-it-" + UUID.randomUUID().toString().take(8)
    private val backupDir: File = Files.createTempDirectory("doucko-backup-it").toFile()
    private val env = mapOf("DOUCKO_DB_PASSWORD" to "it-only-password")

    private fun run(
        vararg command: String,
        timeoutSec: Long = 180,
    ): String {
        val builder = ProcessBuilder(*command).redirectErrorStream(true)
        builder.environment().putAll(env)
        val process = builder.start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(timeoutSec, TimeUnit.SECONDS)) { "timed out: ${command.joinToString(" ")}" }
        check(process.exitValue() == 0) { "exit ${process.exitValue()}: ${command.joinToString(" ")}\n$output" }
        return output
    }

    private fun compose(vararg args: String) = run("docker", "compose", "-p", project, *args)

    private fun sql(statement: String) = compose("exec", "-T", "db", "psql", "-U", "doucko", "-d", "doucko", "-tAc", statement).trim()

    @AfterEach
    fun tearDown() {
        runCatching { compose("down", "-v") }
        backupDir.deleteRecursively()
    }

    // @trace NFR-10
    @Test
    fun `a backup survives losing the volume and restores the data`() {
        compose("up", "-d", "--wait", "db")
        sql("CREATE TABLE it_probe (id int PRIMARY KEY, label text NOT NULL); INSERT INTO it_probe VALUES (1, 'round-trip')")

        val written = run("node", "tools/backup.mjs", "--project", project, "--out", backupDir.path)
        written shouldContain "backup written:"
        val file = backupDir.listFiles()!!.single()
        (file.length() > 0) shouldBe true

        compose("down", "-v")
        compose("up", "-d", "--wait", "db")
        run("node", "tools/restore.mjs", "--project", project, "--yes", file.path)

        sql("SELECT label FROM it_probe WHERE id = 1") shouldBe "round-trip"
    }
}
