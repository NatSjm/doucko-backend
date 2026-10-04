package cz.doucko.checking

import java.text.Normalizer

/** The four mechanical format-tip rules of the store spec answer-checking ("Format tip for unreadable input"). */
object TipRules {
    private val FORBIDDEN =
        listOf(
            "chyba",
            "chyby",
            "chybně",
            "chybná",
            "chybný",
            "chybné",
            "špatně",
            "špatná",
            "špatný",
            "špatné",
            "nesprávně",
            "nesprávná",
            "nesprávný",
            "nesprávné",
            "hloupě",
            "hloupý",
            "blbě",
        ).map(::fold).toSet()

    private const val CZECH_LETTERS = "áčďéěíňóřšťúůýžÁČĎÉĚÍŇÓŘŠŤÚŮÝŽ"

    private fun fold(s: String): String =
        Normalizer
            .normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()

    private val NUMERIC = setOf(AnswerType.INTEGER, AnswerType.DECIMAL, AnswerType.FRACTION, AnswerType.NUMBER_WITH_UNIT)

    /** Returns the broken rules (empty when the tip is fine). */
    fun violations(
        spec: AnswerSpec,
        tip: String?,
    ): List<String> {
        if (tip == null) return listOf("no tip")
        val broken = mutableListOf<String>()
        if (tip.length > 120 || tip.none { it in CZECH_LETTERS }) broken += "rule 1 (Czech, at most 120 characters): $tip"
        val words = fold(tip).split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
        if (words.any { it in FORBIDDEN }) broken += "rule 2 (forbidden word): $tip"
        when (spec.type) {
            in NUMERIC -> {
                val at = tip.lastIndexOf("třeba ")
                if (at < 0 || !tip.endsWith(".")) {
                    broken += "rule 3 (ends with 'třeba <example>.'): $tip"
                } else {
                    val example = tip.substring(at + "třeba ".length, tip.length - 1)
                    val verdict = AnswerChecker.check(spec, example).verdict
                    if (verdict != Verdict.WRONG) broken += "rule 3 (example '$example' is $verdict, not WRONG): $tip"
                }
            }

            AnswerType.TEXT -> {
                if (!tip.contains("Napiš")) broken += "rule 4 (Napiš): $tip"
            }

            else -> {
                if (!tip.contains("Vyber")) broken += "rule 4 (Vyber): $tip"
            }
        }
        return broken
    }
}
