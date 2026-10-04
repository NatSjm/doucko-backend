package cz.doucko.content.domain

/**
 * FR-1 metadata rules: title required (at most 200 code points), publisher (200) and edition (50)
 * optional (OQ-31), grade and subject among the configured values (CA-D16). Texts are trimmed; an
 * optional text that is blank after trimming is absent.
 */
object SourceRules {
    fun validate(
        draft: SourceDraft,
        grades: List<Int>,
        subjects: List<String>,
    ): Validation<SourceMetadata> {
        val errors = mutableListOf<FieldError>()
        val title = Fields.required("title", draft.title, Limits.TITLE, errors)
        val publisher = Fields.optional("publisher", draft.publisher, Limits.PUBLISHER, errors)
        val edition = Fields.optional("edition", draft.edition, Limits.EDITION, errors)
        val grade = draft.grade.orNull()?.takeIf { it in grades }
        if (grade == null) errors += FieldError("grade", "grade-allowed", listOf(grades.joinToString(", ")))
        val subject =
            draft.subject
                .orNull()
                ?.let(Text::trim)
                ?.takeIf { it in subjects }
        if (subject == null) errors += FieldError("subject", "subject-allowed", listOf(subjects.joinToString(", ")))
        if (errors.isNotEmpty() || title == null || grade == null || subject == null) return Validation.Invalid(errors)
        return Validation.Valid(SourceMetadata(title, publisher, edition, grade, subject))
    }
}

/** Shared text-field rules (content-authoring "Field limits"). */
internal object Fields {
    /** A required text: trimmed, not empty, at most [limit] code points. */
    fun required(
        field: String,
        value: In<String>,
        limit: Int,
        errors: MutableList<FieldError>,
    ): String? =
        when (value) {
            is In.Invalid -> {
                null.also { errors += FieldError(field, "wrong-type") }
            }

            is In.Missing -> {
                null.also { errors += FieldError(field, "required") }
            }

            is In.Ok -> {
                val text = Text.trim(value.value)
                when {
                    text.isEmpty() -> null.also { errors += FieldError(field, "required") }
                    Text.codePoints(text) > limit -> null.also { errors += FieldError(field, "too-long", listOf(limit)) }
                    else -> text
                }
            }
        }

    /** An optional text: trimmed; blank is absent; at most [limit] code points. */
    fun optional(
        field: String,
        value: In<String>,
        limit: Int,
        errors: MutableList<FieldError>,
    ): String? =
        when (value) {
            is In.Invalid -> {
                null.also { errors += FieldError(field, "wrong-type") }
            }

            is In.Missing -> {
                null
            }

            is In.Ok -> {
                val text = Text.trim(value.value)
                when {
                    text.isEmpty() -> null
                    Text.codePoints(text) > limit -> null.also { errors += FieldError(field, "too-long", listOf(limit)) }
                    else -> text
                }
            }
        }
}
