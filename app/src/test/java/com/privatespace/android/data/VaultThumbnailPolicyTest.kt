package com.privatespace.android.data

import org.junit.Assert.assertEquals
import org.junit.Test

class VaultThumbnailPolicyTest {
    @Test
    fun downsamplesLargeImagesToNearTheRequestedLongestSide() {
        assertEquals(32, VaultThumbnailPolicy.calculateSampleSize(4000, 3000, 100))
        assertEquals(16, VaultThumbnailPolicy.calculateSampleSize(1600, 900, 100))
    }

    @Test
    fun keepsSmallImagesAtFullResolution() {
        assertEquals(1, VaultThumbnailPolicy.calculateSampleSize(80, 60, 112))
        assertEquals(1, VaultThumbnailPolicy.calculateSampleSize(112, 112, 112))
    }

    @Test
    fun videoFrameTargetPreservesAspectRatioWithinPreviewBounds() {
        assertEquals(128 to 72, VaultThumbnailPolicy.fitWithin(1920, 1080, 128))
        assertEquals(72 to 128, VaultThumbnailPolicy.fitWithin(1080, 1920, 128))
        assertEquals(128 to 128, VaultThumbnailPolicy.fitWithin(4000, 4000, 128))
    }

    @Test
    fun invalidVideoDimensionsReturnANonZeroSafeTarget() {
        assertEquals(1 to 1, VaultThumbnailPolicy.fitWithin(0, 1080, 128))
        assertEquals(1 to 1, VaultThumbnailPolicy.fitWithin(1920, 0, 128))
        assertEquals(1 to 1, VaultThumbnailPolicy.fitWithin(1920, 1080, 0))
    }

    @Test
    fun invalidDimensionsUseTheSafeNoSubsamplingValue() {
        assertEquals(1, VaultThumbnailPolicy.calculateSampleSize(0, 100, 80))
        assertEquals(1, VaultThumbnailPolicy.calculateSampleSize(100, -1, 80))
        assertEquals(1, VaultThumbnailPolicy.calculateSampleSize(100, 100, 0))
    }
}
