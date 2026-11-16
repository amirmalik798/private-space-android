package com.privatespace.android.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultSessionPolicyTest {
    @Test
    fun authenticationCanUnlockOnlyInTheSameForegroundGeneration() {
        assertTrue(VaultSessionPolicy.mayCompleteUnlock(7L, 7L, isResumed = true))
        assertFalse(VaultSessionPolicy.mayCompleteUnlock(7L, 8L, isResumed = true))
        assertFalse(VaultSessionPolicy.mayCompleteUnlock(7L, 7L, isResumed = false))
    }

    @Test
    fun missingLifecycleGenerationFailsClosed() {
        assertFalse(VaultSessionPolicy.mayCompleteUnlock(null, 1L, isResumed = true))
        assertFalse(VaultSessionPolicy.mayCompleteUnlock(1L, null, isResumed = true))
        assertFalse(VaultSessionPolicy.mayCompleteUnlock(null, null, isResumed = true))
    }

    @Test
    fun plaintextCanOnlyBeReleasedWhenUnlockedAndResumed() {
        assertTrue(VaultSessionPolicy.mayReleasePlaintext(isUnlocked = true, isResumed = true))
        assertFalse(VaultSessionPolicy.mayReleasePlaintext(isUnlocked = false, isResumed = true))
        assertFalse(VaultSessionPolicy.mayReleasePlaintext(isUnlocked = true, isResumed = false))
        assertFalse(VaultSessionPolicy.mayReleasePlaintext(isUnlocked = false, isResumed = false))
    }

    @Test
    fun ordinaryBackgroundingDoesNotLockTheVault() {
        assertFalse(VaultSessionPolicy.shouldLockForScreenLock(isKeyguardLocked = false))
    }

    @Test
    fun actualScreenLockLocksTheVault() {
        assertTrue(VaultSessionPolicy.shouldLockForScreenLock(isKeyguardLocked = true))
    }
    @Test
    fun recoverySetupIsNotOfferedAfterRestoreOrWhenStorageIsAlreadyActive() {
        assertFalse(
            VaultSessionPolicy.shouldOfferRecoverySetup(
                hasPendingRecoveryPassword = true,
                restoringExistingRecovery = true,
                recoveryStorageAlreadyActive = true
            )
        )
        assertFalse(
            VaultSessionPolicy.shouldOfferRecoverySetup(
                hasPendingRecoveryPassword = true,
                restoringExistingRecovery = false,
                recoveryStorageAlreadyActive = true
            )
        )
        assertFalse(
            VaultSessionPolicy.shouldOfferRecoverySetup(
                hasPendingRecoveryPassword = false,
                restoringExistingRecovery = false,
                recoveryStorageAlreadyActive = false
            )
        )
    }

    @Test
    fun recoverySetupIsOfferedOnlyForNewPasswordWithoutActiveRecovery() {
        assertTrue(
            VaultSessionPolicy.shouldOfferRecoverySetup(
                hasPendingRecoveryPassword = true,
                restoringExistingRecovery = false,
                recoveryStorageAlreadyActive = false
            )
        )
    }

}
