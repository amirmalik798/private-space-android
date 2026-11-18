package com.privatespace.android.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import java.io.IOException
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultRecoveryIntegrityTest {
    @Test
    fun acceptsLegacyAndVersionedEncryptedContainerLengths() {
        assertTrue(VaultRecoveryIntegrity.hasExpectedEncryptedLength(0L, 28L))
        assertTrue(VaultRecoveryIntegrity.hasExpectedEncryptedLength(0L, 36L))
        assertTrue(VaultRecoveryIntegrity.hasExpectedEncryptedLength(1_024L, 1_052L))
        assertTrue(VaultRecoveryIntegrity.hasExpectedEncryptedLength(1_024L, 1_060L))
    }

    @Test
    fun acceptsNewChunkedContainerLengthsIncludingOneTerabyte() {
        val sizes = listOf(0L, 1L, 65_536L, 65_537L, 1_099_511_627_776L)
        sizes.forEach { plain ->
            assertTrue(
                VaultRecoveryIntegrity.hasExpectedEncryptedLength(
                    plain,
                    com.privatespace.android.security.VaultCrypto.encryptedContainerLength(plain)
                )
            )
        }
    }

    @Test
    fun rejectsTruncatedOrUnexpectedContainerLengths() {
        assertFalse(VaultRecoveryIntegrity.hasExpectedEncryptedLength(0L, 27L))
        assertFalse(VaultRecoveryIntegrity.hasExpectedEncryptedLength(0L, 35L))
        assertFalse(VaultRecoveryIntegrity.hasExpectedEncryptedLength(1_024L, 1_051L))
        assertFalse(VaultRecoveryIntegrity.hasExpectedEncryptedLength(1_024L, 1_061L))
    }

    @Test
    fun recoveryInventoryRejectsMissingIndexedPayloads() {
        val failure = assertThrows(IOException::class.java) {
            VaultRecoveryIntegrity.validateRecoveryPayloadInventory(
                indexedPlainBytesById = mapOf("active-file" to 128L, "trash-file" to 0L),
                encryptedBytesById = mapOf("active-file" to 128L + 32L)
            )
        }

        assertTrue(failure.message.orEmpty().contains("referenced by its authenticated index are missing"))
    }

    @Test
    fun recoveryInventoryRejectsTruncatedIndexedPayloads() {
        val failure = assertThrows(IOException::class.java) {
            VaultRecoveryIntegrity.validateRecoveryPayloadInventory(
                indexedPlainBytesById = mapOf("active-file" to 1_024L),
                encryptedBytesById = mapOf("active-file" to 1_059L)
            )
        }

        assertTrue(failure.message.orEmpty().contains("truncated"))
    }

    @Test
    fun recoveryInventoryAcceptsLegacyAndCurrentPayloadsAndRetainsUnreferencedRecords() {
        val currentPlainBytes = 65_537L
        val currentEncryptedBytes =
            com.privatespace.android.security.VaultCrypto.encryptedContainerLength(currentPlainBytes)

        VaultRecoveryIntegrity.validateRecoveryPayloadInventory(
            indexedPlainBytesById = mapOf(
                "legacy-file" to 1_024L,
                "current-file" to currentPlainBytes
            ),
            encryptedBytesById = mapOf(
                "legacy-file" to 1_024L + 36L,
                "current-file" to currentEncryptedBytes,
                "unreferenced-recovery-record" to 777L
            )
        )
    }

    @Test
    fun rejectsNegativeAndOverflowingLengths() {
        assertFalse(VaultRecoveryIntegrity.hasExpectedEncryptedLength(-1L, 27L))
        assertFalse(VaultRecoveryIntegrity.hasExpectedEncryptedLength(1L, -1L))
        assertFalse(VaultRecoveryIntegrity.hasExpectedEncryptedLength(Long.MAX_VALUE, 0L))
    }
}
