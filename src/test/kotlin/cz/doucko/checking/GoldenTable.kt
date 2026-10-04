package cz.doucko.checking

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.File

/**
 * Loader for the golden table (FR-23): `src/test/resources/checking/golden-table.jsonl`, one JSON case per
 * line. An input or normalized value may be a string, an array of option keys, or
 * `{"prefix": "...", "repeat": "x", "times": n}` for long inputs.
 */
object GoldenTable {
    data class Case(
        val id: String,
        val requirement: String,
        val spec: AnswerSpec,
        val input: RawInput,
        val verdict: Verdict,
        val normalized: String?,
    ) {
        override fun toString(): String {
            val shown =
                when (input) {
                    is RawInput.Text -> display(input.value)
                    is RawInput.Selection -> input.keys.joinToString(",", "{", "}")
                }
            return "$id ${spec.type} ${spec.accepted} / \"$shown\" -> $verdict"
        }
    }

    /** The 12 verdict-producing requirements of the store spec's "Golden table" requirement. */
    val REQUIREMENTS =
        listOf(
            "Verdicts and normalized interpretation",
            "Numeric notation",
            "Input length and exact arithmetic",
            "Integer input",
            "Decimal input",
            "Fraction input",
            "Numbers with units",
            "Multiple choice",
            "Short text",
            "Accepted alternatives",
            "Numeric tolerance",
            "Format tip for unreadable input",
        )

    val FILE = File("src/test/resources/checking/golden-table.jsonl")

    val cases: List<Case> by lazy {
        val mapper = JsonMapper.builder().build()
        FILE
            .readLines(Charsets.UTF_8)
            .filter { it.isNotBlank() }
            .map { line -> toCase(mapper.readTree(line)) }
    }

    private fun toCase(node: JsonNode): Case {
        val s = node.get("spec")
        val spec =
            AnswerSpec(
                type = AnswerType.valueOf(s.get("type").asString()),
                accepted = strings(s.get("accepted")),
                tolerance = s.get("tolerance")?.asString(),
                exactDecimals = s.get("exactDecimals")?.asInt(),
                allowEquivalent = s.get("allowEquivalent")?.asBoolean() ?: true,
                requireLowestTerms = s.get("requireLowestTerms")?.asBoolean() ?: false,
                unitRequired = s.get("unitRequired")?.asBoolean() ?: true,
                diacriticsStrict = s.get("diacriticsStrict")?.asBoolean() ?: false,
                options = s.get("options")?.let(::strings) ?: emptyList(),
            )
        val inputNode = node.get("input")
        val input =
            if (inputNode.isArray) {
                RawInput.Selection(strings(inputNode))
            } else {
                RawInput.Text(text(inputNode)!!)
            }
        require(node.has("normalized")) { "case ${node.get("id")} has no normalized field" }
        return Case(
            id = node.get("id").asString(),
            requirement = node.get("req").asString(),
            spec = spec,
            input = input,
            verdict = Verdict.valueOf(node.get("verdict").asString()),
            normalized = text(node.get("normalized")),
        )
    }

    private fun strings(node: JsonNode): List<String> = (0 until node.size()).map { node.get(it).asString() }

    private fun text(node: JsonNode): String? =
        when {
            node.isNull -> {
                null
            }

            node.isObject -> {
                (node.get("prefix")?.asString() ?: "") +
                    node.get("repeat").asString().repeat(node.get("times").asInt())
            }

            else -> {
                node.asString()
            }
        }

    /** Shows invisible and long inputs readably in test names. */
    fun display(value: String): String {
        val shown =
            value
                .map { c -> if (c in ' '..'~' || c.isLetter()) c.toString() else "<U+%04X>".format(c.code) }
                .joinToString("")
        return if (shown.length > 40) shown.take(20) + "...(${value.length} chars)" else shown
    }
}
