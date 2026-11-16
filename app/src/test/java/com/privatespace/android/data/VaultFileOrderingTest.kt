package com.privatespace.android.data

import org.junit.Assert.assertEquals
import org.junit.Test

class VaultFileOrderingTest {
    private fun file(id: String, addedAt: Long) = VaultFile(
        id = id,
        topicId = "topic",
        displayName = id,
        mimeType = "application/octet-stream",
        sizeBytes = 1L,
        addedAt = addedAt
    )

    @Test
    fun newestFirstUsesTopicAdditionTimestamp() {
        val files = listOf(file("older-z", 100L), file("newest-a", 300L), file("middle", 200L))
        assertEquals(listOf("newest-a", "middle", "older-z"), VaultFileOrdering.newestFirst(files).map { it.id })
    }

    @Test
    fun oldestFirstUsesTopicAdditionTimestamp() {
        val files = listOf(file("newest", 300L), file("oldest", 100L), file("middle", 200L))
        assertEquals(listOf("oldest", "middle", "newest"), VaultFileOrdering.oldestFirst(files).map { it.id })
    }

    @Test
    fun equalTimestampsHaveDeterministicOrdering() {
        val files = listOf(file("z", 100L), file("a", 100L), file("m", 100L))
        assertEquals(listOf("a", "m", "z"), VaultFileOrdering.newestFirst(files).map { it.id })
        assertEquals(listOf("a", "m", "z"), VaultFileOrdering.oldestFirst(files).map { it.id })
    }

    @Test
    fun orderingPreservesEveryFile() {
        val files = listOf(file("one", 10L), file("two", 20L), file("three", 30L))
        assertEquals(files.map { it.id }.toSet(), VaultFileOrdering.newestFirst(files).map { it.id }.toSet())
        assertEquals(files.size, VaultFileOrdering.oldestFirst(files).size)
    }
}
