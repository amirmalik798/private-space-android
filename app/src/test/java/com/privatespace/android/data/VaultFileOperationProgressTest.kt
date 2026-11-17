package com.privatespace.android.data

import org.junit.Assert.assertEquals
import org.junit.Test

class VaultFileOperationProgressTest {
    @Test
    fun calculatesAggregateFractionSafely() {
        val progress = VaultFileOperationProgress(
            phase = "Writing export",
            completedBytes = 75L,
            totalBytes = 200L,
            completedFiles = 1,
            totalFiles = 3,
            currentFileName = "photo.jpg",
            currentFileCompletedBytes = 25L,
            currentFileTotalBytes = 50L
        )

        assertEquals(0.375f, progress.fraction, 0.0001f)
    }

    @Test
    fun clampsProgressToValidRange() {
        val progress = VaultFileOperationProgress(
            phase = "Decrypting",
            completedBytes = 150L,
            totalBytes = 100L
        )

        assertEquals(1f, progress.fraction, 0f)
    }

    @Test
    fun unknownTotalUsesZeroFraction() {
        val progress = VaultFileOperationProgress(
            phase = "Preparing",
            completedBytes = 0L,
            totalBytes = 0L
        )

        assertEquals(0f, progress.fraction, 0f)
    }
}
