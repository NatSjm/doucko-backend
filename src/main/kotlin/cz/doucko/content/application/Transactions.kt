package cz.doucko.content.application

import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/** Runs [action] once the surrounding transaction has committed (file removal after a delete, CA-D5). */
internal fun afterCommit(action: () -> Unit) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) return action()
    TransactionSynchronizationManager.registerSynchronization(
        object : TransactionSynchronization {
            override fun afterCommit() = action()
        },
    )
}

/** Runs [action] if the surrounding transaction rolls back (a stored file whose row was never committed). */
internal fun onRollback(action: () -> Unit) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) return
    TransactionSynchronizationManager.registerSynchronization(
        object : TransactionSynchronization {
            override fun afterCompletion(status: Int) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) action()
            }
        },
    )
}
