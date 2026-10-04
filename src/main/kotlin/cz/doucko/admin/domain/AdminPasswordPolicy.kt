package cz.doucko.admin.domain

/**
 * The `ADMIN_PASSWORD` limits (store spec admin-access, NFR-16, decisions OQ-23 and OQ-30): at least
 * 12 characters counted as Unicode code points of the value exactly as given, and at most 72 bytes in
 * UTF-8 because BCrypt reads no further. Messages name the variable and the limit, never the value.
 */
object AdminPasswordPolicy {
    const val MIN_CHARACTERS = 12
    const val MAX_BYTES = 72

    enum class Violation { MISSING, TOO_SHORT, TOO_LONG }

    fun violation(value: String?): Violation? =
        when {
            value.isNullOrEmpty() -> Violation.MISSING
            value.codePointCount(0, value.length) < MIN_CHARACTERS -> Violation.TOO_SHORT
            value.toByteArray(Charsets.UTF_8).size > MAX_BYTES -> Violation.TOO_LONG
            else -> null
        }

    fun message(violation: Violation): String =
        when (violation) {
            Violation.MISSING -> "ADMIN_PASSWORD is not set. Set it in .env (see .env.example) and restart."
            Violation.TOO_SHORT -> "ADMIN_PASSWORD is too short: the $MIN_CHARACTERS-character minimum applies."
            Violation.TOO_LONG -> "ADMIN_PASSWORD is too long: the $MAX_BYTES-byte maximum (UTF-8) applies."
        }
}
