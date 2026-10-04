package cz.doucko.content.adapter.`in`.web

import cz.doucko.api.AdminSourcesApi
import cz.doucko.api.model.SourceMetadataUpdate
import cz.doucko.content.application.SourceService
import cz.doucko.content.application.Upload
import cz.doucko.content.domain.In
import cz.doucko.content.domain.Limits
import cz.doucko.content.domain.SourceDraft
import org.springframework.core.io.InputStreamResource
import org.springframework.core.io.Resource
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.util.UUID
import cz.doucko.api.model.Source as ApiSource
import cz.doucko.content.domain.Source as DomainSource

/**
 * The admin source operations of the contract (store spec content-authoring "Upload a PDF source",
 * "Upload limits and storage", "PDF viewer beside the editor"; FR-1, FR-2, NFR-17). Behind the admin
 * session and CSRF chain (admin-access).
 */
@RestController
class SourceController(
    private val service: SourceService,
) : AdminSourcesApi {
    override fun listSources(): ResponseEntity<List<ApiSource>> = ResponseEntity.ok(service.list().map { it.toApi() })

    override fun uploadSource(
        title: String,
        grade: Int,
        subject: String,
        file: MultipartFile,
        publisher: String?,
        edition: String?,
    ): ResponseEntity<ApiSource> {
        val draft = SourceDraft(In.Ok(title), optional(publisher), optional(edition), In.Ok(grade), In.Ok(subject))
        val upload = Upload(file.size) { file.inputStream.use { it.readNBytes(Limits.PDF_BYTES.toInt() + 1) } }
        return ResponseEntity.status(201).body(service.upload(draft, upload).toApi())
    }

    override fun getSource(sourceId: UUID): ResponseEntity<ApiSource> = ResponseEntity.ok(service.get(sourceId).toApi())

    override fun updateSource(
        sourceId: UUID,
        sourceMetadataUpdate: SourceMetadataUpdate,
    ): ResponseEntity<ApiSource> {
        val draft = JsonReaders.sourceUpdate(ContentBodyAdvice.body(Requests.current()))
        return ResponseEntity.ok(service.update(sourceId, draft).toApi())
    }

    override fun deleteSource(sourceId: UUID): ResponseEntity<Unit> {
        service.delete(sourceId)
        return ResponseEntity.noContent().build()
    }

    override fun getSourceFile(sourceId: UUID): ResponseEntity<Resource> =
        ResponseEntity
            .ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
            .body(InputStreamResource(service.file(sourceId)))

    private fun optional(value: String?): In<String> = value?.let { In.Ok(it) } ?: In.Missing

    private fun DomainSource.toApi() =
        ApiSource(
            title = metadata.title,
            grade = metadata.grade,
            subject = metadata.subject,
            sourceId = id,
            pageCount = pageCount,
            publisher = metadata.publisher,
            edition = metadata.edition,
        )
}
