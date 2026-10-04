package cz.doucko.platform

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeGreaterThan
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * CA-D21 (content-authoring "Copyright-safe content"): every fixture file is listed in
 * `fixtures/PROVENANCE.md` with origin `self-authored` and an author; listed files exist and lie
 * in scope. Scope: every file under `src/` except Kotlin sources, everything under `fixtures/`,
 * and every image, document, audio or video file anywhere in git. Exempt: `application.yaml`, the
 * logging config, the message catalog, and Flyway migrations that hold no data statements (pure DDL).
 */
class FixtureProvenanceTest {
    private companion object {
        const val MANIFEST = "fixtures/PROVENANCE.md"
        val MEDIA =
            Regex("""(?i).*\.(pdf|png|jpe?g|gif|webp|avif|svg|bmp|tiff?|heic|docx?|xlsx?|odt|ods|csv|mp3|wav|ogg|m4a|mp4|webm|mov)$""")
        val OUT_OF_SCOPE = listOf("contract/", "target/", "node_modules/", "docs/qa/")
        val CONFIG = Regex("""src/main/resources/(application\.yaml|logback-spring\.xml|messages(_[\w-]+)?\.properties)""")
        val MIGRATION = Regex("""src/main/resources/db/migration/V\d+(_\d+)*__\w+\.sql""")
        val DATA_STATEMENT = Regex("""(?im)^\s*(insert|copy|update)\b""")
        val ROW = Regex("""^\|\s*`([^`]+)`\s*\|([^|]*)\|([^|]*)\|([^|]*)\|$""")
    }

    private fun inScope(
        path: String,
        read: (String) -> String,
    ): Boolean =
        when {
            path == MANIFEST || path.endsWith("/.gitkeep") || OUT_OF_SCOPE.any { path.startsWith(it) } -> false
            path.matches(CONFIG) -> false
            path.matches(MIGRATION) -> DATA_STATEMENT.containsMatchIn(read(path))
            path.startsWith("src/") -> !path.endsWith(".kt") && !path.endsWith(".kts")
            path.startsWith("fixtures/") -> true
            else -> path.matches(MEDIA)
        }

    /** Every problem with the manifest for a repository holding [files]; empty when the provenance is complete. */
    private fun provenanceProblems(
        files: List<String>,
        manifest: String,
        exists: (String) -> Boolean,
        read: (String) -> String,
    ): List<String> {
        val problems = mutableListOf<String>()
        val table =
            manifest
                .lines()
                .map(String::trim)
                .filter { it.startsWith("|") }
                .drop(2) // header + separator
        val listed = mutableSetOf<String>()
        for (row in table) {
            val m = ROW.matchEntire(row)
            if (m == null) {
                problems += "malformed manifest row: $row"
                continue
            }
            val (path, origin, author) = m.destructured
            listed += path
            when {
                ".." in path.split('/') || !inScope(path) { "INSERT" } -> problems += "$path: not a fixture path"
                !exists(path) -> problems += "$path: listed but does not exist"
                origin.trim() != "self-authored" -> problems += "$path: origin is '${origin.trim()}', not self-authored"
                author.isBlank() -> problems += "$path: no author"
            }
        }
        files.filter { inScope(it, read) && it !in listed }.forEach { problems += "$it: not listed in $MANIFEST" }
        return problems
    }

    private fun gitFiles(): List<String> {
        val process = ProcessBuilder("git", "ls-files", "--cached", "--others", "--exclude-standard").redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readLines()
        check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { "git ls-files failed: $out" }
        return out.filter { File(it).isFile }
    }

    private fun manifestWith(rows: String) = "# x\n\n| Path | Origin | Author | What |\n|---|---|---|---|\n$rows"

    // @trace NFR-4
    @Test
    fun `this repository's fixtures all have self-authored provenance`() {
        val files = gitFiles()
        files.size shouldBeGreaterThan 0
        provenanceProblems(files, File(MANIFEST).readText(), { File(it).isFile }, { File(it).readText() }).shouldBeEmpty()
    }

    // @trace NFR-4
    @Test
    fun `an unlisted fixture, data file or media file fails the check and is named`() {
        val files =
            listOf(
                "src/test/resources/page.png",
                "src/main/kotlin/cz/doucko/checking/answers.json",
                "src/main/resources/seed/tasks.csv",
                "src/main/resources/application-seed.yaml",
                "src/main/resources/db/migration/V3__seed_tasks.sql",
                "src/main/resources/db/migration/V2__tables.sql",
                "src/main/resources/application.yaml",
                "src/main/kotlin/cz/doucko/App.kt",
                "fixtures/self-authored/answers.json",
                "tools/scan.pdf",
                "README.md",
            )
        val sql = mapOf("src/main/resources/db/migration/V3__seed_tasks.sql" to "CREATE TABLE t (x text);\nINSERT INTO t VALUES ('task');")
        provenanceProblems(files, manifestWith(""), { true }, { sql[it] ?: "CREATE TABLE t (x text);" }) shouldContainExactlyInAnyOrder
            listOf(
                "src/test/resources/page.png: not listed in fixtures/PROVENANCE.md",
                "src/main/kotlin/cz/doucko/checking/answers.json: not listed in fixtures/PROVENANCE.md",
                "src/main/resources/seed/tasks.csv: not listed in fixtures/PROVENANCE.md",
                "src/main/resources/application-seed.yaml: not listed in fixtures/PROVENANCE.md",
                "src/main/resources/db/migration/V3__seed_tasks.sql: not listed in fixtures/PROVENANCE.md",
                "fixtures/self-authored/answers.json: not listed in fixtures/PROVENANCE.md",
                "tools/scan.pdf: not listed in fixtures/PROVENANCE.md",
            )
    }

    // @trace NFR-4
    @Test
    fun `bad manifest rows fail the check`() {
        val rows =
            """
            | `fixtures/a.json` | scanned | someone | a |
            | `fixtures/b.json` | self-authored |  | b |
            | `fixtures/gone.json` | self-authored | me | c |
            | `../outside.pdf` | self-authored | me | d |
            | `src/main/kotlin/cz/doucko/App.kt` | self-authored | me | source, not a fixture |
            | fixtures/c.json | self-authored | me | no backticks |
            """.trimIndent()
        val files = listOf("fixtures/a.json", "fixtures/b.json", "fixtures/c.json")
        provenanceProblems(files, manifestWith(rows), { it != "fixtures/gone.json" }, { "" }) shouldContainExactlyInAnyOrder
            listOf(
                "fixtures/a.json: origin is 'scanned', not self-authored",
                "fixtures/b.json: no author",
                "fixtures/gone.json: listed but does not exist",
                "../outside.pdf: not a fixture path",
                "src/main/kotlin/cz/doucko/App.kt: not a fixture path",
                "malformed manifest row: | fixtures/c.json | self-authored | me | no backticks |",
                "fixtures/c.json: not listed in fixtures/PROVENANCE.md",
            )
    }
}
