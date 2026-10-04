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
 * (`problem.title.<key>` / `problem.detail.<key>`, else `problem.title` / `problem.detail`), NFR-9. The key is
 * the status code unless a caller names a more specific one (e.g. `invalid-credentials`).
 */
class ProblemResponses(
    private val messages: MessageSource,
    private val json: JsonMapper,
) {
    fun problem(
        status: HttpStatus,
        instance: String,
        locale: Locale,
        key: String = status.value().toString(),
    ): ProblemDetail {
        fun text(kind: String) =
            messages.getMessage("problem.$kind.$key", null, null, locale) ?: messages.getMessage("problem.$kind", null, locale)
        return ProblemDetail.forStatusAndDetail(status, text("detail")).apply {
            title = text("title")
            this.instance = URI.create(instance.replace(" ", "%20"))
        }
    }

    fun write(
        request: HttpServletRequest,
        response: HttpServletResponse,
        status: HttpStatus,
        key: String = status.value().toString(),
    ) {
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        json.writeValue(response.outputStream, problem(status, request.requestURI, request.locale, key))
    }
}
