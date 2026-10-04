package cz.doucko.profile.domain

/**
 * The first-start display-name rules (store spec child-profile, "Display name is validated on first
 * start"): trimmed of surrounding whitespace, then 1 to [MAX_CODE_POINTS] Unicode code points, no
 * control character (category Cc) and no U+FFFD, which the JVM puts in place of bytes that are not
 * valid UTF-8. Messages name the variable and the rule, never the value (NFR-3).
 */
object DisplayNamePolicy {
    const val VARIABLE = "DOUCKO_CHILD_DISPLAY_NAME"
    const val MAX_CODE_POINTS = 50

    enum class Violation { MISSING, TOO_LONG, CONTROL_CHARACTER, NOT_UTF8 }

    sealed interface Outcome {
        data class Accepted(
            val displayName: String,
        ) : Outcome {
            /** Never print the name (NFR-3). */
            override fun toString() = "Accepted(<display name>)"
        }

        data class Refused(
            val violation: Violation,
        ) : Outcome
    }

    fun check(raw: String?): Outcome {
        val name = raw?.trim().orEmpty()
        val violation =
            when {
                name.isEmpty() -> Violation.MISSING
                name.contains(REPLACEMENT) -> Violation.NOT_UTF8
                name.codePoints().anyMatch { Character.getType(it) == Character.CONTROL.toInt() } -> Violation.CONTROL_CHARACTER
                name.codePointCount(0, name.length) > MAX_CODE_POINTS -> Violation.TOO_LONG
                else -> return Outcome.Accepted(name)
            }
        return Outcome.Refused(violation)
    }

    fun message(violation: Violation): String =
        when (violation) {
            Violation.MISSING -> "$VARIABLE is not set or blank. Set it in .env (see .env.example) before the first start."
            Violation.TOO_LONG -> "$VARIABLE is too long: the $MAX_CODE_POINTS-character maximum applies."
            Violation.CONTROL_CHARACTER -> "$VARIABLE contains a control character (such as a newline or tab), which is not allowed."
            Violation.NOT_UTF8 -> "$VARIABLE is not valid UTF-8 text."
        }

    /** U+FFFD, which the JVM decodes bytes that are not valid UTF-8 into. */
    private val REPLACEMENT = Char(0xFFFD)
}
