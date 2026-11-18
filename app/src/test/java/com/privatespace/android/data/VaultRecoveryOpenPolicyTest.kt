package com.privatespace.android.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultRecoveryOpenPolicyTest {
    @Test
    fun blocksAutomaticRestoreWhenValidLocalAndRecoveryIndexesDiffer() {
        assertTrue(
            VaultRecoveryOpenPolicy.shouldBlockAutomaticRestore(
                hasLocalIndex = true,
                localIndexReadable = true,
                localContentMatchesRecovery = false
            )
        )
    }

    @Test
    fun allowsRestoreWhenNoLocalIndexExistsForFreshInstallation() {
        assertFalse(
            VaultRecoveryOpenPolicy.shouldBlockAutomaticRestore(
                hasLocalIndex = false,
                localIndexReadable = false,
                localContentMatchesRecovery = false
            )
        )
    }

    @Test
    fun allowsRecoveryRepairWhenLocalIndexCannotBeAuthenticated() {
        assertFalse(
            VaultRecoveryOpenPolicy.shouldBlockAutomaticRestore(
                hasLocalIndex = true,
                localIndexReadable = false,
                localContentMatchesRecovery = false
            )
        )
    }

    @Test
    fun allowsOpeningWhenBothIndexesDescribeTheSameVault() {
        assertFalse(
            VaultRecoveryOpenPolicy.shouldBlockAutomaticRestore(
                hasLocalIndex = true,
                localIndexReadable = true,
                localContentMatchesRecovery = true
            )
        )
    }
}
