package cz.doucko.shared.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.turbo.TurboFilter
import ch.qos.logback.core.spi.FilterReply
import org.slf4j.Marker

/**
 * NFR-3 at every log level: the loggers that print request or bind content when set below INFO —
 * Spring MVC's "Read … to …" and handler-argument dumps, Spring Security's request line with its
 * query string, Tomcat's raw request buffer, Hibernate/JDBC and Spring JDBC bound parameter values, Hibernate Validator's trace of a validated request DTO — are capped at
 * INFO whatever `logging.level.*` says, and the Tomcat parser loggers that print a malformed request
 * line at INFO are capped at WARN. Installed by `logback-spring.xml`.
 */
class RequestContentLogGuard : TurboFilter() {
    override fun decide(
        marker: Marker?,
        logger: Logger,
        level: Level?,
        format: String?,
        params: Array<out Any?>?,
        t: Throwable?,
    ): FilterReply =
        if (level != null && !level.isGreaterOrEqual(Level.INFO) && guarded(logger.name.removePrefix("_"))) {
            FilterReply.DENY
        } else if (level != null && !level.isGreaterOrEqual(Level.WARN) && RAW_REQUEST_AT_INFO.any { logger.name.startsWith(it) }) {
            FilterReply.DENY
        } else {
            FilterReply.NEUTRAL
        }

    private companion object {
        /**
         * Tomcat's HTTP/1.1 parser logs a malformed request line, target and query included, at INFO
         * ("Error parsing HTTP request header … Invalid character found in the request target [ … ]").
         * Those loggers are capped at WARN.
         */
        val RAW_REQUEST_AT_INFO =
            listOf("org.apache.coyote.http11", "org.apache.coyote.AbstractProtocol", "org.apache.tomcat.util.http.parser")

        /** Spring names some internal loggers "_org.springframework…" (hidden logs); the prefix is ignored. */
        fun guarded(name: String) = GUARDED.any { name == it || name.startsWith("$it.") }

        val GUARDED =
            listOf(
                "org.springframework.web",
                "org.springframework.http",
                "org.springframework.security",
                // MockMvc's TestDispatcherServlet stands in for the guarded DispatcherServlet in tests.
                "org.springframework.test.web",
                "org.apache.coyote",
                "org.apache.catalina",
                "org.apache.tomcat",
                // Hibernate ORM: bound and extracted JDBC values (orm.jdbc.bind / extract at TRACE) and entity dumps
                // with every column, the display name included (orm.core at DEBUG).
                "org.hibernate.orm",
                // JdbcTemplate / JdbcClient print bound parameter values (raw answers, report notes) at TRACE.
                "org.springframework.jdbc",
                "org.hibernate.type",
                // Hibernate Validator traces each validated object (a bound request DTO) with its values.
                "org.hibernate.validator",
                "org.postgresql",
            )
    }
}
