package cz.doucko.shared.adapter.`in`.web

import org.springframework.context.MessageSource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.security.web.firewall.HttpFirewall
import org.springframework.security.web.firewall.RequestRejectedHandler
import org.springframework.security.web.firewall.StrictHttpFirewall
import tools.jackson.databind.json.JsonMapper

/**
 * Baseline filter chain (platform-foundation) for everything outside `/api/admin/`, which the admin
 * module's own chain owns (admin-access, NFR-1, NFR-15). Nothing here has a login or a session, so
 * this chain is stateless and needs no CSRF protection (contract `info`): every request reaches MVC,
 * where an undeclared path is 404 problem+json, and the child API can never see the admin session.
 * Every refusal is problem+json titled from the message catalog (NFR-9), never Boot's error page:
 * 400 for a URL the firewall rejects.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {
    @Bean
    fun problemResponses(
        messages: MessageSource,
        json: JsonMapper,
    ) = ProblemResponses(messages, json)

    /** The admin is not a user store entry (admin-access): without this Boot would mint a user and log its password (NFR-3). */
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
                authorize(anyRequest, permitAll)
            }
            csrf { disable() }
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
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
