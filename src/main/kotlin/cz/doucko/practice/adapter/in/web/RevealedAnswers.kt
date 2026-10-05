package cz.doucko.practice.adapter.`in`.web

import cz.doucko.api.model.FeedbackRevealedAnswersInnerAnswer
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
 * module writes the held string or array. Responses stay generated models (NFR-12).
 */
@Component
class RevealedAnswers : SimpleModule("practice-revealed-answers") {
    init {
        addSerializer(FeedbackRevealedAnswersInnerAnswer::class.java, Writer)
    }

    private object Writer : ValueSerializer<FeedbackRevealedAnswersInnerAnswer>() {
        override fun serialize(
            value: FeedbackRevealedAnswersInnerAnswer,
            gen: JsonGenerator,
            ctxt: SerializationContext,
        ) {
            when (val raw = held(value)) {
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

        fun held(answer: FeedbackRevealedAnswersInnerAnswer): RawAnswer = checkNotNull(holderOf(answer)).value

        private fun holderOf(candidate: Any): Holder? =
            if (Proxy.isProxyClass(candidate.javaClass)) Proxy.getInvocationHandler(candidate) as? Holder else null
    }
}
