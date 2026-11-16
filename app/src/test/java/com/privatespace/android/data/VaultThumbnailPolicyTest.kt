package com.privatespace.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun largeMediaSkipsExpensiveFullFileThumbnailDecryption() {
        assertTrue(VaultThumbnailPolicy.shouldGeneratePreview(1L))
        assertTrue(
            VaultThumbnailPolicy.shouldGeneratePreview(
                VaultThumbnailPolicy.MAX_PREVIEW_SOURCE_BYTES
            )
        )
        assertTrue(
            !VaultThumbnailPolicy.shouldGeneratePreview(
                VaultThumbnailPolicy.MAX_PREVIEW_SOURCE_BYTES + 1L
            )
        )
        assertTrue(!VaultThumbnailPolicy.shouldGeneratePreview(300L * 1024L * 1024L))
        assertTrue(!VaultThumbnailPolicy.shouldGeneratePreview(0L))
        assertTrue(!VaultThumbnailPolicy.shouldGeneratePreview(-1L))
    }

    @Test
    fun invalidDimensionsUseTheSafeNoSubsamplingValue() {
        assertEquals(1, VaultThumbnailPolicy.calculateSampleSize(0, 100, 80))
        assertEquals(1, VaultThumbnailPolicy.calculateSampleSize(100, -1, 80))
        assertEquals(1, VaultThumbnailPolicy.calculateSampleSize(100, 100, 0))
    }
    @Test
    fun pixelLimitSamplingBoundsLargeSquareAndPanoramicImages() {
        val maximumPixels = 4_194_304L
        val squareSample = VaultThumbnailPolicy.calculateSampleSizeForPixelLimit(
            20_000,
            20_000,
            maximumPixels
        )
        val squarePixels = (20_000L / squareSample) * (20_000L / squareSample)
        assertTrue(squarePixels <= maximumPixels)

        val panoramaSample = VaultThumbnailPolicy.calculateSampleSizeForPixelLimit(
            100_000,
            500,
            maximumPixels
        )
        val panoramaPixels = (100_000L / panoramaSample) * (500L / panoramaSample)
        assertTrue(panoramaPixels <= maximumPixels)
    }

    @Test
    fun pixelLimitSamplingKeepsSmallImagesAtFullResolution() {
        assertEquals(
            1,
            VaultThumbnailPolicy.calculateSampleSizeForPixelLimit(320, 240, 4_194_304L)
        )
    }

    @Test
    fun invalidPixelLimitUsesSafeNoSubsamplingValue() {
        assertEquals(1, VaultThumbnailPolicy.calculateSampleSizeForPixelLimit(4000, 3000, 0L))
        assertEquals(1, VaultThumbnailPolicy.calculateSampleSizeForPixelLimit(0, 3000, 100L))
    }

}
