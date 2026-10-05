package cz.doucko.profile.application

import cz.doucko.profile.ChildProfile
import cz.doucko.profile.domain.AnswerMode
import cz.doucko.profile.domain.Change
import cz.doucko.profile.domain.ProfileSettings
import cz.doucko.profile.domain.SettingsInput
import cz.doucko.profile.domain.SettingsValidator
import cz.doucko.profile.domain.Validation

/** The contract's TaskLocale shape. */
private val LOCALE_SHAPE = Regex("[a-z]{2}")

/**
 * `GET` and `PATCH` of the profile settings (store spec child-profile, FR-43). A patch is validated as a
 * whole (400 naming every invalid field), then checked for `GUIDED` (422), and only then written, so a
 * refused request changes nothing.
 */
class ProfileSettingsService(
    private val store: ChildProfileStore,
    locales: TaskLocaleProperties,
) : ChildProfile {
    /** The configured task locales, in configuration order (the 400 detail lists them). */
    val supportedLocales: List<String> =
        locales.taskLocales.distinct().onEach {
            // The contract's TaskLocale shape (it also fits the column): a bad configuration fails the startup, not a PATCH.
            require(LOCALE_SHAPE.matches(it)) { "doucko.profile.task-locales: each entry must be two lowercase letters." }
        }

    private val validator = SettingsValidator(supportedLocales.toSet())

    fun settings(): ProfileSettings = store.settings()

    override fun displayName(): String = store.displayName()

    override fun secondaryTaskLocale(): String? = store.settings().secondaryTaskLocale

    fun update(input: SettingsInput): ProfileSettings {
        val patch =
            when (val validation = validator.validate(input)) {
                is Validation.Invalid -> throw InvalidSettingsException(validation.errors)
                is Validation.Valid -> validation.patch
            }
        if (patch.answerMode == Change.To(AnswerMode.GUIDED)) throw GuidedModeNotAvailableException()
        return store.updateSettings(patch::applyTo)
    }
}
