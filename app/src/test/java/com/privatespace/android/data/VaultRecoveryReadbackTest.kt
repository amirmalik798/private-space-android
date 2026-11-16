package com.privatespace.android.data

import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultRecoveryReadbackTest {
    @Test
    fun retriesTransientVerificationFailure() {
        var attempts = 0
        val delays = mutableListOf<Long>()

        VaultRecoveryReadback.verifyWithRetry(
            verify = {
                attempts++
                if (attempts < 2) throw IOException("Provider has not committed the data yet.")
            },
            pause = { delays += it }
        )

        assertEquals(2, attempts)
        assertEquals(listOf(100L), delays)
    }

    @Test
    fun verificationFailsAfterBoundedAttempts() {
        var attempts = 0
        val failure = assertThrows(IOException::class.java) {
            VaultRecoveryReadback.verifyWithRetry(
                verify = {
                    attempts++
                    throw IOException("Authentication failed.")
                },
                pause = {}
            )
        }

        assertEquals(3, attempts)
        assertTrue(failure.message.orEmpty().contains("original vault has not been changed"))
    }

    @Test
    fun retriesTransientInvalidReadbackAndReturnsVerifiedBytes() {
        val first = byteArrayOf(1, 2, 3)
        val expected = byteArrayOf(7, 8, 9)
        var reads = 0
        val delays = mutableListOf<Long>()

        val verified = VaultRecoveryReadback.readVerifiedBytes(
            read = {
                reads++
                if (reads == 1) first.copyOf() else expected.copyOf()
            },
            verify = { it.contentEquals(expected) },
            pause = { delays += it }
        )

        assertArrayEquals(expected, verified)
        assertEquals(2, reads)
        assertEquals(listOf(100L), delays)
    }

    @Test
    fun wipesEveryRejectedReadbackBeforeRetrying() {
        val rejected = byteArrayOf(1, 2, 3)
        val expected = byteArrayOf(4, 5, 6)
        var reads = 0

        val verified = VaultRecoveryReadback.readVerifiedBytes(
            read = {
                reads++
                if (reads == 1) rejected else expected.copyOf()
            },
            verify = { it.contentEquals(expected) },
            pause = {}
        )

        assertArrayEquals(byteArrayOf(0, 0, 0), rejected)
        assertArrayEquals(expected, verified)
    }

    @Test
    fun retriesTransientReadExceptions() {
        val expected = byteArrayOf(11, 12)
        var reads = 0

        val verified = VaultRecoveryReadback.readVerifiedBytes(
            read = {
                reads++
                if (reads == 1) throw IOException("Temporary provider read failure.")
                expected.copyOf()
            },
            verify = { it.contentEquals(expected) },
            pause = {}
        )

        assertArrayEquals(expected, verified)
        assertEquals(2, reads)
    }

    @Test
    fun failsClosedAfterBoundedAttemptsAndWipesCandidates() {
        val rejected = mutableListOf<ByteArray>()
        var reads = 0

        val failure = assertThrows(IOException::class.java) {
            VaultRecoveryReadback.readVerifiedBytes(
                read = {
                    reads++
                    ByteArray(4) { reads.toByte() }.also(rejected::add)
                },
                verify = { false },
                pause = {}
            )
        }

        assertEquals(3, reads)
        assertTrue(failure.message.orEmpty().contains("original vault has not been changed"))
        rejected.forEach { bytes ->
            assertArrayEquals(byteArrayOf(0, 0, 0, 0), bytes)
        }
    }
}
