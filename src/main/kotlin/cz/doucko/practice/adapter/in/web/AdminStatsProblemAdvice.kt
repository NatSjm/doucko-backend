package cz.doucko.practice.adapter.`in`.web

import cz.doucko.practice.application.InvalidPracticeInput
import cz.doucko.practice.application.PracticeConflict
import cz.doucko.practice.application.PracticeNotFound
import cz.doucko.practice.domain.FieldError
import cz.doucko.shared.adapter.`in`.web.ProblemResponses
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.ConstraintViolationException
import org.springframework.context.MessageSource
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

/**
 * Problem responses of the admin statistics operations (contract `Problem`; design decisions 7–12). Titles,
 * details and field messages come from the catalog (NFR-9) and never quote a submitted value (NFR-3): 400
 * names `weeks`, `status` or `reportId` in `errors`, 404 for an unknown report, 409 for a report that is not
 * `OPEN` or has no attempt.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = [AdminStatsController::class])
class AdminStatsProblemAdvice(
    private val problems: ProblemResponses,
    private val messages: MessageSource,
) {
    @ExceptionHandler(PracticeNotFound::class)
    fun notFound(request: HttpServletRequest) = respond(request, HttpStatus.NOT_FOUND, "report-not-found")

    @ExceptionHandler(PracticeConflict::class)
    fun conflict(
        exception: PracticeConflict,
        request: HttpServletRequest,
    ) = respond(request, HttpStatus.CONFLICT, "report-conflict") {
        detail = messages.getMessage("practice.conflict.${exception.code}", null, request.locale)
    }

    @ExceptionHandler(InvalidPracticeInput::class)
    fun invalid(
        exception: InvalidPracticeInput,
        request: HttpServletRequest,
    ) = invalid(request, exception.errors)

    /** A report id that is not a UUID (design decision 11). */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun typeMismatch(
        exception: MethodArgumentTypeMismatchException,
        request: HttpServletRequest,
    ) = invalid(request, listOf(FieldError(exception.name, "not-uuid")))

    /** The generated `@Pattern` on `weeks` / `status` (design decision 12): the parameter is the last path node. */
    @ExceptionHandler(ConstraintViolationException::class)
    fun constraintViolation(
        exception: ConstraintViolationException,
        request: HttpServletRequest,
    ) = invalid(
        request,
        exception.constraintViolations
            .map { it.propertyPath.lastOrNull()?.name ?: "parameter" }
            .distinct()
            .map { FieldError(it, if (it in PARAMETERS) it else "parameter") },
    )

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

    private companion object {
        val PARAMETERS = setOf("weeks", "status")
    }
}
