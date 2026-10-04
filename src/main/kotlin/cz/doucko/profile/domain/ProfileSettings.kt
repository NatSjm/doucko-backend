package cz.doucko.profile.domain

/** The answer modes of the contract; only [DIRECT] can be stored in MVP (store spec child-profile). */
enum class AnswerMode { DIRECT, GUIDED }

/** The two practice settings of the profile (FR-43). [secondaryTaskLocale] null = translation off. */
data class ProfileSettings(
    val secondaryTaskLocale: String?,
    val answerMode: AnswerMode,
) {
    companion object {
        /** A new profile: no translation, direct answers. */
        val DEFAULTS = ProfileSettings(secondaryTaskLocale = null, answerMode = AnswerMode.DIRECT)
    }
}
