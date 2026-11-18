package com.privatespace.android.data

import java.io.IOException

/**
 * Monotonic generation allocation for immutable recovery indexes.
 *
 * A generation is reserved before writing so an interrupted/failed SAF write
 * can never force the next save to reuse a possibly partial filename.
 */
internal object VaultIndexGenerationPolicy {
    fun nextGeneration(highestAllocated: Long): Long {
        require(highestAllocated >= 0L) { "Invalid recovery index generation." }
        return try {
            Math.addExact(highestAllocated, 1L)
        } catch (failure: ArithmeticException) {
            throw IOException("Recovery index generations are exhausted.", failure)
        }
    }

    fun shouldPrune(generation: Long, keepGenerations: Set<Long>): Boolean =
        generation !in keepGenerations
}
