package cz.doucko.admin.adapter.`in`.web

import cz.doucko.admin.application.AdminCredentials
import cz.doucko.api.AdminSessionApi
import cz.doucko.api.model.SignInRequest
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.time.Clock

/**
 * `getAdminSession`, `signIn` and `signOut` from the contract (store spec admin-access). The admin
 * chain has already checked the session (and, for sign-out, the CSRF token) before these run.
 */
@RestController
class AdminSessionController(
    private val credentials: AdminCredentials,
    private val clock: Clock,
    private val contextRepository: HttpSessionSecurityContextRepository,
    private val csrfTokens: CookieCsrfTokenRepository,
) : AdminSessionApi {
    override fun getAdminSession(): ResponseEntity<Unit> = ResponseEntity.noContent().build()

    /**
     * A wrong, missing or oversized password throws [InvalidCredentialsException], which
     * [SignInFailureAdvice] answers. On success any earlier session is replaced (no fixation), the
     * new one is marked active now, and a fresh CSRF token is issued.
     */
    override fun signIn(signInRequest: SignInRequest?): ResponseEntity<Unit> {
        if (!credentials.matches(signInRequest?.password)) throw InvalidCredentialsException()
        val (request, response) = current()
        request.getSession(false)?.invalidate()
        request.getSession(true).setAttribute(AdminIdleTimeoutFilter.LAST_ACTIVITY, clock.instant())
        val context =
            SecurityContextHolder.createEmptyContext().apply {
                authentication =
                    UsernamePasswordAuthenticationToken.authenticated(ADMIN, null, listOf(SimpleGrantedAuthority("ROLE_ADMIN")))
            }
        SecurityContextHolder.setContext(context)
        contextRepository.saveContext(context, request, response)
        csrfTokens.saveToken(csrfTokens.generateToken(request), request, response)
        return ResponseEntity.noContent().build()
    }

    override fun signOut(): ResponseEntity<Unit> {
        val (request, response) = current()
        request.getSession(false)?.invalidate()
        SecurityContextHolder.clearContext()
        csrfTokens.saveToken(null, request, response)
        return ResponseEntity.noContent().build()
    }

    private fun current() =
        (RequestContextHolder.currentRequestAttributes() as ServletRequestAttributes).let {
            it.request to requireNotNull(it.response)
        }

    private companion object {
        /** The one admin has no user name (store spec): this is only the principal's label. */
        const val ADMIN = "admin"
    }
}

/** Any failed sign-in; answered as the single `Invalid credentials` problem. */
class InvalidCredentialsException : RuntimeException(null, null, false, false)
