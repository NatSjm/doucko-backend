package cz.doucko.platform

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * CA-D21 (content-authoring "Copyright-safe content"): every fixture file is listed in
 * `fixtures/PROVENANCE.md` with origin `self-authored` and an author; listed files exist and lie
 * under a fixture root. Fixture roots cover every place a data file can be committed: test and main
 * resources and non-Kotlin files beside the tests. Only application config, the message catalog
 * and Flyway DDL are exempt.
 */
class FixtureProvenanceTest {
    @TempDir
    lateinit var tmp: File

    private companion object {
        val ROOTS = listOf("src/test/resources", "src/test/kotlin", "src/main/resources", "fixtures")
        val EXEMPT =
            listOf(
                Regex("""fixtures/PROVENANCE\.md"""),
                Regex(""".*/\.gitkeep"""),
                Regex("""src/test/kotlin/.*\.kt"""),
                Regex("""src/main/resources/application(-[\w-]+)?\.ya?ml"""),
                Regex("""src/main/resources/messages(_[\w-]+)?\.properties"""),
                Regex("""src/main/resources/db/migration/V\d+(_\d+)*__\w+\.sql"""),
            )
        val ROW = Regex("""^\|\s*`([^`]+)`\s*\|([^|]*)\|([^|]*)\|([^|]*)\|$""")
    }

    /** Every problem with the manifest under [repo]; empty when the provenance is complete. */
    private fun provenanceProblems(repo: File): List<String> {
        val problems = mutableListOf<String>()
        val manifest = File(repo, "fixtures/PROVENANCE.md")
        if (!manifest.isFile) return listOf("fixtures/PROVENANCE.md is missing")

        val table =
            manifest
                .readLines()
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
                ".." in path.split('/') || ROOTS.none { path.startsWith("$it/") } -> problems += "$path: not under a fixture root"
                !File(repo, path).isFile -> problems += "$path: listed but does not exist"
                origin.trim() != "self-authored" -> problems += "$path: origin is '${origin.trim()}', not self-authored"
                author.isBlank() -> problems += "$path: no author"
            }
        }
        ROOTS
            .map { File(repo, it) }
            .filter(File::isDirectory)
            .flatMap { root -> root.walkTopDown().filter(File::isFile).toList() }
            .map { it.relativeTo(repo).invariantSeparatorsPath }
            .filterNot { path -> EXEMPT.any { it.matches(path) } }
            .filterNot { it in listed }
            .forEach { problems += "$it: not listed in fixtures/PROVENANCE.md" }
        return problems
    }

    private fun repoWith(
        manifestRows: String,
        vararg files: String,
    ): File {
        File(tmp, "fixtures").mkdirs()
        File(tmp, "fixtures/PROVENANCE.md").writeText("# x\n\n| Path | Origin | Author | What |\n|---|---|---|---|\n$manifestRows")
        for (f in files) File(tmp, f).apply { parentFile.mkdirs() }.writeText("x")
        return tmp
    }

    // @trace NFR-4
    @Test
    fun `this repository's fixtures all have self-authored provenance`() {
        provenanceProblems(File(".")).shouldBeEmpty()
    }

    // @trace NFR-4
    @Test
    fun `an unlisted fixture fails the check and is named`() {
        val repo =
            repoWith(
                "",
                "src/test/resources/page.png",
                "fixtures/self-authored/answers.json",
                "src/main/resources/seed/tasks.csv",
                "src/test/kotlin/cz/doucko/sample.pdf",
            )
        provenanceProblems(repo) shouldContainExactlyInAnyOrder
            listOf(
                "src/test/resources/page.png: not listed in fixtures/PROVENANCE.md",
                "fixtures/self-authored/answers.json: not listed in fixtures/PROVENANCE.md",
                "src/main/resources/seed/tasks.csv: not listed in fixtures/PROVENANCE.md",
                "src/test/kotlin/cz/doucko/sample.pdf: not listed in fixtures/PROVENANCE.md",
            )
    }

    // @trace NFR-4
    @Test
    fun `bad manifest rows fail the check`() {
        val repo =
            repoWith(
                """
                | `fixtures/a.json` | scanned | someone | a |
                | `fixtures/b.json` | self-authored |  | b |
                | `fixtures/gone.json` | self-authored | me | c |
                | `../outside.pdf` | self-authored | me | d |
                | fixtures/c.json | self-authored | me | no backticks |
                """.trimIndent() + "\n",
                "fixtures/a.json",
                "fixtures/b.json",
                "fixtures/c.json",
            )
        provenanceProblems(repo) shouldContainExactlyInAnyOrder
            listOf(
                "fixtures/a.json: origin is 'scanned', not self-authored",
                "fixtures/b.json: no author",
                "fixtures/gone.json: listed but does not exist",
                "../outside.pdf: not under a fixture root",
                "malformed manifest row: | fixtures/c.json | self-authored | me | no backticks |",
                "fixtures/c.json: not listed in fixtures/PROVENANCE.md",
            )
    }
}
