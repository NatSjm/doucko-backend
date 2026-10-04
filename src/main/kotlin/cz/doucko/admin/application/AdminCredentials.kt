package cz.doucko.admin.application

import cz.doucko.admin.domain.AdminPasswordPolicy
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Component

/**
 * The single admin's password (store spec admin-access, NFR-1, NFR-16). Built at startup from
 * `ADMIN_PASSWORD`. A value outside the policy fails the context before any request is served, and
 * only a BCrypt hash is kept. [matches] compares the full value: an input over 72 bytes never signs
 * in, and it is refused before BCrypt so an oversized body costs no hashing.
 */
@Component
class AdminCredentials(
    @Value("\${ADMIN_PASSWORD:}") password: String,
) {
    private val encoder = BCryptPasswordEncoder()

    val hash: String =
        AdminPasswordPolicy.violation(password)?.let { throw AdminPasswordRefusedException(AdminPasswordPolicy.message(it)) }
            ?: requireNotNull(encoder.encode(password))

    fun matches(submitted: String?): Boolean =
        !submitted.isNullOrEmpty() &&
            submitted.length <= AdminPasswordPolicy.MAX_BYTES &&
            submitted.toByteArray(Charsets.UTF_8).size <= AdminPasswordPolicy.MAX_BYTES &&
            encoder.matches(submitted, hash)

    override fun toString() = "AdminCredentials(bcrypt)"
}

/** Startup refusal; the message names `ADMIN_PASSWORD` and the violated limit, never the value. */
class AdminPasswordRefusedException(
    message: String,
) : IllegalStateException(message)
