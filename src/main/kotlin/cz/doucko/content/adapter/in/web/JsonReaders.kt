package cz.doucko.content.adapter.`in`.web

import cz.doucko.checking.AnswerType
import cz.doucko.content.application.AnswerInput
import cz.doucko.content.application.InvalidContent
import cz.doucko.content.domain.FieldError
import cz.doucko.content.domain.In
import cz.doucko.content.domain.Limits
import cz.doucko.content.domain.MissionDraft
import cz.doucko.content.domain.Modality
import cz.doucko.content.domain.OptionDraft
import cz.doucko.content.domain.PartDraft
import cz.doucko.content.domain.PublishStatus
import cz.doucko.content.domain.SourceDraft
import cz.doucko.content.domain.SourceRefDraft
import cz.doucko.content.domain.SpecDraft
import cz.doucko.content.domain.TaskDraft
import cz.doucko.content.domain.Text
import cz.doucko.content.domain.TranslationDraft
import tools.jackson.databind.JsonNode
import java.util.UUID

/**
 * JSON trees of the content bodies → domain drafts. Absent and `null` read as [In.Missing], a value of
 * the wrong JSON type as [In.Invalid] (a float or an out-of-int32 number is never an integer, a UUID
 * must be in canonical form). The domain rules decide the messages.
 */
internal object JsonReaders {
    private val UUID_FORM = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    fun task(node: JsonNode) =
        TaskDraft(
            prompt = node.get("prompt").text(),
            topicId = node.get("topicId").uuid(),
            grade = node.get("grade").int(),
            difficulty = node.get("difficulty").int(),
            modality = node.get("modality").enum { name -> Modality.entries.firstOrNull { it.name == name } },
            sourceRef =
                node.get("sourceRef").obj {
                    SourceRefDraft(it.get("sourceId").uuid(), it.get("page").int(), it.get("taskNo").text(), it.get("subPart").text())
                },
            parts = node.get("parts").list { it.obj(::part) },
            hints = node.get("hints").list { it.text() },
            translations = node.get("translations").entries { it.obj(::translation) },
        )

    private fun part(node: JsonNode) = PartDraft(node.get("key").text(), node.get("label").text(), node.get("spec").obj(::spec))

    private fun spec(node: JsonNode) =
        SpecDraft(
            type = node.get("type").enum { name -> AnswerType.entries.firstOrNull { it.name == name } },
            accepted = node.get("accepted").list { it.text() },
            tolerance = node.get("tolerance").text(),
            exactDecimals = node.get("exactDecimals").int(),
            allowEquivalent = node.get("allowEquivalent").bool(),
            requireLowestTerms = node.get("requireLowestTerms").bool(),
            unitRequired = node.get("unitRequired").bool(),
            diacriticsStrict = node.get("diacriticsStrict").bool(),
            options = node.get("options").list { option -> option.obj { OptionDraft(it.get("key").text(), it.get("text").text()) } },
        )

    private fun translation(node: JsonNode) =
        TranslationDraft(
            prompt = node.get("prompt").text(),
            partLabels = node.get("partLabels").entries { it.text() },
            hints = node.get("hints").list { it.text() },
            options = node.get("options").entries { it.text() },
        )

    /** `createMission` / `updateMission` body (contract `MissionInput`); the mission rules decide the messages. */
    fun mission(node: JsonNode) =
        MissionDraft(
            topicId = node.get("topicId").uuid(),
            position = node.get("position").int(),
            taskIds = node.get("taskIds").list { it.uuid() },
        )

    /** `updateSource` body: closed (`additionalProperties: false`), so `file` or any unknown field is 400. */
    fun sourceUpdate(node: JsonNode): SourceDraft {
        val unknown = node.propertyNames().filter { it !in SOURCE_FIELDS }
        if (unknown.isNotEmpty()) throw InvalidContent(unknown.map { FieldError(it, "unknown-field") })
        return SourceDraft(
            node.get("title").text(),
            node.get("publisher").text(),
            node.get("edition").text(),
            node.get("grade").int(),
            node.get("subject").text(),
        )
    }

