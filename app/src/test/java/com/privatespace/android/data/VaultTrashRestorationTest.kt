package com.privatespace.android.data

import org.junit.Assert.assertEquals
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
}
