package cz.doucko.content.application

import cz.doucko.content.domain.FieldError
import cz.doucko.content.domain.FileKind
import cz.doucko.content.domain.Limits
import cz.doucko.content.domain.Source
import cz.doucko.content.domain.SourceDraft
import cz.doucko.content.domain.SourceRules
import cz.doucko.content.domain.Validation
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.InputStream
import java.util.UUID

/** An uploaded file as the web adapter received it. [read] returns at most [Limits.PDF_BYTES] + 1 bytes. */
class Upload(
    val size: Long,
    val read: () -> ByteArray,
)

/**
 * PDF sources (store spec content-authoring "Upload a PDF source", "Upload limits and storage", "Edit
 * existing content" for sources, "Delete only unused content" CA-D4/CA-D5; FR-1, FR-2, NFR-17).
 * Every check runs before anything is stored; a failed database write removes the stored file again.
 */
@Service
class SourceService(
    private val sources: SourceStore,
    private val storage: FileStoragePort,
    private val pdf: PdfInspector,
    private val properties: ContentProperties,
) {
    fun list(): List<Source> = sources.list()

    fun get(id: UUID): Source = sources.find(id) ?: throw ContentNotFound()

    fun file(id: UUID): InputStream = storage.open(get(id).storageKey) ?: throw ContentNotFound()

    @Transactional
    fun upload(
        draft: SourceDraft,
        file: Upload,
    ): Source {
        if (file.size > Limits.PDF_BYTES) throw UploadTooLarge(UploadKind.PDF)
        val bytes = file.read()
        if (bytes.size > Limits.PDF_BYTES) throw UploadTooLarge(UploadKind.PDF)
        if (bytes.isNotEmpty() && FileKind.detect(bytes.copyOf(minOf(bytes.size, HEAD))) != FileKind.PDF) {
            throw UnsupportedUpload(UploadKind.PDF)
        }
        val metadata = SourceRules.validate(draft, properties.grades, properties.subjects)
        val errors = (metadata as? Validation.Invalid)?.errors.orEmpty().toMutableList()
        val pages =
            when {
                bytes.isEmpty() -> null.also { errors += FieldError("file", "file-empty") }
                else -> pdf.pageCount(bytes)?.takeIf { it > 0 } ?: null.also { errors += FieldError("file", "pdf-unreadable") }
            }
        if (errors.isNotEmpty() || metadata !is Validation.Valid || pages == null) throw InvalidContent(errors)
        val id = UUID.randomUUID()
        val source = Source(id, metadata.value, pages, "sources/$id.pdf")
        storage.store(source.storageKey, bytes)
        onRollback { storage.delete(source.storageKey) }
        sources.insert(source)
        return source
    }

    @Transactional
    fun update(
        id: UUID,
        draft: SourceDraft,
    ): Source {
        val current = get(id)
        val metadata =
            when (val result = SourceRules.validate(draft, properties.grades, properties.subjects)) {
                is Validation.Invalid -> throw InvalidContent(result.errors)
                is Validation.Valid -> result.value
            }
        if (metadata.grade != current.metadata.grade) {
            val tasks = sources.referencingTasks(id)
            if (tasks.isNotEmpty()) throw ContentConflict("source-grade-locked", listOf(tasks.size), tasks)
        }
        val updated = current.copy(metadata = metadata)
        sources.update(updated)
        return updated
    }

    @Transactional
    fun delete(id: UUID) {
        val current = get(id)
        val tasks = sources.referencingTasks(id)
        if (tasks.isNotEmpty()) throw ContentConflict("source-referenced", listOf(tasks.size), tasks)
        sources.delete(id)
        afterCommit { storage.delete(current.storageKey) }
    }

    private companion object {
        const val HEAD = 16
    }
}
