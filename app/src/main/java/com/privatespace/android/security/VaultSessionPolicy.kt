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

    /** Lock when the vault is no longer visible; system pickers and configuration changes are exempt. */
    fun shouldLockOnStop(
        isChangingConfigurations: Boolean,
        suppressAutoLockForPicker: Boolean
    ): Boolean =
        !isChangingConfigurations && !suppressAutoLockForPicker
}
