package cz.doucko.admin

import cz.doucko.admin.adapter.`in`.web.AdminIdleTimeoutFilter
import io.kotest.matchers.shouldBe
import jakarta.servlet.http.HttpSession
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockHttpSession

/** The idle filter never turns a session invalidated by a concurrent request into a raw 500 (NFR-15). */
class AdminIdleTimeoutFilterTest {
    // @trace NFR-15
    @Test
    fun `a session invalidated concurrently is passed over, not a 500`() {
        val gone = MockHttpSession().apply { invalidate() }
        // A container hands out the session, then another request invalidates it before it is read.
        val request =
            object : MockHttpServletRequest("GET", "/api/admin/session") {
                override fun getSession(create: Boolean): HttpSession = gone
            }
        val chain = MockFilterChain()
        AdminIdleTimeoutFilter(MutableClock()).doFilter(request, MockHttpServletResponse(), chain)
        (chain.request === request) shouldBe true
    }
}
