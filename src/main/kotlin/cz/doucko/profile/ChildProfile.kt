package cz.doucko.profile

/**
 * The single child profile as other modules may read it (store spec child-profile, FR-43; practice-flow
 * FR-24, FR-29). Public API of the profile module: HQ shows [displayName] (never logged, NFR-3), and the
 * task screen adds the translation of [secondaryTaskLocale] when it is on.
 */
interface ChildProfile {
    /** The display name, sent only in the HQ response to the local frontend (NFR-3). */
    fun displayName(): String

    /** The configured task locale the child turned on, or null when the translation is off. */
    fun secondaryTaskLocale(): String?
}
