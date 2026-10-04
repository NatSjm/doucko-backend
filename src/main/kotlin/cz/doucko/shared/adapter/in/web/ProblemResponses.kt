package cz.doucko.shared.adapter.`in`.web

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.MessageSource
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.util.Locale

/**
 * Problem responses written outside Spring MVC's exception handling: security refusals, firewall
 * rejections and container-level errors. Titles and details come from the message catalog
 * (`problem.title.<status>` / `problem.detail.<status>`, else `problem.title` / `problem.detail`), NFR-9.
 */
class ProblemResponses(
    private val messages: MessageSource,
    private val json: JsonMapper,
) {
    fun problem(
        status: HttpStatus,
        instance: String,
        locale: Locale,
    ): ProblemDetail {
        fun text(kind: String) =
            messages.getMessage("problem.$kind.${status.value()}", null, null, locale) ?: messages.getMessage("problem.$kind", null, locale)
        return ProblemDetail.forStatusAndDetail(status, text("detail")).apply {
            title = text("title")
            this.instance = URI.create(instance.replace(" ", "%20"))
        }
    }

    fun write(
        request: HttpServletRequest,
        response: HttpServletResponse,
        status: HttpStatus,
    ) {
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        json.writeValue(response.outputStream, problem(status, request.requestURI, request.locale))
    }
}
