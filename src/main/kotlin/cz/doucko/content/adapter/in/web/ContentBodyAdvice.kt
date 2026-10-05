package cz.doucko.content.adapter.`in`.web

import cz.doucko.api.model.AttemptRequest
import cz.doucko.api.model.BulkPublishTasksRequest
import cz.doucko.api.model.MissionInput
import cz.doucko.api.model.ReorderTaskImagesRequest
import cz.doucko.api.model.SourceMetadataUpdate
import cz.doucko.api.model.StatusChange
import cz.doucko.api.model.TaskInput
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
 * Reads the JSON bodies of the content endpoints itself (design decision 1). The generated models cannot
 * express the contract conventions: `AttemptRequestAnswersValue` is a sealed interface Jackson cannot
 * build, integers must never be coerced, text limits count code points after trimming, and every error
 * needs its dotted field path. So the raw body is read here (at most [TESTER_MAX_BYTES] for the answer
 * tester, [MAX_BYTES] otherwise, else 413), parsed into one JSON tree kept as a request attribute for
 * the controller, and Jackson is handed a fixed valid placeholder: the generated DTO never holds a
 * submitted value (NFR-3). Scoped to the content controllers: the child attempt endpoint binds the same
 * `AttemptRequest` type and has its own advice in practice.
 */
@ControllerAdvice(
    assignableTypes = [SourceController::class, TaskController::class, MissionController::class, ReferenceController::class],
)
class ContentBodyAdvice(
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
        val limit = if (parameter.parameterType == AttemptRequest::class.java) TESTER_MAX_BYTES else MAX_BYTES
        if (inputMessage.headers.contentLength > limit) throw ContentBodyTooLarge(limit)
        val bytes = inputMessage.body.readNBytes(limit + 1)
        if (bytes.size > limit) throw ContentBodyTooLarge(limit)
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
    ): Any? = throw UnreadableContentBody()

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
        if (tree == null || !tree.isObject()) throw UnreadableContentBody()
        return tree
    }

    private fun currentRequest() = (RequestContextHolder.currentRequestAttributes() as ServletRequestAttributes).request

    companion object {
        /** Bodies of the authoring editors: generous for 10 parts, 10 hints and translations. */
        const val MAX_BYTES = 1024 * 1024

        /** The contract's limit for an answer-tester body (`TooLarge`: 16 KB). */
        const val TESTER_MAX_BYTES = 16 * 1024

        private const val BODY = "cz.doucko.content.body"
        private const val ID = "\"00000000-0000-4000-8000-000000000000\""

        private val PLACEHOLDERS: Map<Class<*>, String> =
            mapOf(
                TaskInput::class.java to
                    """{"prompt":"-","topicId":$ID,"grade":1,"difficulty":1,"modality":"TEXT",""" +
                    """"sourceRef":{"sourceId":$ID,"page":1,"taskNo":"-"},"parts":[],"hints":[]}""",
                SourceMetadataUpdate::class.java to """{"title":"-","grade":1,"subject":"-"}""",
                AttemptRequest::class.java to """{"answers":{}}""",
                BulkPublishTasksRequest::class.java to """{"taskIds":[$ID]}""",
                ReorderTaskImagesRequest::class.java to """{"imageIds":[]}""",
                StatusChange::class.java to """{"status":"DRAFT"}""",
                MissionInput::class.java to """{"topicId":$ID,"position":1,"taskIds":[]}""",
            )

        fun body(request: HttpServletRequest): JsonNode = checkNotNull(request.getAttribute(BODY) as? JsonNode)
    }
}

/** A content request body is not one JSON object (400). */
class UnreadableContentBody : RuntimeException(null, null, false, false)

/** A content request body is over its limit (413). */
class ContentBodyTooLarge(
    val limit: Int,
) : RuntimeException(null, null, false, false)
