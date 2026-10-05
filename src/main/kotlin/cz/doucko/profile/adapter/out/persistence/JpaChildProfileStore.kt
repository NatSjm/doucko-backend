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
    override fun settings(): ProfileSettings = repository.findById(ChildProfileEntity.SINGLE_ID).orElseThrow().settings()

    @Transactional(readOnly = true)
    override fun displayName(): String = repository.findById(ChildProfileEntity.SINGLE_ID).orElseThrow().name()

    @Transactional
    override fun updateSettings(change: (ProfileSettings) -> ProfileSettings): ProfileSettings {
        val entity = checkNotNull(repository.findForUpdate(ChildProfileEntity.SINGLE_ID))
        val updated = change(entity.settings())
        entity.secondaryTaskLocale = updated.secondaryTaskLocale
        entity.answerMode = updated.answerMode
        return updated
    }

    private fun ChildProfileEntity.settings() = ProfileSettings(secondaryTaskLocale, answerMode)
}
