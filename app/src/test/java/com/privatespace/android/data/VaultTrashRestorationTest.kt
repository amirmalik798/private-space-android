package com.privatespace.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VaultTrashRestorationTest {
    private val originalTopic = VaultTopic(
        id = "topic-original",
        name = "Travel",
        createdAt = 100L,
        updatedAt = 200L
    )
    private val entry = VaultTrashEntry(
        file = VaultFile(
            id = "file-deleted",
            topicId = originalTopic.id,
            displayName = "passport.pdf",
            mimeType = "application/pdf",
            sizeBytes = 128L,
            addedAt = 150L
        ),
        deletedAt = 300L,
        originalTopic = originalTopic
    )

    @Test
    fun restoreRecreatesOriginalTopicWhenItWasDeleted() {
        val restored = VaultTrashRestoration.topicFor(entry, emptyList(), 500L)

        assertEquals(originalTopic.id, restored.id)
        assertEquals(originalTopic.name, restored.name)
        assertEquals(originalTopic.createdAt, restored.createdAt)
        assertEquals(500L, restored.updatedAt)
    }

    @Test
    fun restoreReusesOriginalTopicWhenItStillExists() {
        val active = originalTopic.copy(updatedAt = 400L)
        val restored = VaultTrashRestoration.topicFor(entry, listOf(active), 500L)

        assertEquals(active, restored)
    }

    @Test
    fun restoreUsesRecreatedTopicWithSameNameInsteadOfDuplicatingIt() {
        val recreated = VaultTopic(
            id = "topic-new",
            name = "TRAVEL",
            createdAt = 450L,
            updatedAt = 450L
        )
        val restored = VaultTrashRestoration.topicFor(entry, listOf(recreated), 500L)

        assertEquals(recreated, restored)
    }

    @Test
    fun recreatedTopicDoesNotReferenceThumbnailStillWaitingInTrash() {
        val thumbnailId = "photo-in-trash"
        val entryWithOtherFile = entry.copy(
            originalTopic = originalTopic.copy(thumbnailFileId = thumbnailId)
        )

        val restored = VaultTrashRestoration.topicFor(entryWithOtherFile, emptyList(), 600L)

        assertNull(restored.thumbnailFileId)
    }

    @Test
    fun restoringThumbnailPhotoRestoresCoverForRecreatedTopic() {
        val image = VaultFile(
            id = "cover-photo",
            topicId = originalTopic.id,
            displayName = "cover.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 512L,
            addedAt = 150L
        )
        val entryWithThumbnail = VaultTrashEntry(
            file = image,
            deletedAt = 300L,
            originalTopic = originalTopic.copy(thumbnailFileId = image.id)
        )

        val restored = VaultTrashRestoration.topicFor(entryWithThumbnail, emptyList(), 700L)

        assertEquals(image.id, restored.thumbnailFileId)
        assertEquals(700L, restored.updatedAt)
    }

    @Test
    fun restoresThumbnailIntoReplacementTopicAndClearsStaleTrashReferences() {
        val image = VaultFile(
            id = "cover-photo",
            topicId = originalTopic.id,
            displayName = "cover.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 512L,
            addedAt = 150L
        )
        val topicWithCover = originalTopic.copy(thumbnailFileId = image.id)
        val restoredEntry = VaultTrashEntry(image, 300L, topicWithCover)
        val remainingEntry = entry.copy(originalTopic = topicWithCover)
        val replacementTopic = originalTopic.copy(id = "topic-recreated", thumbnailFileId = image.id)

        val reconciled = VaultTrashRestoration.reconcileRemainingEntries(
            remainingEntries = listOf(remainingEntry),
            restoredEntry = restoredEntry,
            destinationTopic = replacementTopic
        )

        assertNull(reconciled.single().originalTopic.thumbnailFileId)
    }

    @Test
    fun retainsTrashThumbnailReferenceWhenPhotoReturnsToOriginalTopicIdentity() {
        val image = VaultFile(
            id = "cover-photo",
            topicId = originalTopic.id,
            displayName = "cover.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 512L,
            addedAt = 150L
        )
        val topicWithCover = originalTopic.copy(thumbnailFileId = image.id)
        val restoredEntry = VaultTrashEntry(image, 300L, topicWithCover)
        val remainingEntry = entry.copy(originalTopic = topicWithCover)

        val reconciled = VaultTrashRestoration.reconcileRemainingEntries(
            remainingEntries = listOf(remainingEntry),
            restoredEntry = restoredEntry,
            destinationTopic = topicWithCover
        )

        assertEquals(image.id, reconciled.single().originalTopic.thumbnailFileId)
    }

    @Test
    fun restoringThumbnailPhotoCanAttachItToAnExistingTopicWithoutCover() {
        val image = VaultFile(
            id = "cover-photo",
            topicId = originalTopic.id,
            displayName = "cover.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 512L,
            addedAt = 150L
        )
        val active = originalTopic.copy(updatedAt = 400L, thumbnailFileId = null)
        val entryWithThumbnail = VaultTrashEntry(
            file = image,
            deletedAt = 300L,
            originalTopic = originalTopic.copy(thumbnailFileId = image.id)
        )

        val restored = VaultTrashRestoration.topicFor(entryWithThumbnail, listOf(active), 800L)

        assertEquals(image.id, restored.thumbnailFileId)
        assertEquals(800L, restored.updatedAt)
    }
}