    /** `testAnswers` body (contract `AttemptRequest`): per part a string, or an array of at most 20 option keys. */
    fun answers(node: JsonNode): Map<String, AnswerInput> {
        val answers = node.get("answers")
        if (answers == null || !answers.isObject) throw InvalidContent(listOf(FieldError("answers", "wrong-type")))
        val errors = mutableListOf<FieldError>()
        val result = linkedMapOf<String, AnswerInput>()
        for (key in answers.propertyNames()) {
            val value = answers.get(key)
            val field = "answers.$key"
            when {
                value.isString -> {
                    result[key] = AnswerInput.Text(value.asString())
                }

                value.isArray -> {
                    val items = value.values().toList()
                    when {
                        items.size > MAX_SELECTION -> {
                            errors += FieldError(field, "answer-too-many", listOf(MAX_SELECTION))
                        }

                        items.any { !it.isString } -> {
                            errors += FieldError(field, "answer-shape")
                        }

                        items.any { Text.codePoints(it.asString()) > Limits.TEXT_ANSWER } -> {
                            errors += FieldError(field, "too-long", listOf(Limits.TEXT_ANSWER))
                        }

                        else -> {
                            result[key] = AnswerInput.Keys(items.map { it.asString() })
                        }
                    }
                }

                else -> {
                    errors += FieldError(field, "answer-shape")
                }
            }
        }
        if (errors.isNotEmpty()) throw InvalidContent(errors)
        return result
    }

    /** `bulkPublishTasks` body: 1 to 100 distinct task ids. */
    fun taskIds(node: JsonNode): List<UUID> {
        val ids = uuids(node, "taskIds")
        if (ids.isEmpty() || ids.size > Limits.BULK_PUBLISH) {
            throw InvalidContent(listOf(FieldError("taskIds", "bulk-count", listOf(Limits.BULK_PUBLISH))))
        }
        return ids
    }

    /** `reorderTaskImages` body. */
    fun imageIds(node: JsonNode): List<UUID> = uuids(node, "imageIds")

    /** `setTaskStatus` body. */
    fun status(node: JsonNode): PublishStatus =
        node.get("status").enum { name -> PublishStatus.entries.firstOrNull { it.name == name } }.orNull()
            ?: throw InvalidContent(listOf(FieldError("status", "status")))

    private fun uuids(
        node: JsonNode,
        name: String,
    ): List<UUID> {
        val array = node.get(name)
        if (array == null || !array.isArray) throw InvalidContent(listOf(FieldError(name, "required")))
        val items = array.values().toList().map { it.uuid() }
        val errors = mutableListOf<FieldError>()
        items.forEachIndexed { index, item ->
            when {
                item !is In.Ok -> errors += FieldError("$name[$index]", "not-uuid")
                items.indexOf(item) != index -> errors += FieldError("$name[$index]", "duplicate")
            }
        }
        if (errors.isNotEmpty()) throw InvalidContent(errors)
        return items.map { (it as In.Ok).value }
    }

    private fun JsonNode?.absent() = this == null || isNull || isMissingNode

    fun JsonNode?.text(): In<String> =
        when {
            absent() -> In.Missing
            this!!.isString -> In.Ok(asString())
            else -> In.Invalid
        }

    private fun JsonNode?.int(): In<Int> =
        when {
            absent() -> In.Missing
            this!!.isInt -> In.Ok(intValue())
            else -> In.Invalid
        }

    private fun JsonNode?.bool(): In<Boolean> =
        when {
            absent() -> In.Missing
            this!!.isBoolean -> In.Ok(booleanValue())
            else -> In.Invalid
        }

    private fun JsonNode?.uuid(): In<UUID> =
        when (val text = text()) {
            is In.Ok -> if (UUID_FORM.matches(text.value)) In.Ok(UUID.fromString(text.value)) else In.Invalid
            is In.Missing -> In.Missing
            is In.Invalid -> In.Invalid
        }

    private fun <T> JsonNode?.enum(parse: (String) -> T?): In<T> =
        when (val text = text()) {
            is In.Ok -> parse(text.value)?.let { In.Ok(it) } ?: In.Invalid
            is In.Missing -> In.Missing
            is In.Invalid -> In.Invalid
        }

    private fun <T> JsonNode?.obj(read: (JsonNode) -> T): In<T> =
        when {
            absent() -> In.Missing
            this!!.isObject -> In.Ok(read(this))
            else -> In.Invalid
        }

    private fun <T> JsonNode?.list(item: (JsonNode) -> In<T>): In<List<In<T>>> =
        when {
            absent() -> In.Missing
            this!!.isArray -> In.Ok(values().map(item))
            else -> In.Invalid
        }

    private fun <T> JsonNode?.entries(item: (JsonNode) -> In<T>): In<Map<String, In<T>>> =
        when {
            absent() -> In.Missing
            this!!.isObject -> In.Ok(propertyNames().associateWith { item(get(it)) })
            else -> In.Invalid
        }

    private const val MAX_SELECTION = 20
    private val SOURCE_FIELDS = setOf("title", "publisher", "edition", "grade", "subject")
}
