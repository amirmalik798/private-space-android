package com.privatespace.android.data

import org.junit.Assert.assertEquals
import org.junit.Test

class VaultStorageCalculatorTest {
    private val topicA = VaultTopic("topic-a", "Certificates", 1L, 1L)
    private val topicB = VaultTopic("topic-b", "Travel", 2L, 2L)

    @Test
    fun totalsActualEncryptedBytesAcrossTopics() {
        val files = listOf(
            VaultFile("file-1", topicA.id, "degree.pdf", "application/pdf", 1_024L, 1L),
            VaultFile("file-2", topicA.id, "passport.jpg", "image/jpeg", 2_048L, 2L),
            VaultFile("file-3", topicB.id, "holiday.png", "image/png", 4_096L, 3L)
        )
        val usage = VaultStorageCalculator.calculate(
            listOf(topicA, topicB),
            files,
            mapOf("file-1" to 1_060L, "file-2" to 2_084L, "file-3" to 4_132L)
        )
        assertEquals(7_276L, usage.totalBytes)
        assertEquals(3_144L, usage.topicBytes[topicA.id])
        assertEquals(4_132L, usage.topicBytes[topicB.id])
    }

    @Test
    fun missingEncryptedFileCountsAsZeroButTopicRemainsVisible() {
        val usage = VaultStorageCalculator.calculate(
            listOf(topicA, topicB),
            listOf(VaultFile("missing", topicA.id, "missing.pdf", "application/pdf", 900L, 1L)),
            emptyMap()
        )
        assertEquals(0L, usage.totalBytes)
        assertEquals(0L, usage.topicBytes[topicA.id])
        assertEquals(0L, usage.topicBytes[topicB.id])
    }

    @Test
    fun fileWithNoCurrentTopicDoesNotPolluteStorageTotals() {
        val usage = VaultStorageCalculator.calculate(
            listOf(topicA),
            listOf(VaultFile("orphan", topicB.id, "orphan.pdf", "application/pdf", 1L, 1L)),
            mapOf("orphan" to 100L)
        )
        assertEquals(0L, usage.totalBytes)
        assertEquals(0L, usage.topicBytes[topicA.id])
    }
}
