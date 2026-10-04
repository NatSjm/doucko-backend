package cz.doucko.profile.adapter.`in`.web

import cz.doucko.api.ChildProfileApi
import cz.doucko.api.model.AnswerMode
import cz.doucko.api.model.ProfileSettings
import cz.doucko.api.model.ProfileSettingsPatch
import cz.doucko.profile.application.ProfileSettingsService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import cz.doucko.profile.domain.ProfileSettings as Settings

/**
 * `getProfileSettings` and `updateProfileSettings` from the contract (store spec child-profile, FR-43).
 * Login-free: the shared chain lets every child request through without a session or CSRF token. The
 * generated [ProfileSettingsPatch] cannot tell an absent field from `null`, so the body is read by
 * [SettingsBodyAdvice], which keeps the presence-aware input for this handler.
 */
@RestController
class ProfileSettingsController(
    private val service: ProfileSettingsService,
) : ChildProfileApi {
    override fun getProfileSettings(): ResponseEntity<ProfileSettings> = ResponseEntity.ok(service.settings().toApi())

    override fun updateProfileSettings(profileSettingsPatch: ProfileSettingsPatch): ResponseEntity<ProfileSettings> {
        val request = (RequestContextHolder.currentRequestAttributes() as ServletRequestAttributes).request
        return ResponseEntity.ok(service.update(SettingsBodyAdvice.input(request)).toApi())
    }

    private fun Settings.toApi() = ProfileSettings(secondaryTaskLocale, AnswerMode.forValue(answerMode.name))
}
