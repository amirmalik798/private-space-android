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
