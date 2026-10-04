package cz.doucko.profile.application

import cz.doucko.profile.domain.AnswerMode
import cz.doucko.profile.domain.Change
import cz.doucko.profile.domain.ProfileSettings
import cz.doucko.profile.domain.SettingsInput
import cz.doucko.profile.domain.SettingsValidator
import cz.doucko.profile.domain.Validation

/**
 * `GET` and `PATCH` of the profile settings (store spec child-profile, FR-43). A patch is validated as a
 * whole (400 naming every invalid field), then checked for `GUIDED` (422), and only then written, so a
 * refused request changes nothing.
 */
class ProfileSettingsService(
    private val store: ChildProfileStore,
    locales: TaskLocaleProperties,
) {
    /** The configured task locales, in configuration order (the 400 detail lists them). */
    val supportedLocales: List<String> = locales.taskLocales.distinct()

    private val validator = SettingsValidator(supportedLocales.toSet())

    fun settings(): ProfileSettings = store.settings()

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
