package com.privatespace.android.data

import org.junit.Assert.assertEquals
import org.junit.Test

class VaultExportLayoutTest {
    @Test
    fun allTopicsExportUsesRequiredRootFolderName() {
        assertEquals("private-space-data", VaultExportLayout.ALL_TOPICS_ROOT_FOLDER_NAME)
    }

    @Test
    fun everyTopicGetsItsOwnSameNamedFolderIncludingEmptyTopics() {
        val topics = listOf(
            VaultTopic("topic-1", "Personal documents", 1L, 1L),
            VaultTopic("topic-2", "Travel photos", 2L, 2L),
            VaultTopic("topic-3", "Empty topic", 3L, 3L)
        )

        assertEquals(
            listOf(
                VaultTopicExportFolder("topic-1", "Personal documents"),
                VaultTopicExportFolder("topic-2", "Travel photos"),
                VaultTopicExportFolder("topic-3", "Empty topic")
            ),
            VaultExportLayout.allTopicFolders(topics)
        )
    }
}
