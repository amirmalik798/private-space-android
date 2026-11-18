package com.privatespace.android.data

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultIndexGenerationPolicyTest {
    @Test
    fun failedGenerationIsSkippedOnNextAttempt() {
        val firstReserved = VaultIndexGenerationPolicy.nextGeneration(12L)
        val nextReserved = VaultIndexGenerationPolicy.nextGeneration(firstReserved)

        assertEquals(13L, firstReserved)
        assertEquals(14L, nextReserved)
    }

    @Test
    fun generationOverflowFailsWithoutWrappingToNegativeValues() {
        assertThrows(IOException::class.java) {
            VaultIndexGenerationPolicy.nextGeneration(Long.MAX_VALUE)
        }
    }

    @Test
    fun pruningKeepsNewestAndPreviousAuthenticatedGeneration() {
        val keep = setOf(21L, 19L)

        assertTrue(VaultIndexGenerationPolicy.shouldPrune(18L, keep))
        assertFalse(VaultIndexGenerationPolicy.shouldPrune(19L, keep))
        assertTrue(VaultIndexGenerationPolicy.shouldPrune(20L, keep))
        assertFalse(VaultIndexGenerationPolicy.shouldPrune(21L, keep))
    }
}
