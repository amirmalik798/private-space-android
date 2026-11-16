package com.privatespace.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VaultMediaNavigationTest {
    private fun file(id: String, topic: String, mimeType: String) = VaultFile(
        id = id,
        topicId = topic,
        displayName = "$id.bin",
        mimeType = mimeType,
        sizeBytes = 100L,
        addedAt = id.hashCode().toLong()
    )

    @Test
    fun galleryNavigationSkipsDocumentsAndKeepsOriginalMediaOrder() {
        val files = listOf(
            file("photo-1", "topic-a", "image/jpeg"),
            file("document-1", "topic-a", "application/pdf"),
            file("video-1", "topic-a", "video/mp4"),
            file("document-2", "topic-a", "application/pdf"),
            file("photo-2", "topic-a", "image/png")
        )

        val media = VaultMediaNavigation.mediaFilesInTopic(files, "topic-a")

        assertEquals(listOf("photo-1", "video-1", "photo-2"), media.map { it.id })
        assertEquals("video-1", VaultMediaNavigation.adjacent(media, "photo-1", 1)?.id)
        assertEquals("photo-2", VaultMediaNavigation.adjacent(media, "video-1", 1)?.id)
        assertEquals("photo-1", VaultMediaNavigation.adjacent(media, "video-1", -1)?.id)
        assertEquals("video-1", VaultMediaNavigation.adjacent(media, "photo-2", -1)?.id)
    }

    @Test
    fun galleryNavigationNeverIncludesMediaFromAnotherTopic() {
        val files = listOf(
            file("topic-a-photo", "topic-a", "image/jpeg"),
            file("topic-b-video", "topic-b", "video/mp4"),
            file("topic-a-video", "topic-a", "video/mp4")
        )

        val media = VaultMediaNavigation.mediaFilesInTopic(files, "topic-a")

        assertEquals(listOf("topic-a-photo", "topic-a-video"), media.map { it.id })
        assertNull(VaultMediaNavigation.adjacent(media, "topic-a-photo", -1))
        assertNull(VaultMediaNavigation.adjacent(media, "topic-a-video", 1))
    }

    @Test
    fun invalidDirectionOrUnknownCurrentItemFailsClosed() {
        val media = listOf(file("photo", "topic-a", "image/jpeg"))

        assertNull(VaultMediaNavigation.adjacent(media, "photo", 0))
        assertNull(VaultMediaNavigation.adjacent(media, "missing", 1))
        assertNull(VaultMediaNavigation.adjacent(media, "photo", 1))
        assertNull(VaultMediaNavigation.adjacent(media, "photo", -1))
    }

    @Test
    fun documentsAndOtherTopicsAreExcluded() {
        val files = listOf(
            file("pdf", "topic-a", "application/pdf"),
            file("other-topic", "topic-b", "image/jpeg"),
            file("text", "topic-a", "text/plain")
        )

        assertEquals(emptyList<VaultFile>(), VaultMediaNavigation.mediaFilesInTopic(files, "topic-a"))
    }
}
