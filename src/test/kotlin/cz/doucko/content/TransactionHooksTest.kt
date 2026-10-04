package cz.doucko.content

import cz.doucko.content.application.afterCommitForTest
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.TransactionSynchronizationManager

/** A failed file removal after a committed delete never turns the delete into an error (NFR-17, CA-D5). */
class TransactionHooksTest {
    // @trace NFR-17
    @Test
    fun `a failing after-commit cleanup is swallowed`() {
        TransactionSynchronizationManager.initSynchronization()
        try {
            var ran = false
            afterCommitForTest {
                ran = true
                error("disk gone")
            }
            TransactionSynchronizationManager.getSynchronizations().forEach { it.afterCommit() }
            ran shouldBe true
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }
}
