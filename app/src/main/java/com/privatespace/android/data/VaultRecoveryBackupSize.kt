package com.privatespace.android.data

import java.io.IOException

/**
 * Byte accounting for a restore from the physical encrypted recovery folder.
 * Payload bytes are counted as they are actually consumed during restore.
 */
internal data class VaultRecoveryBackupSize(
    val totalBytes: Long,
    val completedBytesBeforePayloads: Long,
    val payloadBytesById: Map<String, Long>
)

internal object VaultRecoveryBackupSizeCalculator {
    fun calculate(
        totalBytes: Long,
        encryptedPayloadSizesById: Map<String, Long>,
        referencedPayloadIds: Set<String>
    ): VaultRecoveryBackupSize {
        if (totalBytes < 0L) throw IOException("The recovery backup size is invalid.")

        val referencedSizes = linkedMapOf<String, Long>()
        var referencedBytes = 0L
        referencedPayloadIds.forEach { id ->
            val size = encryptedPayloadSizesById[id]
                ?: throw IOException("An encrypted recovery file is missing from the selected backup.")
            if (size <= 0L) throw IOException("An encrypted recovery file has an invalid stored size.")
            referencedBytes = try {
                Math.addExact(referencedBytes, size)
            } catch (failure: ArithmeticException) {
                throw IOException("The recovery backup size exceeds the supported range.", failure)
            }
            referencedSizes[id] = size
        }
        if (referencedBytes > totalBytes) {
            throw IOException("The recovery backup's file sizes are inconsistent.")
        }

        return VaultRecoveryBackupSize(
            totalBytes = totalBytes,
            completedBytesBeforePayloads = totalBytes - referencedBytes,
            payloadBytesById = referencedSizes
        )
    }
}
