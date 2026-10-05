package cz.doucko.practice.adapter.`in`.web

import cz.doucko.api.model.AckRankUpRequest
import cz.doucko.api.model.AttemptRequest
import cz.doucko.api.model.ReportRequest
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.MethodParameter
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpInputMessage
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayInputStream
import java.lang.reflect.Type

/**
 * Reads the bodies of the child practice endpoints itself (design decision 5): at most [MAX_BYTES] (the
 * contract's 16 KB child body limit, else 413), one JSON object (else 400), kept as a request attribute
 * for the controller. Jackson binds a fixed placeholder, so a generated DTO never holds (or prints, NFR-3)
 * a submitted value, and unknown top-level fields can never reach anything (NFR-1).
 */
@ControllerAdvice(assignableTypes = [ChildHqController::class, ChildPracticeController::class])
class PracticeBodyAdvice(
    private val json: JsonMapper,
) : RequestBodyAdviceAdapter() {
    override fun supports(
        methodParameter: MethodParameter,
        targetType: Type,
        converterType: Class<out HttpMessageConverter<*>>,
    ): Boolean = methodParameter.parameterType in PLACEHOLDERS

    override fun beforeBodyRead(
        inputMessage: HttpInputMessage,
        parameter: MethodParameter,
        targetType: Type,
        converterType: Class<out HttpMessageConverter<*>>,
    ): HttpInputMessage {
        if (inputMessage.headers.contentLength > MAX_BYTES) throw PracticeBodyTooLarge()
        val bytes = inputMessage.body.readNBytes(MAX_BYTES + 1)
        if (bytes.size > MAX_BYTES) throw PracticeBodyTooLarge()
        currentRequest().setAttribute(BODY, parse(bytes))
        val placeholder = checkNotNull(PLACEHOLDERS[parameter.parameterType]).toByteArray()
        return object : HttpInputMessage {
            override fun getBody() = ByteArrayInputStream(placeholder)

            override fun getHeaders(): HttpHeaders = inputMessage.headers
        }
    }

    override fun handleEmptyBody(
        body: Any?,
        inputMessage: HttpInputMessage,
        parameter: MethodParameter,
        targetType: Type,
        converterType: Class<out HttpMessageConverter<*>>,
    ): Any? = throw UnreadablePracticeBody()

    private fun parse(bytes: ByteArray): JsonNode {
        val tree =
            try {
                json.createParser(bytes).use { parser ->
                    val node = json.readTree(parser)
                    if (parser.nextToken() != null) null else node
                }
            } catch (_: JacksonException) {
                null
            }
        if (tree == null || !tree.isObject()) throw UnreadablePracticeBody()
        return tree
    }

    private fun currentRequest() = (RequestContextHolder.currentRequestAttributes() as ServletRequestAttributes).request

    companion object {
        /** The contract's limit for a child request body (`TooLarge`: 16 KB). */
        const val MAX_BYTES = 16 * 1024

        private const val BODY = "cz.doucko.practice.body"

        private val PLACEHOLDERS: Map<Class<*>, String> =
            mapOf(
                AttemptRequest::class.java to """{"answers":{}}""",
                ReportRequest::class.java to """{"reason":"OTHER"}""",
                AckRankUpRequest::class.java to """{"rankIndex":0}""",
            )

        fun body(request: HttpServletRequest): JsonNode = checkNotNull(request.getAttribute(BODY) as? JsonNode)
    }
}

/** A child request body is not one JSON object (400). */
class UnreadablePracticeBody : RuntimeException(null, null, false, false)

/** A child request body is over 16 KB (413). */
class PracticeBodyTooLarge : RuntimeException(null, null, false, false)
