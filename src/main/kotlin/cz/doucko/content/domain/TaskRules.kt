package cz.doucko.content.domain

import java.util.UUID

/**
 * content-authoring task rules: "Create a task with a source reference" (FR-3, CA-D16), "Answer
 * modality" (FR-5), "Answer parts" (FR-6, CA-D11, CA-D13, CA-D14), "Hints" (FR-7), "Translations"
 * (FR-8) and "Field limits" (CA-D15). Every invalid field is reported, with its contract path; nothing
 * is valid unless all of it is.
 */
object TaskRules {
    private val PART_KEY = Regex("[a-z0-9]{1,20}")

    fun validate(
        draft: TaskDraft,
        context: TaskContext,
    ): Validation<TaskContent> {
        val errors = mutableListOf<FieldError>()
        val prompt = Fields.required("prompt", draft.prompt, Limits.PROMPT, errors)
        val source = context.source
        val grade = grade(draft.grade, context, errors)
        val topicId = topic(draft.topicId, context, grade, errors)
        val difficulty = draft.difficulty.orNull()?.takeIf { it in 1..Limits.DIFFICULTY_MAX }
        if (difficulty == null) errors += FieldError("difficulty", "difficulty")
        val modality = draft.modality.orNull()
        if (modality == null) errors += FieldError("modality", "modality")
        val sourceRef = sourceRef(draft.sourceRef, source, errors)
        val parts = parts(draft.parts, modality, errors)
        val hints = hints(draft.hints, errors)
        val translations = translations(draft.translations, context, parts, hints, errors)
        if (errors.isNotEmpty() || prompt == null || grade == null || topicId == null || difficulty == null || modality == null ||
            sourceRef == null || parts == null || hints == null || translations == null
        ) {
            return Validation.Invalid(errors)
        }
        return Validation.Valid(TaskContent(prompt, topicId, grade, difficulty, modality, sourceRef, parts, hints, translations))
    }

    /** CA-D19: the search query trimmed; blank means no text filter; over 200 code points is refused. */
    fun query(raw: String?): Validation<String?> {
        val text = raw?.let(Text::trim)?.ifEmpty { null } ?: return Validation.Valid(null)
        if (Text.codePoints(text) > Limits.QUERY) return Validation.Invalid(listOf(FieldError("q", "too-long", listOf(Limits.QUERY))))
        return Validation.Valid(text)
    }

    private fun grade(
        value: In<Int>,
        context: TaskContext,
        errors: MutableList<FieldError>,
    ): Int? {
        val grade = value.orNull()?.takeIf { it in context.grades }
        val source = context.source
        return when {
            grade == null -> {
                null.also { errors += FieldError("grade", "grade-allowed", listOf(context.grades.joinToString(", "))) }
            }

            source != null && source.metadata.grade != grade -> {
                null.also { errors += FieldError("grade", "grade-source", listOf(source.metadata.grade)) }
            }

            else -> {
                grade
            }
        }
    }

    private fun topic(
        value: In<UUID>,
        context: TaskContext,
        grade: Int?,
        errors: MutableList<FieldError>,
    ): UUID? {
        val topic = context.topic?.takeIf { value is In.Ok && value.value == it.id }
        val expectedGrade = grade ?: context.source?.metadata?.grade
        return when {
            value is In.Missing -> {
                null.also { errors += FieldError("topicId", "required") }
            }

            value is In.Invalid -> {
                null.also { errors += FieldError("topicId", "not-uuid") }
            }

            topic == null -> {
                null.also { errors += FieldError("topicId", "topic-unknown") }
            }

            expectedGrade != null && topic.grade != expectedGrade -> {
                null.also { errors += FieldError("topicId", "topic-grade", listOf(expectedGrade)) }
            }

            else -> {
                topic.id
            }
        }
    }

    private fun sourceRef(
        value: In<SourceRefDraft>,
        source: Source?,
        errors: MutableList<FieldError>,
    ): SourceRef? {
        val draft =
            when (value) {
                is In.Ok -> value.value
                is In.Missing -> return null.also { errors += FieldError("sourceRef", "required") }
                is In.Invalid -> return null.also { errors += FieldError("sourceRef", "wrong-type") }
            }
        val sourceId =
            when {
                draft.sourceId is In.Missing -> {
                    null.also { errors += FieldError("sourceRef.sourceId", "required") }
                }

                draft.sourceId is In.Invalid -> {
                    null.also { errors += FieldError("sourceRef.sourceId", "not-uuid") }
                }

                source == null || draft.sourceId.orNull() != source.id -> {
                    null.also { errors += FieldError("sourceRef.sourceId", "source-unknown") }
                }

                else -> {
                    source.id
                }
            }
        val page = draft.page.orNull()
        val pageOk =
            when {
                source != null -> page != null && page in 1..source.pageCount
                else -> page != null && page >= 1
            }
        if (!pageOk) {
            errors +=
                if (source != null) {
                    FieldError("sourceRef.page", "page-range", listOf("1–${source.pageCount}"))
                } else {
                    FieldError("sourceRef.page", "page-whole")
                }
        }
        val taskNo = Fields.required("sourceRef.taskNo", draft.taskNo, Limits.TASK_NO, errors)
        val subPart = Fields.optional("sourceRef.subPart", draft.subPart, Limits.SUB_PART, errors)
        if (sourceId == null || !pageOk || taskNo == null) return null
        return SourceRef(sourceId, page!!, taskNo, subPart)
    }

