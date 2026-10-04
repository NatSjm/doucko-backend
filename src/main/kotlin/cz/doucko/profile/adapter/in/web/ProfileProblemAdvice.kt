package cz.doucko.profile.adapter.`in`.web

import cz.doucko.profile.application.GuidedModeNotAvailableException
import cz.doucko.profile.application.InvalidSettingsException
import cz.doucko.profile.application.ProfileSettingsService
import cz.doucko.profile.domain.FieldProblem
import cz.doucko.shared.adapter.`in`.web.ProblemResponses
import jakarta.servlet.http.HttpServletRequest
import org.springframework.context.MessageSource
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Problem responses of the settings endpoints (contract `updateProfileSettings`, store spec
 * child-profile). Titles, details and field messages come from the catalog (NFR-9) and never quote a
 * submitted value (NFR-3): 400 names each invalid field in `errors` and, for a locale, lists the
 * supported ones; 422 for `GUIDED`; 413 for a body over 16 KB.
 */
@RestControllerAdvice(assignableTypes = [ProfileSettingsController::class])
class ProfileProblemAdvice(
    private val problems: ProblemResponses,
    private val messages: MessageSource,
    private val service: ProfileSettingsService,
) {
    @ExceptionHandler(InvalidSettingsException::class)
    fun invalid(
        exception: InvalidSettingsException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> {
        val locales = service.supportedLocales.joinToString(", ").ifEmpty { text("profile.locales.none", request) }
        val localeRefused = exception.errors.any { it.problem == FieldProblem.UNSUPPORTED_LOCALE }
        val problem =
            problems.problem(HttpStatus.BAD_REQUEST, request.requestURI, request.locale, "profile-settings-invalid").apply {
                if (localeRefused) detail = text("problem.detail.profile-settings-invalid-locale", request, locales)
                setProperty(
                    "errors",
                    exception.errors.map { error ->
                        mapOf("field" to error.field, "message" to text("profile.field.${error.problem.name}", request, locales))
                    },
                )
            }
        return respond(HttpStatus.BAD_REQUEST, problem)
    }

    @ExceptionHandler(UnreadableSettingsException::class)
    fun unreadable(request: HttpServletRequest) = respond(HttpStatus.BAD_REQUEST, request, "profile-settings-unreadable")

    @ExceptionHandler(GuidedModeNotAvailableException::class)
    fun guided(request: HttpServletRequest) = respond(HttpStatus.UNPROCESSABLE_CONTENT, request, "guided-not-available")

    @ExceptionHandler(SettingsBodyTooLargeException::class)
    fun tooLarge(request: HttpServletRequest) = respond(HttpStatus.CONTENT_TOO_LARGE, request, "child-body-too-large")

    private fun respond(
        status: HttpStatus,
        request: HttpServletRequest,
        key: String,
    ) = respond(status, problems.problem(status, request.requestURI, request.locale, key))

    private fun respond(
        status: HttpStatus,
        problem: ProblemDetail,
    ): ResponseEntity<ProblemDetail> = ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem)

    private fun text(
        code: String,
        request: HttpServletRequest,
        vararg args: Any,
    ): String = messages.getMessage(code, args, request.locale)
}
