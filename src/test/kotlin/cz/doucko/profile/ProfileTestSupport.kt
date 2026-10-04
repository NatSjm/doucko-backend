package cz.doucko.profile

import cz.doucko.profile.application.ChildProfileStore
import cz.doucko.profile.application.ProfileConfiguration
import cz.doucko.profile.domain.ProfileSettings
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import

/** A fictional display name (NFR-3): the store spec's own example, never a real child's name. */
const val FICTIONAL_NAME = "Hrdinka"

/** [ChildProfileStore] in memory, for web slices and context runners without a database. */
class InMemoryChildProfileStore(
    existing: Boolean = true,
) : ChildProfileStore {
    var displayName: String? = if (existing) FICTIONAL_NAME else null
        private set
    var current: ProfileSettings = ProfileSettings.DEFAULTS
    var creates = 0
        private set
    var writes = 0
        private set

    fun reset() {
        current = ProfileSettings.DEFAULTS
        writes = 0
    }

    override fun exists() = displayName != null

    override fun create(displayName: String) {
        check(this.displayName == null) { "a second profile" }
        this.displayName = displayName
        creates++
    }

    override fun settings() = current.also { checkNotNull(displayName) }

    override fun updateSettings(change: (ProfileSettings) -> ProfileSettings): ProfileSettings {
        current = change(current)
        writes++
        return current
    }
}

/** The profile use case over an existing in-memory profile, for `@WebMvcTest` slices (the controller needs it). */
@TestConfiguration(proxyBeanMethods = false)
@Import(ProfileConfiguration::class)
class ProfileWebTestBeans {
    @Bean
    fun inMemoryChildProfileStore() = InMemoryChildProfileStore()
}
