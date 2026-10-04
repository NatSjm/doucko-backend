package cz.doucko.platform

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeGreaterThan
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * CA-D21 (content-authoring "Copyright-safe content"): every fixture file is listed in
 * `fixtures/PROVENANCE.md` with origin `self-authored` and an author; listed files exist and lie
 * in scope. Scope: every file under `src/` except Kotlin sources, everything under `fixtures/`,
 * and every image, document, audio or video file anywhere in git, docs/qa/ included except the
 * recordings a docs/qa recording manifest lists. Exempt: `application.yaml`, the
 * logging config, the message catalog, and Flyway migrations with no data statement anywhere (comments
 * aside).
 */
class FixtureProvenanceTest {
    private companion object {
        const val MANIFEST = "fixtures/PROVENANCE.md"
        val MEDIA =
            Regex(
                """(?i).*\.(pdf|djvu|epub|png|jpe?g|jfif|gif|webp|avif|svg|bmp|tiff?|heic|heif|psd|""" +
                    """docx?|xlsx?|pptx?|odt|ods|odp|rtf|csv|tsv|txt|mp3|wav|ogg|opus|flac|aac|m4a|mp4|m4v|webm|mov|mkv|avi)$""",
            )

        /** The pinned contract (the docs store checks it) and build output. docs/qa/ is in scope, except recordings. */
        val OUT_OF_SCOPE = listOf("contract/", "target/", "node_modules/")
        val CONFIG = Regex("""src/main/resources/(application\.yaml|logback-spring\.xml|messages(_[\w-]+)?\.properties)""")
        val MIGRATION = Regex("""src/main/resources/db/migration/V\d+(_\d+)*__\w+\.sql""")

        /** A statement that writes rows, anywhere in the file (comments removed first). */
        val DATA_STATEMENT = Regex("""(?i)\b(insert\s+into|copy\s+["\w.]+|update\s+["\w.]+\s+set|merge\s+into)\b""")
        val SQL_COMMENT = Regex("""--[^\n]*|/\*[\s\S]*?\*/""")

        /** What the recorder writes per clip into a docs/qa recording manifest. */
        val RECORDING = mapOf("video" to Regex("""(?i).*\.webm$"""), "screenshot" to Regex("""(?i).*\.png$"""))
        val ROW = Regex("""^\|\s*`([^`]+)`\s*\|([^|]*)\|([^|]*)\|([^|]*)\|$""")
    }

    /** The recordings (results[].video .webm, results[].screenshot .png) that docs/qa recording manifests list. */
    private fun recordings(
        files: List<String>,
        read: (String) -> String,
    ): Set<String> =
        files
            .filter { it.startsWith("docs/qa/") && it.endsWith("/manifest.json") }
            .flatMap { manifest ->
                val dir = manifest.substringBeforeLast('/') + "/"
                val results =
                    runCatching {
                        JsonMapper
                            .builder()
                            .build()
                            .readTree(read(manifest))
                            .get("results")
                    }.getOrNull()
                results?.toList().orEmpty().flatMap { r ->
                    RECORDING.mapNotNull { (key, ext) ->
                        r.get(key)?.takeIf { it.isString }?.asString()?.takeIf { it.matches(ext) }?.let { v ->
                            (
                                if (v.startsWith(
                                        "docs/",
                                    )
                                ) {
                                    v
                                } else {
                                    dir + v.removePrefix("./")
                                }
                            ).takeIf { it.startsWith("docs/qa/") && ".." !in it }
                        }
                    }
                }
            }.toSet()

    private fun inScope(
        path: String,
        read: (String) -> String,
        recorded: Set<String> = emptySet(),
    ): Boolean =
        when {
            path == MANIFEST || path.endsWith("/.gitkeep") || OUT_OF_SCOPE.any { path.startsWith(it) } -> false
            path in recorded -> false
            path.matches(CONFIG) -> false
            path.matches(MIGRATION) -> DATA_STATEMENT.containsMatchIn(read(path).replace(SQL_COMMENT, " "))
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
                ".." in path.split('/') || !inScope(path, { "INSERT INTO x" }) -> problems += "$path: not a fixture path"
                !exists(path) -> problems += "$path: listed but does not exist"
                origin.trim() != "self-authored" -> problems += "$path: origin is '${origin.trim()}', not self-authored"
                author.isBlank() -> problems += "$path: no author"
            }
        }
        val recorded = recordings(files, read)
        files.filter { inScope(it, read, recorded) && it !in listed }.forEach { problems += "$it: not listed in $MANIFEST" }
        return problems
    }

    /** NUL-separated (`-z`), so git neither C-quotes nor escapes non-ASCII names such as `úloha.json`. */
    private fun gitFiles(): List<String> {
        val process = ProcessBuilder("git", "ls-files", "-z", "--cached", "--others", "--exclude-standard").start()
        val out = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        val err = process.errorStream.readAllBytes().toString(Charsets.UTF_8)
        check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { "git ls-files failed: $err" }
        return out.split('\u0000').filter { it.isNotEmpty() && File(it).isFile }
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
    fun `docs-qa is in scope except the recordings a recording manifest lists`() {
        val files =
            listOf(
                "docs/qa/scan.png",
                "docs/qa/report.pdf",
                "docs/qa/demo/manifest.json",
                "docs/qa/demo/a.webm",
                "docs/qa/demo/a.png",
                "docs/qa/demo/book.epub",
                "docs/qa/evidence/slice1-compose-smoke.md",
            )
        val recordingManifest = """{"kind":"demo","results":[{"id":"a","video":"a.webm","screenshot":"a.png","extra":"book.epub"}]}"""
        val read = { p: String -> if (p == "docs/qa/demo/manifest.json") recordingManifest else "" }
        provenanceProblems(files, manifestWith(""), { true }, read) shouldContainExactlyInAnyOrder
            listOf(
                "docs/qa/scan.png: not listed in fixtures/PROVENANCE.md",
                "docs/qa/report.pdf: not listed in fixtures/PROVENANCE.md",
                "docs/qa/demo/book.epub: not listed in fixtures/PROVENANCE.md",
            )
    }

    // @trace NFR-4
    @Test
    fun `a data statement anywhere in a migration puts it in scope, comments do not`() {
        val migration = "src/main/resources/db/migration/V4__x.sql"
        val inlined = "CREATE TABLE t (x text); INSERT INTO t VALUES ('task');"
        val commented =
            listOf(
                "-- INSERT INTO t is not done here",
                "/* COPY t FROM stdin */",
                "CREATE TABLE t (x text REFERENCES u ON UPDATE CASCADE);",
            ).joinToString("\n")
        provenanceProblems(listOf(migration), manifestWith(""), { true }, { inlined }) shouldContainExactlyInAnyOrder
            listOf("$migration: not listed in fixtures/PROVENANCE.md")
        provenanceProblems(listOf(migration), manifestWith(""), { true }, { commented }).shouldBeEmpty()
    }

    // @trace NFR-4
    @Test
    fun `git file names with Czech characters are read as they are, not C-quoted`() {
        val file = File("src/test/resources/provenance-probe-úloha.json")
        file.parentFile.mkdirs()
        file.writeText("{}")
        try {
            gitFiles() shouldContain "src/test/resources/provenance-probe-úloha.json"
        } finally {
            file.delete()
        }
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
