package cz.doucko.profile.adapter.`in`.web

import cz.doucko.api.model.ProfileSettingsPatch
import cz.doucko.profile.domain.JsonField
import cz.doucko.profile.domain.SettingsInput
import cz.doucko.profile.domain.SettingsValidator
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
 * Reads the body of `updateProfileSettings` presence-aware (contract `ProfileSettingsPatch`: an absent
 * field is not `null`, and `answerMode: null` is refused). The generated DTO cannot tell the two apart,
 * so this advice reads the raw body itself:
 *
 * - at most [MAX_BYTES] (the contract's 16 KB child body limit), else [SettingsBodyTooLargeException] (413);
 * - one JSON object, else [UnreadableSettingsException] (400);
 * - each known field becomes a [JsonField]; unknown fields are dropped.
 *
 * The [SettingsInput] is kept as a request attribute for the handler, and Jackson is handed `{}` so the
 * generated DTO never holds (or prints, NFR-3) a submitted value.
 */
@ControllerAdvice
class SettingsBodyAdvice(
    private val json: JsonMapper,
) : RequestBodyAdviceAdapter() {
    override fun supports(
        methodParameter: MethodParameter,
        targetType: Type,
        converterType: Class<out HttpMessageConverter<*>>,
    ): Boolean = methodParameter.parameterType == ProfileSettingsPatch::class.java

    override fun beforeBodyRead(
        inputMessage: HttpInputMessage,
        parameter: MethodParameter,
        targetType: Type,
        converterType: Class<out HttpMessageConverter<*>>,
    ): HttpInputMessage {
        if (inputMessage.headers.contentLength > MAX_BYTES) throw SettingsBodyTooLargeException()
        val bytes = inputMessage.body.readNBytes(MAX_BYTES + 1)
        if (bytes.size > MAX_BYTES) throw SettingsBodyTooLargeException()
        currentRequest().setAttribute(INPUT, parse(bytes))
        return object : HttpInputMessage {
            override fun getBody() = ByteArrayInputStream(EMPTY_OBJECT)

            override fun getHeaders(): HttpHeaders = inputMessage.headers
        }
    }

    override fun handleEmptyBody(
        body: Any?,
        inputMessage: HttpInputMessage,
        parameter: MethodParameter,
        targetType: Type,
        converterType: Class<out HttpMessageConverter<*>>,
    ): Any? = throw UnreadableSettingsException()

    private fun parse(bytes: ByteArray): SettingsInput {
        val tree =
            try {
                json.createParser(bytes).use { parser ->
                    val node = json.readTree(parser)
                    // One JSON value only: `{} {}` is not a settings object.
                    if (parser.nextToken() != null) null else node
                }
            } catch (_: JacksonException) {
                null
            }
        if (tree == null || !tree.isObject) throw UnreadableSettingsException()
        return SettingsInput(field(tree, SettingsValidator.LOCALE), field(tree, SettingsValidator.MODE))
    }

    private fun field(
        tree: JsonNode,
        name: String,
    ): JsonField {
        val node = tree.get(name) ?: return JsonField.Absent
        return when {
            node.isNull -> JsonField.Null
            node.isString -> JsonField.Text(node.asString())
            else -> JsonField.WrongType
        }
    }

    private fun currentRequest() = (RequestContextHolder.currentRequestAttributes() as ServletRequestAttributes).request

    companion object {
        /** The contract's limit for a child request body (`TooLarge`: 16 KB). */
        const val MAX_BYTES = 16 * 1024

        private const val INPUT = "cz.doucko.profile.settingsInput"
        private val EMPTY_OBJECT = "{}".toByteArray()

        fun input(request: HttpServletRequest): SettingsInput = checkNotNull(request.getAttribute(INPUT) as? SettingsInput)
    }
}

/** The settings body is not one JSON object (400). */
class UnreadableSettingsException : RuntimeException(null, null, false, false)

/** The settings body is over 16 KB (413). */
class SettingsBodyTooLargeException : RuntimeException(null, null, false, false)
