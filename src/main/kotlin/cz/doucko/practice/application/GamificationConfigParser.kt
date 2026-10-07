package cz.doucko.practice.application

import cz.doucko.practice.domain.GamificationConfig
import cz.doucko.practice.domain.Rank
import cz.doucko.practice.domain.RankLadder
import cz.doucko.practice.domain.XpConfig
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * The gamification configuration is invalid: the backend refuses to start (gamification "XP configuration
 * is validated", "Rank ladder", "Swappable skin"; OQ-29). The message names the key and the rule, never
 * the file's content.
 */
class InvalidGamificationConfig(
    val key: String,
    rule: String,
) : IllegalStateException("Invalid gamification configuration: $key $rule")

/**
 * Parses the gamification JSON file (design decision 2). Every value must be a JSON integer in its range:
 * strings (`"10"`, `"10,5"`, `"1 500"`), decimals (`2.5`, `10.0`), booleans and null are refused, every
 * key is required and unknown keys are refused, so nothing ever falls back to a built-in value.
 */
object GamificationConfigParser {
    const val XP_MAX = 1000
    const val THRESHOLD_MAX = 1_000_000
    const val NAME_MAX = 50

    private val XP_KEYS = listOf("firstAttempt", "secondAttempt", "revealed", "hintCost", "minimum", "missionBonus")
    private val mapper: JsonMapper = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()

    fun parse(text: String): GamificationConfig {
        val root =
            try {
                mapper.readTree(text)
            } catch (_: JacksonException) {
                null
            }
        if (root == null || !root.isObject) throw InvalidGamificationConfig("(file)", "must be one JSON object")
        unknown(root, setOf("xp", "ranks"), "")
        val xpNode = root.get("xp") ?: throw InvalidGamificationConfig("xp", "is missing")
        if (!xpNode.isObject) throw InvalidGamificationConfig("xp", "must be an object")
        unknown(xpNode, XP_KEYS.toSet(), "xp.")
        val values = XP_KEYS.associateWith { key -> int(xpNode, key, "xp.$key", if (key == "minimum") 1 else 0, XP_MAX) }
        val xp =
            XpConfig(
                firstAttempt = values.getValue("firstAttempt"),
                secondAttempt = values.getValue("secondAttempt"),
                revealed = values.getValue("revealed"),
                hintCost = values.getValue("hintCost"),
                minimum = values.getValue("minimum"),
                missionBonus = values.getValue("missionBonus"),
            )
        return GamificationConfig(xp, ladder(root.get("ranks")))
    }

    private fun ladder(node: JsonNode?): RankLadder {
        if (node == null || node.isNull) throw InvalidGamificationConfig("ranks", "is missing")
        if (!node.isArray || node.isEmpty) throw InvalidGamificationConfig("ranks", "must be a non-empty array of ranks")
        val ranks = mutableListOf<Rank>()
        node.values().forEachIndexed { i, entry ->
            val key = "ranks[$i] (rank ${i + 1})"
            if (!entry.isObject) throw InvalidGamificationConfig(key, "must be an object with name and minXp")
            unknown(entry, setOf("name", "minXp"), "$key.")
            val nameNode = entry.get("name")
            val name = nameNode?.takeIf { it.isString }?.asString()?.trim()
            if (name.isNullOrEmpty() || name.codePointCount(0, name.length) > NAME_MAX) {
                throw InvalidGamificationConfig("$key.name", "must be a text of 1 to $NAME_MAX characters")
            }
            val minXp = int(entry, "minXp", "$key.minXp", 0, THRESHOLD_MAX)
            when {
                i == 0 && minXp != 0 -> throw InvalidGamificationConfig("$key.minXp", "must be 0 for the first rank")

                i > 0 && minXp <= ranks.last().minXp -> throw InvalidGamificationConfig(
                    "$key.minXp",
                    "must be higher than the previous rank's",
                )
            }
            ranks += Rank(i, name, minXp)
        }
        return RankLadder(ranks)
    }

    private fun int(
        parent: JsonNode,
        field: String,
        key: String,
        min: Int,
        max: Int,
    ): Int {
        val node = parent.get(field) ?: throw InvalidGamificationConfig(key, "is missing")
        val rule = "must be a whole number from $min to $max"
        if (!node.isIntegralNumber || !node.canConvertToInt()) throw InvalidGamificationConfig(key, rule)
        val value = node.intValue()
        if (value !in min..max) throw InvalidGamificationConfig(key, rule)
        return value
    }

    private fun unknown(
        node: JsonNode,
        allowed: Set<String>,
        prefix: String,
    ) {
        node.propertyNames().firstOrNull { it !in allowed }?.let { throw InvalidGamificationConfig("$prefix$it", "is not a known key") }
    }
}
