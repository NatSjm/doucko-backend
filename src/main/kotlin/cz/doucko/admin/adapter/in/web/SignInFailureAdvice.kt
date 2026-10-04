package cz.doucko.admin.adapter.`in`.web

import cz.doucko.shared.adapter.`in`.web.ProblemResponses
import jakarta.servlet.http.HttpServletRequest
import org.springframework.context.MessageSource
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.ServletWebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

/**
 * Sign-in never answers 400 or 415 (contract `signIn`, store spec admin-access): a wrong password and
 * a missing, malformed, non-JSON or oversized body all get the same 401 `Invalid credentials` problem,
 * whose body differs only by `instance`. An unreadable body or unsupported media type on any other
 * path keeps Spring's standard problem response.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class SignInFailureAdvice(
    private val problems: ProblemResponses,
    messages: MessageSource,
) {
    private val standard = object : ResponseEntityExceptionHandler() {}.apply { setMessageSource(messages) }

    @ExceptionHandler(InvalidCredentialsException::class)
    fun invalidCredentials(request: HttpServletRequest): ResponseEntity<Any> =
        ResponseEntity
            .status(HttpStatus.UNAUTHORIZED)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(problems.problem(HttpStatus.UNAUTHORIZED, request.requestURI, request.locale, "invalid-credentials"))

    @ExceptionHandler(HttpMessageNotReadableException::class, HttpMediaTypeNotSupportedException::class)
    fun unreadable(
        exception: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<Any>? =
        if (request.method == HttpMethod.POST.name() && request.requestURI == SIGN_IN) {
            invalidCredentials(request)
        } else {
            standard.handleException(exception, ServletWebRequest(request))
        }

    private companion object {
        const val SIGN_IN = "/api/admin/session"
    }
}
