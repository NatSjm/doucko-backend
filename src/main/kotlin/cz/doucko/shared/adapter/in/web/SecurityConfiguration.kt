package cz.doucko.shared.adapter.`in`.web

import org.springframework.context.MessageSource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.security.web.csrf.CsrfFilter
import org.springframework.security.web.firewall.HttpFirewall
import org.springframework.security.web.firewall.RequestRejectedHandler
import org.springframework.security.web.firewall.StrictHttpFirewall
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher
import org.springframework.security.web.util.matcher.AndRequestMatcher
import tools.jackson.databind.json.JsonMapper

/**
 * Baseline filter chain (platform-foundation). Nothing outside the admin area has a login or a
 * session, so only `/api/admin/` needs CSRF protection (contract `info`); everything else reaches
 * MVC, where an undeclared path is 404 problem+json. The admin area stays closed until the
 * admin-access slice replaces that rule with the session + CSRF chain (NFR-1, NFR-15). Every
 * refusal is problem+json titled from the message catalog (NFR-9), never Boot's error page: 403 from
 * the chain, 400 for a URL the firewall rejects.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {
    @Bean
    fun problemResponses(
        messages: MessageSource,
        json: JsonMapper,
    ) = ProblemResponses(messages, json)

    /** No users until admin-access: Boot would otherwise mint one and log its password at startup (NFR-3). */
    @Bean
    fun userDetailsService(): UserDetailsService = InMemoryUserDetailsManager()

    /**
     * DNS rebinding guard (NFR-2): a website that points its own domain at this laptop gets 400, so a
     * page in the child's browser cannot script the API. Only `localhost`, a bare IPv4 address (loopback
     * or the LAN address) and the Compose service name `backend` are answered. The same rule as the
     * frontend's nginx, kept here for anything that reaches the backend directly (dev runs, a
     * misconfigured publish). A refused host goes to [requestRejectedHandler].
     */
    @Bean
    fun httpFirewall(): HttpFirewall =
        StrictHttpFirewall().apply {
            setAllowedHostnames { host -> host == "localhost" || host == "backend" || IPV4.matches(host) }
        }

    @Bean
    fun requestRejectedHandler(problems: ProblemResponses) =
        RequestRejectedHandler { request, response, _ -> problems.write(request, response, HttpStatus.BAD_REQUEST) }

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        problems: ProblemResponses,
    ): SecurityFilterChain {
        http {
            authorizeHttpRequests {
                authorize("/api/admin/**", denyAll)
                authorize(anyRequest, permitAll)
            }
            csrf {
                requireCsrfProtectionMatcher =
                    AndRequestMatcher(CsrfFilter.DEFAULT_CSRF_MATCHER, PathPatternRequestMatcher.withDefaults().matcher("/api/admin/**"))
            }
            exceptionHandling {
                authenticationEntryPoint =
                    AuthenticationEntryPoint { request, response, _ -> problems.write(request, response, HttpStatus.FORBIDDEN) }
                accessDeniedHandler =
                    AccessDeniedHandler { request, response, _ -> problems.write(request, response, HttpStatus.FORBIDDEN) }
            }
        }
        return http.build()
    }
}

/** A dotted-quad IPv4 host name (no port: the firewall checks the server name). */
private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
