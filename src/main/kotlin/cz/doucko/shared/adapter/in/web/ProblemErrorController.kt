package cz.doucko.shared.adapter.`in`.web

import jakarta.servlet.RequestDispatcher
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.webmvc.error.ErrorController
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Replaces Boot's BasicErrorController: an error the servlet container forwards to `/error` is
 * answered as problem+json from the catalog whatever the Accept header, and a direct request to
 * `/error` (a path the contract does not declare) is a 404 like any other (NFR-9).
 */
@RestController
class ProblemErrorController(
    private val problems: ProblemResponses,
) : ErrorController {
    @RequestMapping("\${server.error.path:/error}")
    fun error(request: HttpServletRequest): ResponseEntity<ProblemDetail> {
        val code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) as? Int
        val status = code?.let(HttpStatus::resolve) ?: HttpStatus.NOT_FOUND
        val uri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI) as? String ?: request.requestURI
        return ResponseEntity
            .status(status)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(problems.problem(status, uri, request.locale))
    }
}
