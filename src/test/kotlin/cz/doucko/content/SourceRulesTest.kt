package cz.doucko.content

import cz.doucko.content.domain.FileKind
import cz.doucko.content.domain.In
import cz.doucko.content.domain.SourceDraft
import cz.doucko.content.domain.SourceMetadata
import cz.doucko.content.domain.SourceRules
import cz.doucko.content.domain.Text
import cz.doucko.content.domain.Validation
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** content-authoring "Upload a PDF source" metadata rules, file kinds by content (FR-1, NFR-17, CA-D1). */
class SourceRulesTest {
    private fun draft(
        title: In<String> = In.Ok("  Matematika 6 – pracovní sešit "),
        publisher: In<String> = In.Ok("Testovací nakladatelství"),
        edition: In<String> = In.Ok("1"),
        grade: In<Int> = In.Ok(6),
        subject: In<String> = In.Ok("math"),
    ) = SourceDraft(title, publisher, edition, grade, subject)

    private fun errors(draft: SourceDraft): List<Pair<String, String>> {
        val result = SourceRules.validate(draft, listOf(6), listOf("math"))
        result.shouldBeInstanceOf<Validation.Invalid>()
        return result.errors.map { it.field to it.code }
    }

    // @trace FR-1
    @Test
    fun `valid metadata is trimmed, publisher and edition are optional`() {
        SourceRules.validate(draft(), listOf(6), listOf("math")) shouldBe
            Validation.Valid(SourceMetadata("Matematika 6 – pracovní sešit", "Testovací nakladatelství", "1", 6, "math"))
        val bare = SourceRules.validate(draft(publisher = In.Missing, edition = In.Ok("  ")), listOf(6), listOf("math"))
        bare shouldBe Validation.Valid(SourceMetadata("Matematika 6 – pracovní sešit", null, null, 6, "math"))
    }

    // @trace FR-1
    @Test
    fun `title is required and limited, publisher and edition are limited`() {
        errors(draft(title = In.Missing)) shouldContainExactly listOf("title" to "required")
        errors(draft(title = In.Ok("   "))) shouldContainExactly listOf("title" to "required")
        errors(draft(title = In.Ok("t".repeat(201)))) shouldContainExactly listOf("title" to "too-long")
        errors(draft(publisher = In.Ok("p".repeat(201)))) shouldContainExactly listOf("publisher" to "too-long")
        errors(draft(edition = In.Ok("e".repeat(51)))) shouldContainExactly listOf("edition" to "too-long")
        errors(draft(edition = In.Invalid)) shouldContainExactly listOf("edition" to "wrong-type")
    }

    // @trace FR-1
    @Test
    fun `grade and subject must be configured values`() {
        errors(draft(grade = In.Ok(7))) shouldContainExactly listOf("grade" to "grade-allowed")
        errors(draft(grade = In.Invalid)) shouldContainExactly listOf("grade" to "grade-allowed")
        errors(draft(subject = In.Ok("physics"))) shouldContainExactly listOf("subject" to "subject-allowed")
        errors(draft(subject = In.Missing, title = In.Missing)) shouldContainExactly
            listOf("title" to "required", "subject" to "subject-allowed")
    }

    // @trace NFR-17
    @Test
    fun `the file kind comes from the content, not the name`() {
        FileKind.detect(Files.pdf(1)) shouldBe FileKind.PDF
        FileKind.detect(Files.png()) shouldBe FileKind.PNG
        FileKind.detect(Files.jpg()) shouldBe FileKind.JPEG
        FileKind.detect(Files.gif()) shouldBe FileKind.OTHER
        FileKind.detect(Files.docx()) shouldBe FileKind.OTHER
        FileKind.detect(ByteArray(0)) shouldBe FileKind.OTHER
    }

    // @trace FR-11
    @Test
    fun `folding removes case and diacritics, code points count non-BMP letters once`() {
        Text.fold("OBDÉLNÍK Žluťoučký") shouldBe "obdelnik zlutoucky"
        Text.codePoints("𝔸b") shouldBe 2
        Text.trim("  x  ") shouldBe "x"
    }
}
