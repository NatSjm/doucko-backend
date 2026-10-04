package cz.doucko.content.domain

import cz.doucko.checking.AnswerType
import java.util.UUID

/**
 * One submitted field as the web adapter read it: absent or JSON `null` ([Missing]), present with the
 * wrong JSON type or an unreadable value ([Invalid]), or a value ([Ok]). The rules choose the message,
 * so a wrong type and an out-of-range value of the same field read the same (e.g. difficulty).
 */
sealed interface In<out T> {
    data object Missing : In<Nothing>

    data object Invalid : In<Nothing>

    data class Ok<out T>(
        val value: T,
    ) : In<T>

    fun orNull(): T? = (this as? Ok)?.value
}

/** A validation outcome: the value, or every field error found. */
sealed interface Validation<out T> {
    data class Valid<out T>(
        val value: T,
    ) : Validation<T>

    data class Invalid(
        val errors: List<FieldError>,
    ) : Validation<Nothing>
}

/**
 * An invalid input: the contract field path (`parts[1].spec.accepted[0]`), a message [code] resolved
 * from the catalog as `content.error.<code>` (NFR-9), and its arguments. Never the submitted value
 * (NFR-3).
 */
data class FieldError(
    val field: String,
    val code: String,
    val args: List<Any> = emptyList(),
)

/** Submitted source metadata (multipart `uploadSource` or JSON `updateSource`). */
data class SourceDraft(
    val title: In<String>,
    val publisher: In<String>,
    val edition: In<String>,
    val grade: In<Int>,
    val subject: In<String>,
)

/** A submitted `TaskInput`. */
data class TaskDraft(
    val prompt: In<String>,
    val topicId: In<UUID>,
    val grade: In<Int>,
    val difficulty: In<Int>,
    val modality: In<Modality>,
    val sourceRef: In<SourceRefDraft>,
    val parts: In<List<In<PartDraft>>>,
    val hints: In<List<In<String>>>,
    val translations: In<Map<String, In<TranslationDraft>>>,
)

data class SourceRefDraft(
    val sourceId: In<UUID>,
    val page: In<Int>,
    val taskNo: In<String>,
    val subPart: In<String>,
)

data class PartDraft(
    val key: In<String>,
    val label: In<String>,
    val spec: In<SpecDraft>,
)

data class SpecDraft(
    val type: In<AnswerType> = In.Missing,
    val accepted: In<List<In<String>>> = In.Missing,
    val tolerance: In<String> = In.Missing,
    val exactDecimals: In<Int> = In.Missing,
    val allowEquivalent: In<Boolean> = In.Missing,
    val requireLowestTerms: In<Boolean> = In.Missing,
    val unitRequired: In<Boolean> = In.Missing,
    val diacriticsStrict: In<Boolean> = In.Missing,
    val options: In<List<In<OptionDraft>>> = In.Missing,
)

data class OptionDraft(
    val key: In<String>,
    val text: In<String>,
)

data class TranslationDraft(
    val prompt: In<String> = In.Missing,
    val partLabels: In<Map<String, In<String>>> = In.Missing,
    val hints: In<List<In<String>>> = In.Missing,
    val options: In<Map<String, In<String>>> = In.Missing,
)

/** What the task rules need to know beyond the draft: the referenced source and topic, and configuration. */
data class TaskContext(
    val source: Source?,
    val topic: Topic?,
    val grades: List<Int>,
    val locales: List<String>,
)
