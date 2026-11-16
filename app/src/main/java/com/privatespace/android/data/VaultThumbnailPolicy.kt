package com.privatespace.android.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * Selects bounded bitmap sizes and decodes untrusted image files defensively.
 */
internal object VaultThumbnailPolicy {
    private const val MAX_DECODE_DIMENSION_PX = 2048
    private const val DEFAULT_MAX_DECODED_PIXELS = 4_194_304L
    // Avoid decrypting very large media files solely to draw a tiny list thumbnail.
    // Large files remain fully supported and can still be opened/exported normally.
    const val MAX_PREVIEW_SOURCE_BYTES = 16L * 1024L * 1024L

    fun shouldGeneratePreview(sourceBytes: Long): Boolean =
        sourceBytes in 1L..MAX_PREVIEW_SOURCE_BYTES
    fun calculateSampleSize(width: Int, height: Int, targetSizePx: Int): Int {
        if (width <= 0 || height <= 0 || targetSizePx <= 0) return 1

        val longestSide = maxOf(width, height).toLong()
        val target = targetSizePx.toLong()
        var sampleSize = 1
        while (
            sampleSize <= Int.MAX_VALUE / 2 &&
            longestSide / (sampleSize.toLong() * 2L) >= target
        ) {
            sampleSize *= 2
        }
        return sampleSize
    }

    fun calculateSampleSizeForPixelLimit(
        width: Int,
        height: Int,
        maximumPixels: Long
    ): Int {
        if (width <= 0 || height <= 0 || maximumPixels <= 0L) return 1

        var sampleSize = 1
        while (true) {
            val sample = sampleSize.toLong()
            val decodedPixels = (width.toLong() / sample) * (height.toLong() / sample)
            if (decodedPixels <= maximumPixels) return sampleSize
            if (sampleSize > Int.MAX_VALUE / 2) return sampleSize
            sampleSize *= 2
        }
    }

    /**
     * Decodes untrusted images within dimension and pixel-area bounds. If the
     * device still cannot allocate the bitmap, retry with a larger sample size
     * and return null rather than crashing the UI on a recoverable decode OOM.
     */
    fun decodeSampledBitmap(
        source: File,
        targetSizePx: Int,
        maximumPixels: Long = DEFAULT_MAX_DECODED_PIXELS
    ): Bitmap? {
        if (!source.isFile || source.length() <= 0L || maximumPixels <= 0L) return null

        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            inScaled = false
        }
        try {
            BitmapFactory.decodeFile(source.absolutePath, bounds)
        } catch (_: OutOfMemoryError) {
            return null
        } catch (_: RuntimeException) {
            return null
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val safeTarget = targetSizePx.coerceIn(32, MAX_DECODE_DIMENSION_PX)
        val pixelLimit = maximumPixels.coerceAtMost(DEFAULT_MAX_DECODED_PIXELS)
        var sampleSize = maxOf(
            calculateSampleSize(bounds.outWidth, bounds.outHeight, safeTarget),
            calculateSampleSizeForPixelLimit(bounds.outWidth, bounds.outHeight, pixelLimit)
        )

        while (true) {
            var allocationFailed = false
            val bitmap = try {
                BitmapFactory.decodeFile(
                    source.absolutePath,
                    BitmapFactory.Options().apply {
                        inJustDecodeBounds = false
                        inScaled = false
                        inDither = false
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                        inSampleSize = sampleSize
                    }
                )
            } catch (_: OutOfMemoryError) {
                allocationFailed = true
                null
            } catch (_: RuntimeException) {
                return null
            }

            if (bitmap != null) {
                val decodedPixels = bitmap.width.toLong() * bitmap.height.toLong()
                val maximumBytes = pixelLimit * 4L
                if (decodedPixels <= pixelLimit && bitmap.allocationByteCount.toLong() <= maximumBytes) {
                    return bitmap
                }
                if (!bitmap.isRecycled) bitmap.recycle()
            } else if (!allocationFailed) {
                return null
            }

            if (sampleSize > Int.MAX_VALUE / 2) return null
            sampleSize *= 2
        }
    }

    /** Returns a non-zero frame target that preserves aspect ratio within the memory bound. */
    fun fitWithin(width: Int, height: Int, targetSizePx: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0 || targetSizePx <= 0) return 1 to 1
        val longestSide = maxOf(width, height).toDouble()
        val scale = targetSizePx.coerceIn(32, 512).toDouble() / longestSide
        return (width * scale).toInt().coerceAtLeast(1) to
            (height * scale).toInt().coerceAtLeast(1)
    }
}
