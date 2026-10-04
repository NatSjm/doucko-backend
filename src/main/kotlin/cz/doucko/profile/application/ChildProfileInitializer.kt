package cz.doucko.profile.application

import cz.doucko.profile.domain.DisplayNamePolicy
import org.springframework.beans.factory.InitializingBean
import org.springframework.core.env.Environment

/**
 * Creates the single profile on the first start (store spec child-profile, decision OQ-22). It runs
 * while the context refreshes, before the web server takes requests. The variable is read only when no
 * profile exists; a refused value fails the startup (the process exits non-zero) and creates nothing.
 * Nothing here logs the name (NFR-3).
 */
class ChildProfileInitializer(
    private val store: ChildProfileStore,
    private val environment: Environment,
) : InitializingBean {
    override fun afterPropertiesSet() {
        if (store.exists()) return
        when (val outcome = DisplayNamePolicy.check(environment.getProperty(DisplayNamePolicy.VARIABLE))) {
            is DisplayNamePolicy.Outcome.Refused -> throw DisplayNameRefusedException(outcome.violation)
            is DisplayNamePolicy.Outcome.Accepted -> store.create(outcome.displayName)
        }
    }
}
