package cz.doucko.admin.adapter.`in`.web

import cz.doucko.admin.application.AdminCredentials
import cz.doucko.shared.adapter.`in`.web.ProblemResponses
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.AuthenticationTrustResolverImpl
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.context.SecurityContextHolderFilter
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler
import org.springframework.security.web.savedrequest.NullRequestCache
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher
import java.time.Clock

/**
 * The admin area's own filter chain (store spec admin-access, NFR-1, NFR-15). It runs ahead of the
 * shared chain and owns every `/api/admin/` path:
 *
 * - every path except sign-in needs the session that sign-in created;
 * - every data-changing request except sign-in needs the `XSRF-TOKEN` cookie value in `X-XSRF-TOKEN`;
 * - the session is checked first: no valid session is 401 whatever the CSRF token, and 403 is only for
 *   a valid session with a missing or wrong token;
 * - [AdminIdleTimeoutFilter] ends a session idle for 8 h before anything reads it.
 *
 * Refusals are problem+json from the catalog (NFR-9). The session cookie's name, path and flags are
 * in `application.yaml` (`server.servlet.session`).
 */
@Configuration(proxyBeanMethods = false)
@Import(AdminCredentials::class)
class AdminSecurityConfiguration {
    @Bean
    fun adminClock(): Clock = Clock.systemUTC()

    @Bean
    fun adminSecurityContextRepository() = HttpSessionSecurityContextRepository()

    /**
     * Readable by the admin UI (no HttpOnly) and sent on every path, so the UI can echo it. The raw
     * value is the header value: the token never appears in HTML, so BREACH masking buys nothing.
     */
    @Bean
    fun adminCsrfTokenRepository(): CookieCsrfTokenRepository =
        CookieCsrfTokenRepository.withHttpOnlyFalse().apply {
            setCookiePath("/")
            setCookieCustomizer { it.sameSite("Strict") }
        }

    @Bean
    @Order(1)
    fun adminSecurityFilterChain(
        http: HttpSecurity,
        problems: ProblemResponses,
        adminClock: Clock,
        adminSecurityContextRepository: HttpSessionSecurityContextRepository,
        adminCsrfTokenRepository: CookieCsrfTokenRepository,
    ): SecurityFilterChain {
        val signIn = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/admin/session")
        val trust = AuthenticationTrustResolverImpl()
        http {
            securityMatcher("/api/admin/**")
            authorizeHttpRequests {
                authorize(signIn, permitAll)
                authorize(anyRequest, authenticated)
            }
            securityContext { securityContextRepository = adminSecurityContextRepository }
            csrf {
                csrfTokenRepository = adminCsrfTokenRepository
                csrfTokenRequestHandler = CsrfTokenRequestAttributeHandler()
                ignoringRequestMatchers(signIn)
            }
            requestCache { requestCache = NullRequestCache() }
            logout { disable() }
            addFilterBefore<SecurityContextHolderFilter>(AdminIdleTimeoutFilter(adminClock))
            exceptionHandling {
                authenticationEntryPoint =
                    AuthenticationEntryPoint { request, response, _ -> problems.write(request, response, HttpStatus.UNAUTHORIZED) }
                // CsrfFilter runs before authorization, so a request with neither a session nor a token
                // lands here first: without a session it is 401, never 403.
                accessDeniedHandler =
                    AccessDeniedHandler { request, response, _ ->
                        val authentication = SecurityContextHolder.getContext().authentication
                        val signedIn = authentication != null && !trust.isAnonymous(authentication) && authentication.isAuthenticated
                        problems.write(request, response, if (signedIn) HttpStatus.FORBIDDEN else HttpStatus.UNAUTHORIZED)
                    }
            }
        }
        return http.build()
    }
}
