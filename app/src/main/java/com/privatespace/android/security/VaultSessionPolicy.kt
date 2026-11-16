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

    /** Backgrounding to another app is not a close; finishing the task is. */
    fun shouldLockOnStop(
        isFinishing: Boolean,
        isChangingConfigurations: Boolean,
        suppressAutoLockForPicker: Boolean
    ): Boolean =
        isFinishing && !isChangingConfigurations && !suppressAutoLockForPicker
}
