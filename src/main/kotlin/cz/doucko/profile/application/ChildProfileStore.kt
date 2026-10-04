package cz.doucko.profile.application

import cz.doucko.profile.domain.ProfileSettings

/** Port to where the single profile lives (adapter: Postgres). */
interface ChildProfileStore {
    fun exists(): Boolean

    /** Creates the one profile with [displayName] and default settings. */
    fun create(displayName: String)

    fun settings(): ProfileSettings

    /** Reads the settings under a write lock, applies [change] and stores the result in one transaction. */
    fun updateSettings(change: (ProfileSettings) -> ProfileSettings): ProfileSettings
}
