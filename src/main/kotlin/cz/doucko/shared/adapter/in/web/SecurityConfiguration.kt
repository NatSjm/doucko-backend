package cz.doucko.shared.adapter.`in`.web

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.web.SecurityFilterChain

/**
 * Baseline filter chain (platform-foundation). The child API has no login and no session, so it
 * needs no CSRF token (contract `info`). The admin area (every path under `/api/admin/`) stays
 * closed until the admin-access slice replaces that rule with the session + CSRF chain (NFR-1, NFR-15).
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            authorizeHttpRequests {
                authorize("/api/admin/**", denyAll)
                authorize(anyRequest, permitAll)
            }
            csrf { ignoringRequestMatchers("/api/child/**") }
        }
        return http.build()
    }
}
