package cz.doucko.practice.adapter.`in`.web

import cz.doucko.practice.application.InvalidPracticeInput
import cz.doucko.practice.application.PracticeConflict
import cz.doucko.practice.application.PracticeNotFound
import cz.doucko.practice.domain.FieldError
import cz.doucko.shared.adapter.`in`.web.ProblemResponses
import jakarta.servlet.http.HttpServletRequest
import org.springframework.context.MessageSource
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

/**
 * Problem responses of the child practice operations (contract `Problem`). Titles, details and field
 * messages come from the catalog (NFR-9) and never quote a submitted value (NFR-3): 400 names each invalid
 * field in `errors`, 404 for anything not served (FR-12), 409 for a state rule, 413 for a body over 16 KB.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = [ChildHqController::class, ChildPracticeController::class])
class PracticeProblemAdvice(
    private val problems: ProblemResponses,
    private val messages: MessageSource,
) {
    @ExceptionHandler(PracticeNotFound::class)
    fun notFound(request: HttpServletRequest) = respond(request, HttpStatus.NOT_FOUND, "practice-not-found")

    @ExceptionHandler(PracticeConflict::class)
    fun conflict(
        exception: PracticeConflict,
        request: HttpServletRequest,
    ) = respond(request, HttpStatus.CONFLICT, "practice-conflict") {
        detail = messages.getMessage("practice.conflict.${exception.code}", null, request.locale)
    }

    @ExceptionHandler(InvalidPracticeInput::class)
    fun invalid(
        exception: InvalidPracticeInput,
        request: HttpServletRequest,
    ) = invalid(request, exception.errors)

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun typeMismatch(
        exception: MethodArgumentTypeMismatchException,
        request: HttpServletRequest,
    ) = invalid(request, listOf(FieldError(exception.name, "not-uuid")))

    @ExceptionHandler(UnreadablePracticeBody::class, HttpMessageNotReadableException::class)
    fun unreadable(request: HttpServletRequest) = respond(request, HttpStatus.BAD_REQUEST, "practice-unreadable")

    @ExceptionHandler(PracticeBodyTooLarge::class)
    fun tooLarge(request: HttpServletRequest) = respond(request, HttpStatus.CONTENT_TOO_LARGE, "child-body-too-large")

    private fun invalid(
        request: HttpServletRequest,
        errors: List<FieldError>,
    ) = respond(request, HttpStatus.BAD_REQUEST, "practice-invalid") {
        setProperty(
            "errors",
            errors.map {
                mapOf(
                    "field" to it.field,
                    "message" to
                        messages.getMessage(
                            "practice.error.${it.code}",
                            it.args.map(Any::toString).toTypedArray(),
                            request.locale,
                        ),
                )
            },
        )
    }

    private fun respond(
        request: HttpServletRequest,
        status: HttpStatus,
        key: String,
        customize: ProblemDetail.() -> Unit = {},
    ): ResponseEntity<ProblemDetail> {
        val problem = problems.problem(status, request.requestURI, request.locale, key).apply(customize)
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem)
    }
}
