package cz.doucko.practice.adapter.`in`.web

import cz.doucko.practice.application.InvalidPracticeInput
import cz.doucko.practice.domain.FieldError
import cz.doucko.practice.domain.JsonText
import cz.doucko.practice.domain.RawAnswer
import cz.doucko.practice.domain.ReportInput
import cz.doucko.practice.domain.ReportRules
import tools.jackson.databind.JsonNode

/**
 * JSON trees of the child bodies → practice input, read after the 404 check of the mission and task:
 * `answers` must be an object of strings and arrays of strings, an array holds at most 20 elements of at
 * most 100 code points (contract `AttemptRequest`). An over-long string is not refused: the checker grades
 * it UNPARSEABLE. Only `answers` is read; every other top-level field is ignored (NFR-1).
 */
internal object PracticeReaders {
    const val MAX_SELECTION = 20
    const val MAX_ELEMENT = 100

    fun answers(node: JsonNode): Map<String, RawAnswer> {
        val answers = node.get("answers")
        if (answers == null || !answers.isObject) throw InvalidPracticeInput(listOf(FieldError("answers", "answers-object")))
        val errors = mutableListOf<FieldError>()
        val result = linkedMapOf<String, RawAnswer>()
        for (key in answers.propertyNames()) {
            val value = answers.get(key)
            val field = "answers.$key"
            when {
                value.isString -> {
                    result[key] = RawAnswer.Text(value.asString())
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

                        items.any { it.asString().let { s -> s.codePointCount(0, s.length) } > MAX_ELEMENT } -> {
                            errors += FieldError(field, "answer-element-too-long", listOf(MAX_ELEMENT))
                        }

                        else -> {
                            result[key] = RawAnswer.Keys(items.map { it.asString() })
                        }
                    }
                }

                else -> {
                    errors += FieldError(field, "answer-shape")
                }
            }
        }
        if (errors.isNotEmpty()) throw InvalidPracticeInput(errors)
        return result
    }

    /** `reportProblem` body (contract `ReportRequest`). */
    fun report(node: JsonNode): ReportInput {
        val (input, errors) = ReportRules.validate(text(node.get("reason")), text(node.get("note")))
        if (errors.isNotEmpty()) throw InvalidPracticeInput(errors)
        return input!!
    }

    /** `ackRankUp` body: `rankIndex`, an int32 of at least 0. */
    fun rankIndex(node: JsonNode): Int {
        val index = node.get("rankIndex")
        if (index == null || !index.isInt || index.intValue() < 0) throw InvalidPracticeInput(listOf(FieldError("rankIndex", "rank-index")))
        return index.intValue()
    }

    private fun text(node: JsonNode?): JsonText =
        when {
            node == null || node.isNull -> JsonText.Missing
            node.isString -> JsonText.Value(node.asString())
            else -> JsonText.WrongType
        }
}
