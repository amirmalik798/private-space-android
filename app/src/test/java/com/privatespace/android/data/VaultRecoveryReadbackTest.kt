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
    @Test
    fun reportsEmptyProviderReadbackWithoutExposingRawProviderError() {
        var reads = 0
        val failure = assertThrows(IOException::class.java) {
            VaultRecoveryReadback.readVerifiedBytes(
                operation = "the encrypted recovery index",
                read = {
                    reads++
                    throw IOException("The storage provider returned an empty encrypted recovery record. private-uri")
                },
                verify = { true },
                pause = {}
            )
        }

        assertEquals(3, reads)
        assertTrue(failure.message.orEmpty().contains("empty file"))
        assertTrue(failure.message.orEmpty().contains("encrypted recovery index"))
        assertTrue(!failure.message.orEmpty().contains("private-uri"))
    }

    @Test
    fun classifiesAuthenticationFailureWithoutExposingSensitiveDetails() {
        val failure = assertThrows(IOException::class.java) {
            VaultRecoveryReadback.readVerifiedBytes(
                operation = "the encrypted recovery index",
                read = { byteArrayOf(1, 2, 3) },
                verify = { throw IOException("Recovery vault integrity check failed. secret detail") },
                pause = {}
            )
        }

        assertTrue(failure.message.orEmpty().contains("authentication check failed"))
        assertTrue(!failure.message.orEmpty().contains("secret detail"))
    }

    @Test
    fun readsBoundedBytesWithoutDependingOnProviderReportedLength() {
        val expected = byteArrayOf(9, 8, 7, 6, 5, 4)
        var offset = 0
        var returnZeroOnce = true
        val providerStream = object : java.io.InputStream() {
            override fun read(): Int =
                if (offset >= expected.size) -1 else expected[offset++].toInt() and 0xff

            override fun read(buffer: ByteArray, start: Int, length: Int): Int {
                if (returnZeroOnce) {
                    returnZeroOnce = false
                    return 0
                }
                if (offset >= expected.size) return -1
                val count = minOf(2, length, expected.size - offset)
                System.arraycopy(expected, offset, buffer, start, count)
                offset += count
                return count
            }
        }

        val actual = PersistentVaultStore.readBoundedBytes(providerStream, 16)

        assertArrayEquals(expected, actual)
    }

    @Test
    fun boundedProviderReadRejectsOversizedEncryptedRecord() {
        val failure = assertThrows(IOException::class.java) {
            PersistentVaultStore.readBoundedBytes(
                java.io.ByteArrayInputStream(ByteArray(9) { it.toByte() }),
                8
            )
        }

        assertTrue(failure.message.orEmpty().contains("exceeds its supported size"))
    }

}
