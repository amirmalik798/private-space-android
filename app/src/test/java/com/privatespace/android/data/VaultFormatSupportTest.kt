package com.privatespace.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultFormatSupportTest {
    @Test
    fun recognizesCommonPhotoFormatsCaseInsensitively() {
        val photoNames = listOf(
            "photo.jpg", "photo.JPEG", "photo.png", "photo.webp", "photo.gif",
            "photo.bmp", "photo.tiff", "photo.heic", "photo.heif", "photo.avif",
            "photo.svg", "photo.dng", "photo.cr2", "photo.nef", "photo.arw"
        )
        photoNames.forEach { name ->
            assertTrue("$name should have a MIME mapping", VaultFormatSupport.isKnownMajorFormat(name))
        }
        assertEquals("image/jpeg", VaultFormatSupport.mimeTypeForName("PHOTO.JPEG"))
        assertEquals("image/heic", VaultFormatSupport.mimeTypeForName("photo.heic"))
        assertEquals("image/x-adobe-dng", VaultFormatSupport.mimeTypeForName("camera.dng"))
    }

    @Test
    fun recognizesCommonDocumentAndOfficeFormats() {
        val documentTypes = mapOf(
            "scan.pdf" to "application/pdf",
            "notes.txt" to "text/plain",
            "letter.rtf" to "application/rtf",
            "table.csv" to "text/csv",
            "book.epub" to "application/epub+zip",
            "report.docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "budget.xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "slides.pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "open.odt" to "application/vnd.oasis.opendocument.text",
            "sheet.ods" to "application/vnd.oasis.opendocument.spreadsheet",
            "deck.odp" to "application/vnd.oasis.opendocument.presentation"
        )
        documentTypes.forEach { (name, mime) ->
            assertEquals(mime, VaultFormatSupport.mimeTypeForName(name))
        }
    }

    @Test
    fun unknownExtensionsRemainAcceptableAsOpaqueBinaryFiles() {
        assertFalse(VaultFormatSupport.isKnownMajorFormat("custom.xyz"))
        assertEquals(null, VaultFormatSupport.mimeTypeForName("extensionless"))
    }
}
