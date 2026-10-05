package cz.doucko.platform

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * NFR-10: `npm run backup` / `npm run restore` round-trip the database through `docker compose exec`,
 * and the backup survives losing the Docker volume. Runs the `db` service (and, for the migration
 * test, a `backend` built from this checkout) under its own Compose project, so it never touches the
 * real `doucko` stack, its volumes or its image.
 */
class BackupRestoreIT {
    private val project = "doucko-it-" + UUID.randomUUID().toString().take(8)
    private val backupDir: File = Files.createTempDirectory("doucko-backup-it").toFile()
    private val env =
        mapOf(
            "DOUCKO_DB_PASSWORD" to "it-only-password",
            "ADMIN_PASSWORD" to "it-only-admin-password",
            // Fictional (NFR-3); also keeps the owner's .env value out of this project.
            "DOUCKO_CHILD_DISPLAY_NAME" to "Hrdinka",
        )

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

    private fun run(
        vararg command: String,
        timeoutSec: Long = 180,
    ): String {
        val (exit, output) = exec(*command, timeoutSec = timeoutSec)
        check(exit == 0) { "exit $exit: ${command.joinToString(" ")}\n$output" }
        return output
    }

    private fun compose(vararg args: String) = run("docker", "compose", "-p", project, *args)

    private fun sql(statement: String) = compose("exec", "-T", "db", "psql", "-U", "doucko", "-d", "doucko", "-tAc", statement).trim()

    /** A backup of the current data in its own directory; returns the dump file. */
    private fun backup(): File {
        val dumpDir = File(backupDir, "dumps").apply { mkdirs() }
        run("node", "tools/backup.mjs", "--project", project, "--out", dumpDir.path)
        return dumpDir.listFiles()!!.single()
    }

    private fun restore(
        dump: File,
        timeoutSec: Long = 180,
    ) = run(
        "node",
        "tools/restore.mjs",
        "--project",
        project,
        "--yes",
        "--backup-dir",
        File(backupDir, "safety").path,
        dump.path,
        timeoutSec = timeoutSec,
    )

    /** A minimal Flyway history at [version], as a database last migrated by that backend has it. */
    private fun historyAt(version: String) =
        sql(
            "CREATE TABLE flyway_schema_history (installed_rank int PRIMARY KEY, version varchar(50), success boolean NOT NULL); " +
                "INSERT INTO flyway_schema_history VALUES (1, '$version', true)",
        )

    /** A backend built from this checkout in this project (its own image), migrated and healthy. */
    private fun buildAndStartBackend() {
        run("docker", "compose", "-p", project, "build", "backend", timeoutSec = 1200)
        compose("up", "-d", "--wait", "--no-build", "backend")
    }

    @AfterEach
    fun tearDown() {
        // --rmi local: only the backend image this project built, never the owner's doucko-backend.
        runCatching { compose("down", "-v", "--rmi", "local") }
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
        val restored = run("node", "tools/restore.mjs", "--project", project, "--yes", "--backup-dir", backupDir.path, file.path)

        sql("SELECT label FROM it_probe WHERE id = 1") shouldBe "round-trip"
        restored shouldContain "doucko-storage"
        restored shouldContain "not restored"
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
        val dump = backup()
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
        val dump = backup()
        // A later migration added a table and a column that the dump does not know.
        sql("CREATE TABLE it_newer (id int); ALTER TABLE it_probe ADD COLUMN extra text")

        restore(dump)

        sql("SELECT label FROM it_probe WHERE id = 1") shouldBe "old"
        sql("SELECT count(*) FROM information_schema.tables WHERE table_name = 'it_newer'") shouldBe "0"
        sql("SELECT count(*) FROM information_schema.columns WHERE table_name = 'it_probe' AND column_name = 'extra'") shouldBe "0"
    }

    // @trace NFR-10
    @Test
    fun `restoring a dump from before the newest migration restarts the running backend, which migrates it again`() {
        compose("up", "-d", "--wait", "db")
        buildAndStartBackend()
        // The database as it was before V4 (V4__practice.sql; applied migrations never change).
        sql("DROP TABLE problem_report, opened_hint, attempt, practice_run; DELETE FROM flyway_schema_history WHERE version = '4'")
        val dump = backup()
        // Back to the newest schema, as a backend that has been running since V4 has it.
        compose("restart", "backend")
        compose("up", "-d", "--wait", "--no-build", "backend")
        sql("SELECT count(*) FROM information_schema.tables WHERE table_name = 'practice_run'") shouldBe "1"

        val output = restore(dump, timeoutSec = 600)

        output shouldContain "restarted"
        sql("SELECT count(*) FROM flyway_schema_history WHERE version = '4' AND success") shouldBe "1"
        sql("SELECT count(*) FROM information_schema.tables WHERE table_name = 'practice_run'") shouldBe "1"
        // The restarted backend serves the child area.
        compose("exec", "-T", "backend", "wget", "-qO-", "http://localhost:8080/api/child/hq") shouldContain "\"rank\""
    }

    // @trace NFR-10
    @Test
    fun `restoring an older dump with no backend running says its next start migrates it`() {
        compose("up", "-d", "--wait", "db")
        historyAt("1")
        val dump = backup()

        val output = restore(dump)

        output shouldContain "V1"
        output shouldContain "not running"
        output shouldContain "doucko-storage"
        output shouldNotContain "restarted now"
    }

    // @trace NFR-10
    @Test
    fun `restoring a dump newer than this checkout warns and restarts nothing`() {
        compose("up", "-d", "--wait", "db")
        historyAt("999")
        val dump = backup()
        // The live database is at this checkout's level again before the newer dump comes back.
        sql("UPDATE flyway_schema_history SET version = '1'")

        val output = restore(dump)

        output shouldContain "V999"
        output shouldContain "newer"
        output shouldNotContain "restarted now"
    }

    // @trace NFR-10
    @Test
    fun `when the restarted backend cannot migrate the restored schema, restore exits 1 and says the data is restored`() {
        compose("up", "-d", "--wait", "db")
        buildAndStartBackend()
        // History says V3 but V4's tables exist: Flyway's V4 fails on restart and the backend crash-loops.
        sql("DELETE FROM flyway_schema_history WHERE version = '4'")
        val dump = backup()

        val (exit, output) =
            exec(
                "node",
                "tools/restore.mjs",
                "--project",
                project,
                "--yes",
                "--backup-dir",
                File(backupDir, "safety").path,
                dump.path,
                timeoutSec = 600,
            )

        exit shouldBe 1
        output shouldContain "the data is restored, but the backend did not come back healthy"
        output shouldContain "-p $project restart backend"
        output shouldContain "-p $project logs backend"
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
