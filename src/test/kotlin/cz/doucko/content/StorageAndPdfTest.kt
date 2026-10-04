package cz.doucko.content

import cz.doucko.content.adapter.out.files.FilesystemFileStorage
import cz.doucko.content.adapter.out.files.StorageRoot
import cz.doucko.content.adapter.out.pdf.PdfBoxInspector
import cz.doucko.content.application.ContentProperties
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.nio.file.Files as NioFiles

/** NFR-17 storage outside every repository, and the PDF page-count reader (FR-1, CA-D2). */
class StorageAndPdfTest {
    private val pdf = PdfBoxInspector()

    // @trace FR-1
    @Test
    fun `the page count of a valid PDF is read`() {
        pdf.pageCount(Files.pdf(120)) shouldBe 120
        pdf.pageCount(Files.pdf(1)) shouldBe 1
    }

    // @trace NFR-17
    @Test
    fun `encrypted, truncated and broken PDFs have no page count`() {
        pdf.pageCount(Files.encryptedPdf()).shouldBeNull()
        pdf.pageCount(Files.truncatedPdf()).shouldBeNull()
        pdf.pageCount(Files.brokenPageTreePdf()).shouldBeNull()
        pdf.pageCount("%PDF-1.4\n%%EOF\n".toByteArray()).shouldBeNull()
        pdf.pageCount(Files.png()).shouldBeNull()
    }

    // @trace NFR-17
    @Test
    fun `a storage root inside a git working tree is refused`() {
        val insideThisRepository = File("target/storage-inside-repo").absolutePath
        val failure = shouldThrow<IllegalStateException> { StorageRoot.check(insideThisRepository) }
        failure.message!! shouldContain StorageRoot.SETTING
        shouldThrow<IllegalStateException> { StorageRoot.check(" ") }
    }

    // @trace NFR-17
    @Test
    fun `files are stored under the root, read back and deleted`(
        @TempDir dir: Path,
    ) {
        val storage = FilesystemFileStorage(ContentProperties(storageDir = dir.toString()))
        storage.store("sources/a.pdf", byteArrayOf(1, 2, 3))
        storage.locate("sources/a.pdf").startsWith(dir.toAbsolutePath()) shouldBe true
        storage.open("sources/a.pdf")!!.use { it.readAllBytes().toList() } shouldBe listOf<Byte>(1, 2, 3)
        NioFiles.list(dir.resolve("sources")).use { it.count() } shouldBe 1
        storage.delete("sources/a.pdf")
        storage.open("sources/a.pdf").shouldBeNull()
        shouldThrow<IllegalArgumentException> { storage.locate("../escape.pdf") }
    }
}
