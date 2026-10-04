package cz.doucko.profile.adapter.out.persistence

import cz.doucko.profile.domain.AnswerMode
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** The `child_profile` row (`V1__child_profile.sql`). Not a data class: [toString] never prints the name (NFR-3). */
@Entity
@Table(name = "child_profile")
class ChildProfileEntity(
    @Id
    private val id: Int = SINGLE_ID,
    @Column(name = "display_name", nullable = false, updatable = false)
    private val displayName: String,
    @Column(name = "secondary_task_locale")
    var secondaryTaskLocale: String? = null,
    @Enumerated(EnumType.STRING)
    @Column(name = "answer_mode", nullable = false)
    var answerMode: AnswerMode = AnswerMode.DIRECT,
    @Column(name = "created_at", nullable = false, updatable = false)
    private val createdAt: Instant = Instant.now(),
) {
    override fun toString() = "ChildProfileEntity(id=$id)"

    companion object {
        /** The schema allows only this id: one profile. */
        const val SINGLE_ID = 1
    }
}
