package cz.doucko.platform

import io.kotest.matchers.collections.shouldBeEmpty
import org.junit.jupiter.api.Test
import java.io.File

/**
 * CA-D21 (content-authoring "Copyright-safe content"): every fixture file is listed in
 * `fixtures/PROVENANCE.md` with origin `self-authored` and an author; listed files exist.
 */
class FixtureProvenanceTest {
    private val manifest = File("fixtures/PROVENANCE.md")
    private val fixtureRoots = listOf("src/test/resources", "fixtures")

    private data class Entry(
        val path: String,
        val origin: String,
        val author: String,
    )

    /** Rows of the manifest table: `| path | origin | author | what it is |`. */
    private fun entries(): List<Entry> =
        manifest
            .readLines()
            .map { it.trim() }
            .filter { it.startsWith("| `") }
            .map { row ->
                val cells = row.trim('|').split("|").map { it.trim().trim('`') }
                Entry(cells[0], cells.getOrElse(1) { "" }, cells.getOrElse(2) { "" })
            }

    private fun fixtureFiles(): List<String> =
        fixtureRoots
            .map(::File)
            .filter(File::isDirectory)
            .flatMap { root -> root.walkTopDown().filter(File::isFile).toList() }
            .map { it.invariantSeparatorsPath }
            .filterNot { it == "fixtures/PROVENANCE.md" || it.endsWith("/.gitkeep") }

    // @trace NFR-4
    @Test
    fun `every fixture file is listed in the manifest`() {
        val listed = entries().map { it.path }.toSet()
        fixtureFiles().filterNot { it in listed }.shouldBeEmpty()
    }

    // @trace NFR-4
    @Test
    fun `every manifest entry is self-authored, has an author and exists`() {
        entries()
            .filterNot { it.origin == "self-authored" && it.author.isNotBlank() && File(it.path).isFile }
            .shouldBeEmpty()
    }
}
