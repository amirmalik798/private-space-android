package com.privatespace.android.data

import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultCommitFailurePolicyTest {
    @Test
    fun uncertainRecoveryRollbackRequiresPreservingEncryptedPayload() {
        val commitFailure = IOException("Local AtomicFile commit failed.")
        val rollbackFailure = IOException("Recovery index rollback failed.")
        val failure = RecoveryMirrorRollbackException(commitFailure, rollbackFailure)

        assertTrue(VaultCommitFailurePolicy.shouldPreserveFinalizedPayload(failure))
        assertSame(commitFailure, failure.cause)
        assertSame(rollbackFailure, failure.suppressed.single())
    }

    @Test
    fun ordinaryFailuresDoNotChangeTheExistingCleanupPolicy() {
        assertFalse(
            VaultCommitFailurePolicy.shouldPreserveFinalizedPayload(
                IOException("Import did not complete.")
            )
        )
    }
}
