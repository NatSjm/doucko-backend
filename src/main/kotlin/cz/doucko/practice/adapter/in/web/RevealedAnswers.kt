package cz.doucko.practice.adapter.`in`.web

import cz.doucko.api.model.FeedbackRevealedAnswersInnerAnswer
import cz.doucko.api.model.LoggedAttemptRawInputValue
import cz.doucko.practice.domain.RawAnswer
import org.springframework.stereotype.Component
import tools.jackson.core.JsonGenerator
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.ValueSerializer
import tools.jackson.databind.module.SimpleModule
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/**
 * The contract's `Feedback.revealedAnswers[].answer` is `oneOf: string | array of strings`. The generator
 * turns it into the sealed interface [FeedbackRevealedAnswersInnerAnswer] with no implementation (design
 * decision 6), so a revealed answer is a JDK proxy of that interface holding the value, and this Jackson
 * module writes the held string or array. Responses stay generated models (NFR-12). The flag queue's
 * `LoggedAttempt.rawInput` values (progress-stats, FR-41) have the same shape and are written the same way.
 */
@Component
class RevealedAnswers : SimpleModule("practice-revealed-answers") {
    init {
        addSerializer(FeedbackRevealedAnswersInnerAnswer::class.java, Writer())
        addSerializer(LoggedAttemptRawInputValue::class.java, Writer())
    }

    private class Writer<T : Any> : ValueSerializer<T>() {
        override fun serialize(
            value: T,
            gen: JsonGenerator,
            ctxt: SerializationContext,
        ) {
            when (val raw = checkNotNull(holderOf(value)).value) {
                is RawAnswer.Text -> {
                    gen.writeString(raw.value)
                }

                is RawAnswer.Keys -> {
                    gen.writeStartArray()
                    raw.keys.forEach(gen::writeString)
                    gen.writeEndArray()
                }
            }
        }
    }

    /** Holds one revealed value; equal when the values are equal, so results compare like data classes. */
    private class Holder(
        val value: RawAnswer,
    ) : InvocationHandler {
        override fun invoke(
            proxy: Any,
            method: java.lang.reflect.Method,
            args: Array<out Any?>?,
        ): Any? =
            when (method.name) {
                "equals" -> (args?.get(0)?.let(::holderOf))?.value == value
                "hashCode" -> value.hashCode()
                "toString" -> "RevealedAnswer"
                else -> null
            }
    }

    companion object {
        fun of(value: RawAnswer): FeedbackRevealedAnswersInnerAnswer =
            Proxy.newProxyInstance(
                FeedbackRevealedAnswersInnerAnswer::class.java.classLoader,
                arrayOf(FeedbackRevealedAnswersInnerAnswer::class.java),
                Holder(value),
            ) as FeedbackRevealedAnswersInnerAnswer

        /** A raw input of a logged attempt (`LoggedAttempt.rawInput` value). */
        fun logged(value: RawAnswer): LoggedAttemptRawInputValue =
            Proxy.newProxyInstance(
                LoggedAttemptRawInputValue::class.java.classLoader,
                arrayOf(LoggedAttemptRawInputValue::class.java),
                Holder(value),
            ) as LoggedAttemptRawInputValue

        fun held(answer: Any): RawAnswer = checkNotNull(holderOf(answer)).value

        private fun holderOf(candidate: Any): Holder? =
            if (Proxy.isProxyClass(candidate.javaClass)) Proxy.getInvocationHandler(candidate) as? Holder else null
    }
}
