package com.privatespace.android.data

import java.io.IOException

/**
 * Raised when the recovery index was written but could not be rolled back after
 * the local index commit failed. Callers must retain encrypted payloads because
 * the recovery index may still reference them.
 */
internal class RecoveryMirrorRollbackException(
    commitFailure: Throwable,
    rollbackFailure: Throwable
) : IOException(
    "The local vault index could not be committed and the encrypted recovery copy could not be rolled back safely. " +
        "The encrypted payload has been preserved for recovery.",
    commitFailure
) {
    init {
        addSuppressed(rollbackFailure)
    }
}

internal object VaultCommitFailurePolicy {
    fun shouldPreserveFinalizedPayload(failure: Throwable): Boolean =
        failure is RecoveryMirrorRollbackException
}
