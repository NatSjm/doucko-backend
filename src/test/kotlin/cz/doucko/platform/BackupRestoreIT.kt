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

    private fun exec(
        vararg command: String,
        timeoutSec: Long = 180,
    ): Pair<Int, String> {
        val builder = ProcessBuilder(*command).redirectErrorStream(true)
        builder.environment().putAll(env)
        val process = builder.start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(timeoutSec, TimeUnit.SECONDS)) { "timed out: ${command.joinToString(" ")}" }
        return process.exitValue() to output
    }

    private fun run(vararg command: String): String {
        val (exit, output) = exec(*command)
        check(exit == 0) { "exit $exit: ${command.joinToString(" ")}\n$output" }
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
        run("node", "tools/restore.mjs", "--project", project, "--yes", "--backup-dir", backupDir.path, file.path)

        sql("SELECT label FROM it_probe WHERE id = 1") shouldBe "round-trip"
    }

    // @trace NFR-10
    @Test
    fun `a broken dump leaves the live data untouched and a safety backup is taken first`() {
        compose("up", "-d", "--wait", "db")
        // Enough incompressible rows that the dump is mostly table data: the cut lands after the TOC,
        // i.e. after pg_restore --clean has already dropped the table.
        sql(
            "CREATE TABLE it_probe (id int PRIMARY KEY, label text NOT NULL); " +
                "INSERT INTO it_probe SELECT g, CASE WHEN g = 1 THEN 'live' ELSE md5(g::text) END FROM generate_series(1, 20000) g",
        )
        val dumpDir = File(backupDir, "dumps").apply { mkdirs() }
        run("node", "tools/backup.mjs", "--project", project, "--out", dumpDir.path)
        val dump = dumpDir.listFiles()!!.single()
        val truncated = File(backupDir, "truncated.dump").apply { writeBytes(dump.readBytes().copyOf((dump.length() * 2 / 3).toInt())) }
        val safetyDir = File(backupDir, "safety")

        val (exit, output) =
            exec(
                "node",
                "tools/restore.mjs",
                "--project",
                project,
                "--yes",
                "--backup-dir",
                safetyDir.path,
                truncated.path,
            )

        (exit != 0) shouldBe true
        output shouldContain "restore:"
        sql("SELECT label FROM it_probe WHERE id = 1") shouldBe "live"
        (safetyDir.listFiles()?.size ?: 0) shouldBe 1
    }

    // @trace NFR-10
    @Test
    fun `restoring an older dump leaves no object from the newer schema behind`() {
        compose("up", "-d", "--wait", "db")
        sql("CREATE TABLE it_probe (id int PRIMARY KEY, label text NOT NULL); INSERT INTO it_probe VALUES (1, 'old')")
        val dumpDir = File(backupDir, "dumps").apply { mkdirs() }
        run("node", "tools/backup.mjs", "--project", project, "--out", dumpDir.path)
        val dump = dumpDir.listFiles()!!.single()
        // A later migration added a table and a column that the dump does not know.
        sql("CREATE TABLE it_newer (id int); ALTER TABLE it_probe ADD COLUMN extra text")

        run("node", "tools/restore.mjs", "--project", project, "--yes", "--backup-dir", File(backupDir, "safety").path, dump.path)

        sql("SELECT label FROM it_probe WHERE id = 1") shouldBe "old"
        sql("SELECT count(*) FROM information_schema.tables WHERE table_name = 'it_newer'") shouldBe "0"
        sql("SELECT count(*) FROM information_schema.columns WHERE table_name = 'it_probe' AND column_name = 'extra'") shouldBe "0"
    }

    // @trace NFR-10
    @Test
    fun `restore without --yes refuses and changes nothing`() {
        val dump = File(backupDir, "any.dump").apply { writeText("not used") }
        val (exit, output) = exec("node", "tools/restore.mjs", "--project", project, dump.path)
        exit shouldBe 1
        output shouldContain "--yes"
    }
}
