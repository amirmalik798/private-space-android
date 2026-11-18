package com.privatespace.android.data

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultRecoveryBackupSizeTest {
    @Test
    fun totalUsesPhysicalFolderBytesAndActivePayloadsAreCountedDuringRestore() {
        val result = VaultRecoveryBackupSizeCalculator.calculate(
            totalBytes = 1_960_000_000L,
            encryptedPayloadSizesById = mapOf(
                "active-a" to 900_000_000L,
                "active-b" to 800_000_000L,
                "orphan" to 100_000_000L
            ),
            referencedPayloadIds = setOf("active-a", "active-b")
        )

        assertEquals(1_960_000_000L, result.totalBytes)
        assertEquals(260_000_000L, result.completedBytesBeforePayloads)
        assertEquals(1_700_000_000L, result.payloadBytesById.values.sum())
    }

    @Test
    fun countsIndexKeysMarkersAndUnreferencedFilesAsFolderOverhead() {
        val result = VaultRecoveryBackupSizeCalculator.calculate(
            totalBytes = 1_000L,
            encryptedPayloadSizesById = mapOf("active" to 700L),
            referencedPayloadIds = setOf("active")
        )
        assertEquals(300L, result.completedBytesBeforePayloads)
    }

    @Test
    fun missingReferencedPayloadFailsBeforeStartingRestore() {
        try {
            VaultRecoveryBackupSizeCalculator.calculate(100L, emptyMap(), setOf("missing"))
            throw AssertionError("Expected a missing payload to fail.")
        } catch (_: IOException) {
            // Expected: avoid starting a partial restore.
        }
    }

    @Test
    fun inconsistentOrOverflowingPhysicalSizesAreRejected() {
        try {
            VaultRecoveryBackupSizeCalculator.calculate(
                10L,
                mapOf("a" to 8L, "b" to 8L),
                setOf("a", "b")
            )
            throw AssertionError("Expected inconsistent lengths to fail.")
        } catch (_: IOException) {
            // Expected.
        }

        try {
            VaultRecoveryBackupSizeCalculator.calculate(
                Long.MAX_VALUE,
                mapOf("a" to Long.MAX_VALUE, "b" to 1L),
                setOf("a", "b")
            )
            throw AssertionError("Expected overflowing sizes to fail.")
        } catch (_: IOException) {
            // Expected.
        }
    }

    @Test
    fun initialProgressBytesCannotExceedBackupTotal() {
        val result = VaultRecoveryBackupSizeCalculator.calculate(
            2_000L,
            mapOf("a" to 1_250L),
            setOf("a")
        )
        assertTrue(result.completedBytesBeforePayloads in 0L..result.totalBytes)
    }
}