    private fun parts(
        value: In<List<In<PartDraft>>>,
        modality: Modality?,
        errors: MutableList<FieldError>,
    ): List<Part>? {
        val items =
            when (value) {
                is In.Ok -> value.value
                is In.Missing -> return null.also { errors += FieldError("parts", "required") }
                is In.Invalid -> return null.also { errors += FieldError("parts", "wrong-type") }
            }
        if (items.isEmpty() && modality == Modality.TEXT) errors += FieldError("parts", "parts-text-min")
        if (items.size > Limits.PARTS) errors += FieldError("parts", "too-many", listOf(Limits.PARTS))
        val keys = mutableSetOf<String>()
        val labels = mutableSetOf<String>()
        val parts =
            items.mapIndexed { i, item ->
                val path = "parts[$i]"
                val part = (item as? In.Ok)?.value ?: return@mapIndexed null.also { errors += FieldError(path, "wrong-type") }
                val key = part.key.orNull()
                val keyOk =
                    when {
                        key == null || !PART_KEY.matches(key) -> false.also { errors += FieldError("$path.key", "part-key") }
                        !keys.add(key) -> false.also { errors += FieldError("$path.key", "duplicate") }
                        else -> true
                    }
                val label = Fields.required("$path.label", part.label, Limits.LABEL, errors)
                if (label != null && !labels.add(label.lowercase())) errors += FieldError("$path.label", "duplicate")
                val spec =
                    when (val s = part.spec) {
                        is In.Ok -> {
                            when (val result = SpecRules.validate("$path.spec", s.value)) {
                                is Validation.Valid -> result.value
                                is Validation.Invalid -> null.also { errors += result.errors }
                            }
                        }

                        is In.Missing -> {
                            null.also { errors += FieldError("$path.spec", "required") }
                        }

                        is In.Invalid -> {
                            null.also { errors += FieldError("$path.spec", "wrong-type") }
                        }
                    }
                if (keyOk && label != null && spec != null) Part(key!!, label, spec) else null
            }
        return if (parts.any { it == null }) null else parts.filterNotNull()
    }

    private fun hints(
        value: In<List<In<String>>>,
        errors: MutableList<FieldError>,
    ): List<String>? {
        val items =
            when (value) {
                is In.Ok -> value.value
                is In.Missing -> return null.also { errors += FieldError("hints", "required") }
                is In.Invalid -> return null.also { errors += FieldError("hints", "wrong-type") }
            }
        if (items.size > Limits.HINTS) errors += FieldError("hints", "too-many", listOf(Limits.HINTS))
        val hints = items.mapIndexed { i, item -> Fields.required("hints[$i]", item, Limits.HINT, errors) }
        return if (hints.any { it == null }) null else hints.filterNotNull()
    }

    private fun translations(
        value: In<Map<String, In<TranslationDraft>>>,
        context: TaskContext,
        parts: List<Part>?,
        hints: List<String>?,
        errors: MutableList<FieldError>,
    ): Map<String, Translation>? {
        val items =
            when (value) {
                is In.Ok -> value.value
                is In.Missing -> return emptyMap()
                is In.Invalid -> return null.also { errors += FieldError("translations", "wrong-type") }
            }
        val before = errors.size
        val result = linkedMapOf<String, Translation>()
        for ((locale, item) in items) {
            val path = "translations.$locale"
            if (locale !in context.locales) {
                errors += FieldError(path, "locale", listOf(context.locales.joinToString(", ").ifEmpty { "none" }))
                continue
            }
            val draft = (item as? In.Ok)?.value
            if (draft == null) {
                if (item is In.Invalid) errors += FieldError(path, "wrong-type")
                continue
            }
            val translation = translation(path, draft, parts, hints, errors)
            if (translation != null && !translation.isEmpty()) result[locale] = translation
        }
        return if (errors.size == before) result else null
    }

    private fun translation(
        path: String,
        draft: TranslationDraft,
        parts: List<Part>?,
        hints: List<String>?,
        errors: MutableList<FieldError>,
    ): Translation? {
        val prompt = Fields.optional("$path.prompt", draft.prompt, Limits.PROMPT, errors)
        val partKeys = parts?.map { it.key }?.toSet()
        val labels =
            texts("$path.partLabels", draft.partLabels, Limits.LABEL, errors) { key ->
                partKeys == null || key in partKeys
            }
        val optionKeys = parts?.flatMap { part -> part.spec.options.map { "${part.key}.${it.key}" } }?.toSet()
        val options =
            texts("$path.options", draft.options, Limits.OPTION_TEXT, errors) { key ->
                optionKeys == null || key in optionKeys
            }
        val hintItems =
            when (val h = draft.hints) {
                is In.Ok -> h.value
                is In.Missing -> emptyList()
                is In.Invalid -> null.also { errors += FieldError("$path.hints", "wrong-type") }
            }
        val translatedHints =
            hintItems?.let { list ->
                if (hints != null && list.size > hints.size) {
                    errors += FieldError("$path.hints", "translation-hints", listOf(hints.size))
                }
                list.mapIndexed { i, item -> Fields.optional("$path.hints[$i]", item, Limits.HINT, errors) }.dropLastWhile { it == null }
            }
        return Translation(prompt, labels, translatedHints.orEmpty(), options)
    }

    private fun texts(
        path: String,
        value: In<Map<String, In<String>>>,
        limit: Int,
        errors: MutableList<FieldError>,
        known: (String) -> Boolean,
    ): Map<String, String> {
        val items =
            when (value) {
                is In.Ok -> value.value
                is In.Missing -> return emptyMap()
                is In.Invalid -> return emptyMap<String, String>().also { errors += FieldError(path, "wrong-type") }
            }
        val result = linkedMapOf<String, String>()
        for ((key, item) in items) {
            if (!known(key)) {
                errors += FieldError("$path.$key", "translation-key")
                continue
            }
            Fields.optional("$path.$key", item, limit, errors)?.let { result[key] = it }
        }
        return result
    }
}
