package cz.doucko.shared.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.turbo.TurboFilter
import ch.qos.logback.core.spi.FilterReply
import org.slf4j.Marker

/**
 * NFR-3 at every log level: the loggers that print request or bind content when set below INFO —
 * Spring MVC's "Read … to …" and handler-argument dumps, Spring Security's request line with its
 * query string, Tomcat's raw request buffer, Hibernate/JDBC bound parameter values — are capped at
 * INFO whatever `logging.level.*` says. Installed by `logback-spring.xml`.
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
        } else {
            FilterReply.NEUTRAL
        }

    private companion object {
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
                "org.hibernate.orm.jdbc.bind",
                "org.hibernate.type",
                "org.postgresql",
            )
    }
}
