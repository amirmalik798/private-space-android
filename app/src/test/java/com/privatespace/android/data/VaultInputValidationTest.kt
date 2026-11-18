package com.privatespace.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VaultInputValidationTest {
    @Test
    fun topicNameIsTrimmed() {
        assertEquals("Certificates", VaultInputValidation.topicName("  Certificates  "))
    }

    @Test
    fun topicNameRejectsPathSeparatorsAndControls() {
        assertThrows(IllegalArgumentException::class.java) {
            VaultInputValidation.topicName("../private")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VaultInputValidation.topicName("identity\nfiles")
        }
    }

    @Test
    fun topicNameRejectsEmptyAndOversizedValues() {
        assertThrows(IllegalArgumentException::class.java) {
            VaultInputValidation.topicName("   ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VaultInputValidation.topicName("x".repeat(65))
        }
    }

    @Test
    fun displayNameRemovesUnsafePathCharacters() {
        assertEquals("_my_file_.pdf", VaultInputValidation.displayName("../my:file?.pdf"))
    }


    @Test
    fun reportedSizeTreatsNullAndNegativeProviderValuesAsUnknown() {
        // Cursor.getLong() returns 0 for a SQL NULL, so the null flag must win.
        assertEquals(null, VaultInputValidation.reportedSize(0L, isNull = true))
        assertEquals(null, VaultInputValidation.reportedSize(-1L, isNull = false))
    }

    @Test
    fun reportedSizePreservesValidZeroAndPositiveSizes() {
        assertEquals(0L, VaultInputValidation.reportedSize(0L, isNull = false))
        assertEquals(1_024L, VaultInputValidation.reportedSize(1_024L, isNull = false))
    }

    @Test
    fun mimeTypeFallsBackForMalformedInput() {
        assertEquals("application/pdf", VaultInputValidation.mimeType("application/pdf", "image/jpeg"))
        assertEquals(
            "application/octet-stream",
            VaultInputValidation.mimeType("text/plain\r\nbad", "application/octet-stream")
        )
    }

    @Test
    fun mimeTypeFallsBackWhenProviderDoesNotSupplyAMimeType() {
        assertEquals(
            "application/octet-stream",
            VaultInputValidation.mimeType(null, "application/octet-stream")
        )
        assertEquals(
            "application/pdf",
            VaultInputValidation.mimeType(null, "application/pdf")
        )
    }
}
