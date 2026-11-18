package com.privatespace.android.data

import com.privatespace.android.security.VaultCrypto
import java.io.IOException

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

    /**
     * Confirms that every encrypted payload named by the authenticated recovery
     * index is present in the selected folder and has a structurally valid size.
     * Unreferenced payloads are intentionally allowed: they may be recoverable
     * records left behind by an interrupted or older index generation.
     */
    fun validateRecoveryPayloadInventory(
        indexedPlainBytesById: Map<String, Long>,
        encryptedBytesById: Map<String, Long>
    ) {
        val missingIds = indexedPlainBytesById.keys - encryptedBytesById.keys
        if (missingIds.isNotEmpty()) {
            throw IOException(
                "The encrypted recovery folder is incomplete: one or more files referenced by its authenticated index are missing."
            )
        }

        indexedPlainBytesById.forEach { (id, plainBytes) ->
            val encryptedBytes = encryptedBytesById.getValue(id)
            if (!hasExpectedEncryptedLength(plainBytes, encryptedBytes)) {
                throw IOException(
                    "The encrypted recovery folder is incomplete: one or more files referenced by its authenticated index are truncated or have an invalid size."
                )
            }
        }
    }

    private fun hasLengthWithOverhead(
        plainBytes: Long,
        encryptedBytes: Long,
        overheadBytes: Long
    ): Boolean =
        plainBytes <= Long.MAX_VALUE - overheadBytes &&
            encryptedBytes == plainBytes + overheadBytes
}
