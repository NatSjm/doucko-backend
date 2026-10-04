package cz.doucko.content.domain

import cz.doucko.checking.AnswerType
import java.util.UUID

/** The contract's `Modality` (FR-5). */
enum class Modality { TEXT, GRAPHIC }

/** The contract's `PublishStatus`. */
enum class PublishStatus { DRAFT, PUBLISHED }

/** A seeded topic (store spec "Topics come from configuration"): read-only, one grade each. */
data class Topic(
    val id: UUID,
    val code: String,
    val grade: Int,
    val name: String,
)

/** Validated source metadata (FR-1): trimmed, publisher and edition optional (OQ-31). */
data class SourceMetadata(
    val title: String,
    val publisher: String?,
    val edition: String?,
    val grade: Int,
    val subject: String,
)

/** A stored PDF source. [storageKey] names its file in the file storage, never a path inside a repository. */
data class Source(
    val id: UUID,
    val metadata: SourceMetadata,
    val pageCount: Int,
    val storageKey: String,
)

/** Where a task came from (FR-3, FR-13). */
data class SourceRef(
    val sourceId: UUID,
    val page: Int,
    val taskNo: String,
    val subPart: String?,
)

/** One option of a CHOICE_* part: key `A`, `B`, … in display order. */
data class ChoiceOption(
    val key: String,
    val text: String,
)

/**
 * A validated answer spec (FR-6, contract `AnswerSpec`). [accepted] keeps the values as the admin
 * entered them (trimmed), because the checker compares notation where a part does not allow
 * equivalent forms.
 */
data class Spec(
    val type: AnswerType,
    val accepted: List<String>,
    val tolerance: String? = null,
    val exactDecimals: Int? = null,
    val allowEquivalent: Boolean = true,
    val requireLowestTerms: Boolean = false,
    val unitRequired: Boolean = true,
    val diacriticsStrict: Boolean = false,
    val options: List<ChoiceOption> = emptyList(),
) {
    /** The checker's view of this spec (answer-checking public API). */
    fun toChecker() =
        cz.doucko.checking.AnswerSpec(
            type = type,
            accepted = accepted,
            tolerance = tolerance,
            exactDecimals = exactDecimals,
            allowEquivalent = allowEquivalent,
            requireLowestTerms = requireLowestTerms,
            unitRequired = unitRequired,
            diacriticsStrict = diacriticsStrict,
            options = options.map { it.key },
        )
}

/** One answer part (FR-6): a stable machine [key], a visible [label] and its spec. */
data class Part(
    val key: String,
    val label: String,
    val spec: Spec,
)

/** A translation into one task locale (FR-8). Missing texts fall back to Czech. */
data class Translation(
    val prompt: String?,
    val partLabels: Map<String, String>,
    val hints: List<String?>,
    val options: Map<String, String>,
) {
    fun isEmpty() = prompt == null && partLabels.isEmpty() && hints.all { it == null } && options.isEmpty()
}

/** Everything the admin authors on a task (contract `TaskInput`), validated and trimmed. */
data class TaskContent(
    val prompt: String,
    val topicId: UUID,
    val grade: Int,
    val difficulty: Int,
    val modality: Modality,
    val sourceRef: SourceRef,
    val parts: List<Part>,
    val hints: List<String>,
    val translations: Map<String, Translation>,
)

/** A task image (FR-4): [position] is 1-based display order. */
data class TaskImage(
    val id: UUID,
    val position: Int,
    val mediaType: String,
    val storageKey: String,
)

/** A stored task with its status and images. */
data class Task(
    val id: UUID,
    val content: TaskContent,
    val status: PublishStatus,
    val images: List<TaskImage>,
)

/** Task list filters (FR-11); null means no filter on that field. [query] is already trimmed and folded. */
data class TaskFilter(
    val grade: Int?,
    val topicId: UUID?,
    val sourceId: UUID?,
    val page: Int?,
    val modality: Modality?,
    val status: PublishStatus?,
    val query: String?,
)
