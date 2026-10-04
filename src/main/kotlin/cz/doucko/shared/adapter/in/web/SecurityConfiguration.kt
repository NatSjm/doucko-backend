package cz.doucko.shared.adapter.`in`.web

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.MessageSource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.security.web.csrf.CsrfFilter
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher
import org.springframework.security.web.util.matcher.AndRequestMatcher
import tools.jackson.databind.json.JsonMapper
import java.net.URI

/**
 * Baseline filter chain (platform-foundation). Nothing outside the admin area has a login or a
 * session, so only `/api/admin/` needs CSRF protection (contract `info`); everything else reaches
 * MVC, where an undeclared path is 404 problem+json. The admin area stays closed until the
 * admin-access slice replaces that rule with the session + CSRF chain (NFR-1, NFR-15). Every
 * refusal is a 403 problem+json titled from the message catalog (NFR-9), never Boot's error page.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {
    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        messages: MessageSource,
        json: JsonMapper,
    ): SecurityFilterChain {
        val refuse = { request: HttpServletRequest, response: HttpServletResponse -> writeForbidden(request, response, messages, json) }
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
                authenticationEntryPoint = AuthenticationEntryPoint { request, response, _ -> refuse(request, response) }
                accessDeniedHandler = AccessDeniedHandler { request, response, _ -> refuse(request, response) }
            }
        }
        return http.build()
    }

    private fun writeForbidden(
        request: HttpServletRequest,
        response: HttpServletResponse,
        messages: MessageSource,
        json: JsonMapper,
    ) {
        val key = AccessDeniedException::class.java.name
        val problem =
            ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN,
                messages.getMessage("problemDetail.$key", null, request.locale),
            )
        problem.title = messages.getMessage("problemDetail.title.$key", null, request.locale)
        problem.instance = URI.create(request.requestURI)
        response.status = HttpStatus.FORBIDDEN.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        json.writeValue(response.outputStream, problem)
    }
}
