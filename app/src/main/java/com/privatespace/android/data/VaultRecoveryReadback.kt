package com.privatespace.android.data

import java.io.IOException

/**
 * SAF document providers can briefly return a stale or incomplete view immediately
 * after a successful close. A newly written recovery record is not accepted until
 * a fresh read authenticates. Failed readbacks are wiped and retried a few times.
 */
internal object VaultRecoveryReadback {
    private const val DEFAULT_ATTEMPTS = 3
    private const val DEFAULT_INITIAL_DELAY_MS = 100L

    fun verifyWithRetry(
        verify: () -> Unit,
        operation: String = "encrypted recovery data",
        attempts: Int = DEFAULT_ATTEMPTS,
        initialDelayMs: Long = DEFAULT_INITIAL_DELAY_MS,
        pause: (Long) -> Unit = ::pauseForProvider
    ) {
        require(attempts in 1..5) { "Invalid recovery verification attempt count." }
        require(initialDelayMs in 0L..1_000L) { "Invalid recovery verification delay." }

        var lastFailure: Exception? = null
        repeat(attempts) { attempt ->
            try {
                verify()
                return
            } catch (failure: Exception) {
                lastFailure = failure
            }
            if (attempt < attempts - 1 && initialDelayMs > 0L) {
                pause(initialDelayMs * (attempt + 1L))
            }
        }

        throw IOException(
            "Android could not verify $operation in the selected folder. " +
                "Choose a writable folder on this device and try again. Your original vault has not been changed.",
            lastFailure
        )
    }

    fun readVerifiedBytes(
        read: () -> ByteArray,
        verify: (ByteArray) -> Boolean,
        operation: String = "encrypted recovery data",
        attempts: Int = DEFAULT_ATTEMPTS,
        initialDelayMs: Long = DEFAULT_INITIAL_DELAY_MS,
        pause: (Long) -> Unit = ::pauseForProvider
    ): ByteArray {
        require(attempts in 1..5) { "Invalid recovery verification attempt count." }
        require(initialDelayMs in 0L..1_000L) { "Invalid recovery verification delay." }

        var lastFailure: Exception? = null
        repeat(attempts) { attempt ->
            var candidate: ByteArray? = null
            var accepted = false
            try {
                val bytes = read()
                candidate = bytes
                if (verify(bytes)) {
                    accepted = true
                    return bytes
                }
                lastFailure = IOException("The encrypted recovery record did not authenticate after writing.")
            } catch (failure: Exception) {
                lastFailure = failure
            } finally {
                if (!accepted) candidate?.fill(0)
            }

            if (attempt < attempts - 1 && initialDelayMs > 0L) {
                pause(initialDelayMs * (attempt + 1L))
            }
        }

        throw IOException(
            "Android could not verify $operation in the selected folder. " +
                "Choose a writable folder on this device and try again. Your original vault has not been changed.",
            lastFailure
        )
    }

    private fun pauseForProvider(delayMs: Long) {
        try {
            Thread.sleep(delayMs)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Recovery verification was interrupted.", interrupted)
        }
    }
}
