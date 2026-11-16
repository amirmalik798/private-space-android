package com.privatespace.android.data

/**
 * Selects a power-of-two decode sample so list previews are bounded in memory.
 */
internal object VaultThumbnailPolicy {
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

    /** Returns a non-zero frame target that preserves aspect ratio within the memory bound. */
    fun fitWithin(width: Int, height: Int, targetSizePx: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0 || targetSizePx <= 0) return 1 to 1
        val longestSide = maxOf(width, height).toDouble()
        val scale = targetSizePx.coerceIn(32, 512).toDouble() / longestSide
        return (width * scale).toInt().coerceAtLeast(1) to
            (height * scale).toInt().coerceAtLeast(1)
    }
}
