package com.privatespace.android.data

import org.junit.Assert.assertThrows
import org.junit.Test

class VaultImportIntegrityTest {
    @Test
    fun knownReportedSizeMustMatchBytesReadExactly() {
        VaultImportIntegrity.validateByteCount(reportedSize = 1_024L, actualBytes = 1_024L)
        VaultImportIntegrity.validateByteCount(reportedSize = 0L, actualBytes = 0L)
    }

    @Test
    fun unknownProviderSizeAcceptsActualStreamLength() {
        VaultImportIntegrity.validateByteCount(reportedSize = null, actualBytes = 9_876_543L)
    }

    @Test
    fun shortOrLongStreamIsRejectedWhenProviderReportedSize() {
        assertThrows(IllegalStateException::class.java) {
            VaultImportIntegrity.validateByteCount(reportedSize = 1_024L, actualBytes = 1_023L)
        }
        assertThrows(IllegalStateException::class.java) {
            VaultImportIntegrity.validateByteCount(reportedSize = 1_024L, actualBytes = 1_025L)
        }
    }

    @Test
    fun negativeByteCountsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            VaultImportIntegrity.validateByteCount(reportedSize = null, actualBytes = -1L)
        }
    }
}
