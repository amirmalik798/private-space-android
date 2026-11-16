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
    fun recognizesCommonVideoContainerFormatsCaseInsensitively() {
        val videoTypes = mapOf(
            "clip.mp4" to "video/mp4",
            "clip.M4V" to "video/x-m4v",
            "clip.mov" to "video/quicktime",
            "clip.3gp" to "video/3gpp",
            "clip.3g2" to "video/3gpp2",
            "clip.mkv" to "video/x-matroska",
            "clip.webm" to "video/webm",
            "clip.avi" to "video/x-msvideo",
            "clip.mpeg" to "video/mpeg",
            "clip.m2ts" to "video/mp2t",
            "clip.ogv" to "video/ogg",
            "clip.wmv" to "video/x-ms-wmv",
            "clip.flv" to "video/x-flv"
        )
        videoTypes.forEach { (name, mime) ->
            assertEquals(mime, VaultFormatSupport.mimeTypeForName(name))
            assertTrue("$name should be detected as video", VaultFormatSupport.isVideoMimeType(mime))
        }
    }

    @Test
    fun imageAndVideoClassificationDoesNotConfuseDocuments() {
        assertTrue(VaultFormatSupport.isImageMimeType("image/jpeg"))
        assertTrue(VaultFormatSupport.isVideoMimeType("video/mp4"))
        assertFalse(VaultFormatSupport.isImageMimeType("application/pdf"))
        assertFalse(VaultFormatSupport.isVideoMimeType("application/pdf"))
    }

    @Test
    fun unknownExtensionsRemainAcceptableAsOpaqueBinaryFiles() {
        assertFalse(VaultFormatSupport.isKnownMajorFormat("custom.xyz"))
        assertEquals(null, VaultFormatSupport.mimeTypeForName("extensionless"))
    }
    @Test
    fun routesMediaByKnownExtensionWhenProviderMimeIsGenericOrWrong() {
        assertEquals(
            VaultFormatSupport.FileKind.VIDEO,
            VaultFormatSupport.kindForFile("clip.MP4", "application/octet-stream")
        )
        assertEquals(
            VaultFormatSupport.FileKind.VIDEO,
            VaultFormatSupport.kindForFile("clip.mp4", "image/jpeg")
        )
        assertEquals(
            VaultFormatSupport.FileKind.PHOTO,
            VaultFormatSupport.kindForFile("photo.JPG", "application/octet-stream")
        )
        assertEquals(
            VaultFormatSupport.FileKind.DOCUMENT,
            VaultFormatSupport.kindForFile("report.pdf", "video/mp4")
        )
    }

    @Test
    fun fallsBackToMimeTypeWhenExtensionIsUnknown() {
        assertEquals(
            VaultFormatSupport.FileKind.VIDEO,
            VaultFormatSupport.kindForFile("clip.custom", "video/mp4")
        )
        assertEquals(
            VaultFormatSupport.FileKind.PHOTO,
            VaultFormatSupport.kindForFile("photo.custom", "image/jpeg")
        )
        assertEquals(
            VaultFormatSupport.FileKind.DOCUMENT,
            VaultFormatSupport.kindForFile("unknown.custom", "application/octet-stream")
        )
    }

    @Test
    fun topicFiltersCountAndSelectEachFileCategory() {
        val files = listOf(
            VaultFile("d1", "t", "report.pdf", "application/pdf", 1, 1),
            VaultFile("p1", "t", "one.jpg", "image/jpeg", 1, 1),
            VaultFile("p2", "t", "two.png", "image/png", 1, 1),
            VaultFile("p3", "t", "three.heic", "image/heic", 1, 1),
            VaultFile("v1", "t", "one.mp4", "application/octet-stream", 1, 1),
            VaultFile("v2", "t", "two.mkv", "video/x-matroska", 1, 1)
        )

        assertEquals(6, VaultFormatSupport.TopicFilter.ALL.count(files))
        assertEquals(1, VaultFormatSupport.TopicFilter.DOCUMENTS.count(files))
        assertEquals(3, VaultFormatSupport.TopicFilter.PHOTOS.count(files))
        assertEquals(2, VaultFormatSupport.TopicFilter.VIDEOS.count(files))
        assertEquals(
            listOf("v1", "v2"),
            files.filter(VaultFormatSupport.TopicFilter.VIDEOS::matches).map { it.id }
        )
    }

}
