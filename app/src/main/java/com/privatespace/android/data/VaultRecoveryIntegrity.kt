package com.privatespace.android.data

import com.privatespace.android.security.VaultCrypto

/**
 * Cheap structural checks for the app-private working copy before trusting it
 * over the separately authenticated recovery copy.
 *
 * Legacy records use a 12-byte nonce; older versioned records use an 8-byte
 * marker plus a nonce. Current chunked records include authenticated frame headers.
 */
internal object VaultRecoveryIntegrity {
    private const val LEGACY_CONTAINER_OVERHEAD_BYTES = 12L + 16L
    private const val VERSIONED_CONTAINER_OVERHEAD_BYTES = 8L + 12L + 16L

    fun hasExpectedEncryptedLength(plainBytes: Long, encryptedBytes: Long): Boolean {
        if (plainBytes < 0L || encryptedBytes < 0L) return false
        return hasLengthWithOverhead(plainBytes, encryptedBytes, LEGACY_CONTAINER_OVERHEAD_BYTES) ||
            hasLengthWithOverhead(plainBytes, encryptedBytes, VERSIONED_CONTAINER_OVERHEAD_BYTES) ||
            runCatching { VaultCrypto.encryptedContainerLength(plainBytes) == encryptedBytes }.getOrDefault(false)
    }

    private fun hasLengthWithOverhead(
        plainBytes: Long,
        encryptedBytes: Long,
        overheadBytes: Long
    ): Boolean =
        plainBytes <= Long.MAX_VALUE - overheadBytes &&
            encryptedBytes == plainBytes + overheadBytes
}
