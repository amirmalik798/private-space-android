package com.privatespace.android.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultTopicThumbnailPolicyTest {
    private val topic = VaultTopic(
        id = "00000000-0000-0000-0000-000000000001",
        name = "Travel",
        createdAt = 1L,
        updatedAt = 1L
    )

    private val photo = VaultFile(
        id = "00000000-0000-0000-0000-000000000002",
        topicId = topic.id,
        displayName = "cover.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 128L,
        addedAt = 1L
    )

    @Test
    fun acceptsThumbnailPointingToPhotoInSameActiveTopic() {
        VaultTopicThumbnailPolicy.validate(
            topics = listOf(topic.copy(thumbnailFileId = photo.id)),
            files = listOf(photo),
            trash = emptyList()
        )
    }

    @Test
    fun rejectsThumbnailPointingToMissingOrDifferentTopicFile() {
        val otherTopicId = "00000000-0000-0000-0000-000000000003"
        assertThrows(IllegalArgumentException::class.java) {
            VaultTopicThumbnailPolicy.validate(
                topics = listOf(topic.copy(thumbnailFileId = photo.id)),
                files = emptyList(),
                trash = emptyList()
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            VaultTopicThumbnailPolicy.validate(
                topics = listOf(topic.copy(thumbnailFileId = photo.id)),
                files = listOf(photo.copy(topicId = otherTopicId)),
                trash = emptyList()
            )
        }
    }

    @Test
    fun rejectsNonPhotoAsActiveTopicThumbnail() {
        assertThrows(IllegalArgumentException::class.java) {
            VaultTopicThumbnailPolicy.validate(
                topics = listOf(topic.copy(thumbnailFileId = photo.id)),
                files = listOf(photo.copy(displayName = "notes.pdf", mimeType = "application/pdf")),
                trash = emptyList()
            )
        }
    }

    @Test
    fun previewEligibilityRequiresSupportedPhotoAndSize() {
        assertTrue(VaultTopicThumbnailPolicy.isPreviewEligible(photo))
        assertFalse(
            VaultTopicThumbnailPolicy.isPreviewEligible(
                photo.copy(displayName = "notes.pdf", mimeType = "application/pdf")
            )
        )
        assertFalse(
            VaultTopicThumbnailPolicy.isPreviewEligible(
                photo.copy(sizeBytes = VaultThumbnailPolicy.MAX_PREVIEW_SOURCE_BYTES + 1L)
            )
        )
    }

    @Test
    fun sourceValidationChecksImageTypeAndKnownSizeButAllowsUnknownProviderSize() {
        VaultTopicThumbnailPolicy.validatePhotoSource("cover.jpg", "image/jpeg", null)
        VaultTopicThumbnailPolicy.validatePhotoSource("cover.jpg", "image/jpeg", 1024L)

        assertThrows(IllegalArgumentException::class.java) {
            VaultTopicThumbnailPolicy.validatePhotoSource("notes.pdf", "application/pdf", 1024L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VaultTopicThumbnailPolicy.validatePhotoSource(
                "cover.jpg",
                "image/jpeg",
                VaultThumbnailPolicy.MAX_PREVIEW_SOURCE_BYTES + 1L
            )
        }
    }

    @Test
    fun allowsOriginalTrashTopicToReferenceItsThumbnailWhilePhotoRemainsInTrash() {
        val topicWithThumbnail = topic.copy(thumbnailFileId = photo.id)
        val trashEntry = VaultTrashEntry(photo, 2L, topicWithThumbnail)
        VaultTopicThumbnailPolicy.validate(
            topics = emptyList(),
            files = emptyList(),
            trash = listOf(trashEntry)
        )
    }
}
