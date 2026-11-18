package com.privatespace.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryKeyPrunePolicyTest {
    @Test
    fun `retains the two newest generations`() {
        assertEquals(
            setOf(8L, 9L),
            RecoveryKeyPrunePolicy.generationsToRetain(listOf(4L, 9L, 8L, 2L), 9L)
        )
    }

    @Test
    fun `always retains the active generation even if it is older`() {
        val retained = RecoveryKeyPrunePolicy.generationsToRetain(
            listOf(3L, 4L, 5L, 6L),
            activeGeneration = 3L
        )

        assertEquals(setOf(5L, 6L, 3L), retained)
        assertTrue(3L in retained)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a missing active generation`() {
        RecoveryKeyPrunePolicy.generationsToRetain(listOf(4L, 5L), activeGeneration = 3L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects duplicate generations`() {
        RecoveryKeyPrunePolicy.generationsToRetain(listOf(4L, 4L, 5L), activeGeneration = 5L)
    }
}
