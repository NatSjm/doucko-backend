package cz.doucko.profile

import cz.doucko.profile.adapter.out.persistence.ChildProfileEntity
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/** NFR-3: a logged or printed profile entity never shows the display name. */
class ChildProfileEntityTest {
    // @trace NFR-3
    @Test
    fun `the entity prints no display name`() {
        ChildProfileEntity(displayName = FICTIONAL_NAME).toString() shouldNotContain FICTIONAL_NAME
    }
}
