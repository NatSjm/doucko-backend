package cz.doucko.content.adapter.`in`.web

import cz.doucko.content.application.ContentConflict
import cz.doucko.content.application.ContentNotFound
import cz.doucko.content.application.ContentProperties
import cz.doucko.content.application.InvalidContent
import cz.doucko.content.application.UnsupportedUpload
import cz.doucko.content.application.UploadKind
import cz.doucko.content.application.UploadTooLarge
import cz.doucko.content.domain.FieldError
import cz.doucko.shared.adapter.`in`.web.ProblemResponses
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.ConstraintViolationException
import org.springframework.context.MessageSource
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.multipart.support.MissingServletRequestPartException

/**
 * Problem responses of the content operations (contract `Problem`, info "Conventions"). Titles, details
 * and field messages come from the catalog (NFR-9) and never quote a submitted value (NFR-3). Ordered
 * first, so Spring's generic problem handler never answers these operations without `errors`.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = [SourceController::class, TaskController::class, ReferenceController::class])
class ContentProblemAdvice(
    private val problems: ContentProblems,
) {
    @ExceptionHandler(InvalidContent::class)
    fun invalid(
        exception: InvalidContent,
        request: HttpServletRequest,
    ) = problems.invalid(request, exception.errors)

    @ExceptionHandler(ContentNotFound::class)
    fun notFound(request: HttpServletRequest) = problems.respond(request, HttpStatus.NOT_FOUND, "content-not-found")

    @ExceptionHandler(ContentConflict::class)
    fun conflict(
        exception: ContentConflict,
        request: HttpServletRequest,
    ) = problems.respond(request, HttpStatus.CONFLICT, "content-conflict") {
        detail = problems.text(request, "content.conflict.${exception.code}", exception.args)
        setProperty("conflicts", exception.conflicts)
    }

    /** A concurrent edit broke a database rule between the check and the write (e.g. a task saved for a source being deleted). */
    @ExceptionHandler(DataIntegrityViolationException::class)
    fun integrity(request: HttpServletRequest) = problems.respond(request, HttpStatus.CONFLICT, "content-conflict")

    @ExceptionHandler(UnsupportedUpload::class)
    fun unsupported(
        exception: UnsupportedUpload,
        request: HttpServletRequest,
    ) = problems.upload(request, HttpStatus.UNSUPPORTED_MEDIA_TYPE, exception.kind)

    @ExceptionHandler(UploadTooLarge::class)
    fun tooLarge(
        exception: UploadTooLarge,
        request: HttpServletRequest,
    ) = problems.upload(request, HttpStatus.CONTENT_TOO_LARGE, exception.kind)

    @ExceptionHandler(ContentBodyTooLarge::class)
    fun bodyTooLarge(
        exception: ContentBodyTooLarge,
        request: HttpServletRequest,
    ) = problems.respond(request, HttpStatus.CONTENT_TOO_LARGE, "content-body-too-large") {
        detail = problems.text(request, "problem.detail.content-body-too-large", listOf(exception.limit / 1024))
    }

    @ExceptionHandler(UnreadableContentBody::class, HttpMessageNotReadableException::class)
    fun unreadable(request: HttpServletRequest) = problems.respond(request, HttpStatus.BAD_REQUEST, "content-unreadable")

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun typeMismatch(
        exception: MethodArgumentTypeMismatchException,
        request: HttpServletRequest,
    ) = problems.invalid(request, listOf(problems.parameterError(exception.name)))

    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun missingParameter(
        exception: MissingServletRequestParameterException,
        request: HttpServletRequest,
    ) = problems.invalid(request, listOf(FieldError(exception.parameterName, "required")))

    @ExceptionHandler(MissingServletRequestPartException::class)
    fun missingPart(
        exception: MissingServletRequestPartException,
        request: HttpServletRequest,
    ) = problems.invalid(request, listOf(FieldError(exception.requestPartName, "file-required")))

    /** Method validation of the generated interface (`@Min` on `page`, `@Pattern` on `locale`): the parameter is the last path node. */
    @ExceptionHandler(ConstraintViolationException::class)
    fun constraintViolation(
        exception: ConstraintViolationException,
        request: HttpServletRequest,
    ) = problems.invalid(
        request,
        exception.constraintViolations
            .map { it.propertyPath.lastOrNull()?.name ?: "parameter" }
            .distinct()
            .map(problems::parameterError),
    )
}

/** Builds the content problem responses (shared by [ContentProblemAdvice] and [UploadLimitAdvice]). */
@org.springframework.stereotype.Component
class ContentProblems(
    private val problems: ProblemResponses,
    private val messages: MessageSource,
    private val properties: ContentProperties,
) {
    fun invalid(
        request: HttpServletRequest,
        errors: List<FieldError>,
    ) = respond(request, HttpStatus.BAD_REQUEST, "content-invalid") {
        // Arguments as plain text: a limit reads "4000", never a locale-grouped "4,000".
        setProperty(
            "errors",
            errors.map { mapOf("field" to it.field, "message" to text(request, "content.error.${it.code}", it.args.map(Any::toString))) },
        )
    }

    fun upload(
        request: HttpServletRequest,
        status: HttpStatus,
        kind: UploadKind,
    ): ResponseEntity<ProblemDetail> {
        val key = if (kind == UploadKind.PDF) "upload-pdf" else "upload-image"
        return respond(request, status, "$key-${status.value()}") {
            setProperty("errors", listOf(mapOf("field" to "file", "message" to detail)))
        }
    }

    /** A multipart body over the container limit: a source PDF, or an image when the path is an image path. */
    fun uploadLimit(request: HttpServletRequest) =
        upload(
            request,
            HttpStatus.CONTENT_TOO_LARGE,
            if (request.requestURI.contains("/images")) UploadKind.IMAGE else UploadKind.PDF,
        )

    /** A query, path or multipart parameter that is missing, malformed or out of range. */
    fun parameterError(name: String): FieldError =
        when (name) {
            "taskId", "sourceId", "imageId", "topicId" -> FieldError(name, "not-uuid")
            "grade" -> FieldError(name, "grade-allowed", listOf(properties.grades.joinToString(", ")))
            "page" -> FieldError(name, "page-whole")
            "status" -> FieldError(name, "status")
            "modality" -> FieldError(name, "modality")
            "locale" -> FieldError(name, "locale", listOf(properties.taskLocales.joinToString(", ").ifEmpty { "none" }))
            else -> FieldError(name, "invalid-value")
        }

    fun respond(
        request: HttpServletRequest,
        status: HttpStatus,
        key: String,
        customize: ProblemDetail.() -> Unit = {},
    ): ResponseEntity<ProblemDetail> {
        val problem = problems.problem(status, request.requestURI, request.locale, key).apply(customize)
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem)
    }

    fun text(
        request: HttpServletRequest,
        code: String,
        args: List<Any>,
    ): String = messages.getMessage(code, args.toTypedArray(), request.locale)
}
