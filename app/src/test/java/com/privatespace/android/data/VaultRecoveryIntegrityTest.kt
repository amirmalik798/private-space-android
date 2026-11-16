package com.privatespace.android.data

import org.junit.Assert.assertFalse
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
    fun rejectsTruncatedOrUnexpectedContainerLengths() {
        assertFalse(VaultRecoveryIntegrity.hasExpectedEncryptedLength(0L, 27L))
        assertFalse(VaultRecoveryIntegrity.hasExpectedEncryptedLength(0L, 35L))
        assertFalse(VaultRecoveryIntegrity.hasExpectedEncryptedLength(1_024L, 1_051L))
        assertFalse(VaultRecoveryIntegrity.hasExpectedEncryptedLength(1_024L, 1_061L))
    }

    @Test
    fun rejectsNegativeAndOverflowingLengths() {
        assertFalse(VaultRecoveryIntegrity.hasExpectedEncryptedLength(-1L, 27L))
        assertFalse(VaultRecoveryIntegrity.hasExpectedEncryptedLength(1L, -1L))
        assertFalse(VaultRecoveryIntegrity.hasExpectedEncryptedLength(Long.MAX_VALUE, 0L))
    }
}
