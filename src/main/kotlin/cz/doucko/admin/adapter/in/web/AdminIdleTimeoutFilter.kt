package cz.doucko.admin.adapter.`in`.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpSession
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Admin session idle timeout on the backend's clock (store spec admin-access, NFR-15). Runs before the
 * security context is loaded. A session idle for longer than [idle] is invalidated, so the request
 * meets no session (401) and no later request can revive it. Any other request that carries the
 * session resets the clock, including one that CSRF then refuses with 403. Sign-in never resets it: a
 * failed sign-in is a 401, which must not count as activity, and a successful one starts a new session
 * with its own clock. A request without a session touches nothing.
 */
class AdminIdleTimeoutFilter(
    private val clock: Clock,
    private val idle: Duration = IDLE,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        request.getSession(false)?.let { session ->
            try {
                track(session, signIn = request.method == "POST" && request.requestURI == SIGN_IN)
            } catch (_: IllegalStateException) {
                // Invalidated by a concurrent request (sign-out, another expiry check): the request goes
                // on without a session and is answered 401, never a raw 500.
            }
        }
        chain.doFilter(request, response)
    }

    private fun track(
        session: HttpSession,
        signIn: Boolean,
    ) {
        val now = clock.instant()
        val last = session.getAttribute(LAST_ACTIVITY) as? Instant
        when {
            last != null && Duration.between(last, now) > idle -> session.invalidate()
            !signIn -> session.setAttribute(LAST_ACTIVITY, now)
        }
    }

    companion object {
        val IDLE: Duration = Duration.ofHours(8)

        /** Session attribute holding the [Instant] of the last request that carried the session. */
        const val LAST_ACTIVITY = "cz.doucko.admin.lastActivity"

        private const val SIGN_IN = "/api/admin/session"
    }
}
