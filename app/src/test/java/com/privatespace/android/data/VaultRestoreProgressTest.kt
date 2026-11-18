package com.privatespace.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultRestoreProgressTest {
    @Test
    fun oneTerabyteRestoreProgressUsesLongByteCountsWithoutIntOverflow() {
        val oneTerabyte = 1_099_511_627_776L
        val progress = VaultRestoreProgress(
            completedBytes = oneTerabyte / 2L,
            totalBytes = oneTerabyte,
            completedFiles = 3,
            totalFiles = 7,
            currentFileName = "large-video.mp4"
        )

        assertEquals(0.5f, progress.fraction, 0.0001f)
        assertTrue(progress.completedBytes < progress.totalBytes)
    }

    @Test
    fun progressCanDescribePreflightBeforeByteTotalsAreKnown() {
        val progress = VaultRestoreProgress(
            completedBytes = 0L,
            totalBytes = 0L,
            completedFiles = 0,
            totalFiles = 0,
            currentFileName = "",
            phase = "Checking encrypted recovery folder"
        )

        assertEquals("Checking encrypted recovery folder", progress.phase)
    }

    @Test
    fun completedEmptyRestoreReportsFullProgress() {
        val progress = VaultRestoreProgress(
            completedBytes = 0L,
            totalBytes = 0L,
            completedFiles = 0,
            totalFiles = 0,
            currentFileName = "Finalizing restored vault"
        )

        assertEquals(1f, progress.fraction, 0f)
    }
}
