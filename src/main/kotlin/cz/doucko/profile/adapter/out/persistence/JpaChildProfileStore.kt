package cz.doucko.profile.adapter.out.persistence

import cz.doucko.profile.application.ChildProfileStore
import cz.doucko.profile.domain.ProfileSettings
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/** [ChildProfileStore] on Postgres: the one `child_profile` row. */
@Component
class JpaChildProfileStore(
    private val repository: ChildProfileJpaRepository,
) : ChildProfileStore {
    @Transactional(readOnly = true)
    override fun exists(): Boolean = repository.existsById(ChildProfileEntity.SINGLE_ID)

    @Transactional
    override fun create(displayName: String) {
        repository.save(ChildProfileEntity(displayName = displayName))
    }

    @Transactional(readOnly = true)
    override fun settings(): ProfileSettings = repository.findById(ChildProfileEntity.SINGLE_ID).orElseThrow(::missing).settings()

    @Transactional
    override fun updateSettings(change: (ProfileSettings) -> ProfileSettings): ProfileSettings {
        val entity = repository.findForUpdate(ChildProfileEntity.SINGLE_ID) ?: throw missing()
        val updated = change(entity.settings())
        entity.secondaryTaskLocale = updated.secondaryTaskLocale
        entity.answerMode = updated.answerMode
        return updated
    }

    private fun ChildProfileEntity.settings() = ProfileSettings(secondaryTaskLocale, answerMode)

    /** Cannot happen after a successful start: the initializer creates the row or the start fails. */
    private fun missing() = IllegalStateException("The child profile does not exist.")
}
