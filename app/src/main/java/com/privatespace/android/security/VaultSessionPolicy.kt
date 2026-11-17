package com.privatespace.android.security

/**
 * Guards asynchronous authentication and plaintext hand-offs against lifecycle
 * changes that happen while background work is still running.
 */
internal object VaultSessionPolicy {
    fun mayCompleteUnlock(
        attemptGeneration: Long?,
        currentGeneration: Long?,
        isResumed: Boolean
    ): Boolean =
        attemptGeneration != null &&
            attemptGeneration == currentGeneration &&
            isResumed

    fun mayReleasePlaintext(
        isUnlocked: Boolean,
        isResumed: Boolean
    ): Boolean = isUnlocked && isResumed

    /** Backgrounding never locks the vault; an actual Android keyguard lock does. */
    fun shouldLockForScreenLock(isKeyguardLocked: Boolean): Boolean = isKeyguardLocked

    /**
     * ACTION_SCREEN_OFF is the durable device-level signal needed when the
     * Activity has already entered the background and onPause will not run again.
     */
    fun shouldLockForDeviceScreenOff(action: String?): Boolean =
        action == "android.intent.action.SCREEN_OFF"

    fun shouldOfferRecoverySetup(
        hasPendingRecoveryPassword: Boolean,
        restoringExistingRecovery: Boolean,
        recoveryStorageAlreadyActive: Boolean
    ): Boolean =
        hasPendingRecoveryPassword &&
            !restoringExistingRecovery &&
            !recoveryStorageAlreadyActive
}
