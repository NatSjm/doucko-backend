package cz.doucko.profile.application

import cz.doucko.profile.domain.DisplayNamePolicy
import org.springframework.beans.factory.InitializingBean
import org.springframework.core.env.ConfigurableEnvironment

/**
 * Creates the single profile on the first start (store spec child-profile, decision OQ-22). It runs
 * while the context refreshes, before the web server takes requests. The variable is read only when no
 * profile exists; a refused value fails the startup (the process exits non-zero) and creates nothing.
 * Nothing here logs the name (NFR-3).
 */
class ChildProfileInitializer(
    private val store: ChildProfileStore,
    private val environment: ConfigurableEnvironment,
) : InitializingBean {
    override fun afterPropertiesSet() {
        if (store.exists()) return
        when (val outcome = DisplayNamePolicy.check(rawDisplayName())) {
            is DisplayNamePolicy.Outcome.Refused -> throw DisplayNameRefusedException(outcome.violation)
            is DisplayNamePolicy.Outcome.Accepted -> store.create(outcome.displayName)
        }
    }

    /**
     * The variable exactly as given, from the first property source that has it. `getProperty` would
     * resolve placeholders inside the value, which could replace the name or put another property's
     * value into the startup error. Boot's attached `configurationProperties` view is skipped.
     */
    private fun rawDisplayName(): String? =
        environment.propertySources
            .filter { it.name != ATTACHED }
            .firstNotNullOfOrNull { it.getProperty(DisplayNamePolicy.VARIABLE) }
            ?.toString()

    private companion object {
        const val ATTACHED = "configurationProperties"
    }
}
