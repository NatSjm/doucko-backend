package cz.doucko.checking

/** Answer types of the contract's `AnswerType` (store spec answer-checking). */
enum class AnswerType { INTEGER, DECIMAL, FRACTION, NUMBER_WITH_UNIT, CHOICE_SINGLE, CHOICE_MULTI, TEXT }

/** The contract's `Verdict`. */
enum class Verdict { CORRECT, WRONG, UNPARSEABLE }

/**
 * The checker's view of one answer part's spec, with the field names and defaults of the contract's
 * `AnswerSpec`. `options` holds the option keys in display order (CHOICE_* only). The unit of a
 * NUMBER_WITH_UNIT part is derived from `accepted`, as the server does.
 */
data class AnswerSpec(
    val type: AnswerType,
    val accepted: List<String>,
    val tolerance: String? = null,
    val exactDecimals: Int? = null,
    val allowEquivalent: Boolean = true,
    val requireLowestTerms: Boolean = false,
    val unitRequired: Boolean = true,
    val diacriticsStrict: Boolean = false,
    val options: List<String> = emptyList(),
)

/** Raw input of one part: a string for every type except CHOICE_*, which send a selection of option keys. */
sealed interface RawInput {
    data class Text(
        val value: String,
    ) : RawInput

    data class Selection(
        val keys: List<String>,
    ) : RawInput
}

/** Per-part result, as in the contract's `PartResult`: `normalized` is null and `formatTip` set exactly when UNPARSEABLE. */
data class CheckResult(
    val verdict: Verdict,
    val normalized: String?,
    val formatTip: String?,
)
