package cz.doucko.profile.domain

/** One top-level field of a settings request body as JSON gave it, before any rule is applied. */
sealed interface JsonField {
    /** The key is not in the body. */
    data object Absent : JsonField

    /** The key is present with JSON `null`. */
    data object Null : JsonField

    /** A JSON string. */
    data class Text(
        val value: String,
    ) : JsonField {
        /** A submitted value is request content (NFR-3): never printed. */
        override fun toString() = "Text(<${value.length} chars>)"
    }

    /** A number, boolean, object or array. */
    data object WrongType : JsonField
}

/** A settings request body reduced to the two fields the contract knows; unknown fields are dropped. */
data class SettingsInput(
    val secondaryTaskLocale: JsonField = JsonField.Absent,
    val answerMode: JsonField = JsonField.Absent,
)

/** What a valid request does to one setting: leave it, or set it (a null locale clears it). */
sealed interface Change<out T> {
    data object Keep : Change<Nothing>

    data class To<T>(
        val value: T,
    ) : Change<T>
}

/** A validated partial update (store spec child-profile, partial-update semantics). */
data class SettingsPatch(
    val secondaryTaskLocale: Change<String?> = Change.Keep,
    val answerMode: Change<AnswerMode> = Change.Keep,
) {
    fun applyTo(settings: ProfileSettings): ProfileSettings =
        ProfileSettings(
            secondaryTaskLocale =
                when (val change = secondaryTaskLocale) {
                    is Change.To -> change.value
                    Change.Keep -> settings.secondaryTaskLocale
                },
            answerMode =
                when (val change = answerMode) {
                    is Change.To -> change.value
                    Change.Keep -> settings.answerMode
                },
        )
}

/** Why a field was refused; the message comes from the catalog and never quotes the value. */
enum class FieldProblem { WRONG_TYPE, NULL_NOT_ALLOWED, UNKNOWN_VALUE, UNSUPPORTED_LOCALE }

data class FieldError(
    val field: String,
    val problem: FieldProblem,
)

sealed interface Validation {
    data class Valid(
        val patch: SettingsPatch,
    ) : Validation

    data class Invalid(
        val errors: List<FieldError>,
    ) : Validation
}

/**
 * The settings validation rules (store spec child-profile, "Invalid settings are refused without side
 * effects"): every field is checked and every invalid one is reported. Locales match one of
 * [supportedLocales] exactly. `GUIDED` is a valid value here; its availability is checked afterwards.
 */
class SettingsValidator(
    private val supportedLocales: Set<String>,
) {
    fun validate(input: SettingsInput): Validation {
        val errors = mutableListOf<FieldError>()
        val locale =
            when (val field = input.secondaryTaskLocale) {
                JsonField.Absent -> {
                    Change.Keep
                }

                JsonField.Null -> {
                    Change.To(null)
                }

                is JsonField.Text -> {
                    if (field.value in supportedLocales) Change.To(field.value) else errors.refuse(LOCALE, FieldProblem.UNSUPPORTED_LOCALE)
                }

                JsonField.WrongType -> {
                    errors.refuse(LOCALE, FieldProblem.WRONG_TYPE)
                }
            }
        val mode =
            when (val field = input.answerMode) {
                JsonField.Absent -> {
                    Change.Keep
                }

                JsonField.Null -> {
                    errors.refuse(MODE, FieldProblem.NULL_NOT_ALLOWED)
                }

                is JsonField.Text -> {
                    AnswerMode.entries.firstOrNull { it.name == field.value }?.let { Change.To(it) }
                        ?: errors.refuse(MODE, FieldProblem.UNKNOWN_VALUE)
                }

                JsonField.WrongType -> {
                    errors.refuse(MODE, FieldProblem.WRONG_TYPE)
                }
            }
        return if (errors.isEmpty()) Validation.Valid(SettingsPatch(locale, mode)) else Validation.Invalid(errors)
    }

    private fun MutableList<FieldError>.refuse(
        field: String,
        problem: FieldProblem,
    ): Change<Nothing> {
        add(FieldError(field, problem))
        return Change.Keep
    }

    companion object {
        const val LOCALE = "secondaryTaskLocale"
        const val MODE = "answerMode"
    }
}
