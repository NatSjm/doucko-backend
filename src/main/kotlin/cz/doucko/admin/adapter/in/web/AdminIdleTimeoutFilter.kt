package cz.doucko.admin.adapter.`in`.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Admin session idle timeout on the backend's clock (store spec admin-access, NFR-15). Runs before the
 * security context is loaded. A session idle for longer than [idle] is invalidated, so the request
 * meets no session (401) and no later request can revive it. Any other request that carries the
 * session resets the clock, including one that CSRF then refuses with 403. A request without a
 * session touches nothing.
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
            val now = clock.instant()
            val last = session.getAttribute(LAST_ACTIVITY) as? Instant
            if (last != null && Duration.between(last, now) > idle) {
                session.invalidate()
            } else {
                session.setAttribute(LAST_ACTIVITY, now)
            }
        }
        chain.doFilter(request, response)
    }

    companion object {
        val IDLE: Duration = Duration.ofHours(8)

        /** Session attribute holding the [Instant] of the last request that carried the session. */
        const val LAST_ACTIVITY = "cz.doucko.admin.lastActivity"
    }
}
