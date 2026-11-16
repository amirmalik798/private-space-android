package com.privatespace.android.security

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Random
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VaultCryptoBytePreservationTest {
    private val key = SecretKeySpec(
        ByteArray(32) { index -> (index * 7 + 11).toByte() },
        "AES"
    )
    private val aad = "private-space:test-file:byte-integrity".toByteArray(Charsets.UTF_8)

    @Test
    fun emptyAndBoundarySizedPayloadsRoundTripExactly() {
        val sizes = listOf(0, 1, 15, 16, 17, 4095, 4096, 65_535, 65_536, 65_537)

        sizes.forEach { size ->
            val original = payload(size)
            val (encrypted, restored) = roundTrip(original)

            assertArrayEquals("Payload mismatch at size $size", original, restored)
            assertEquals("Decrypted length changed at size $size", original.size, restored.size)
            assertEquals(
                "Unexpected encrypted container overhead at size $size",
                VaultCrypto.encryptedContainerLength(original.size.toLong()).toInt(),
                encrypted.size
            )
        }
    }

    @Test
    fun pdfJpegAndPngLikeBinaryPayloadsRemainByteIdentical() {
        val samples = listOf(
            byteArrayOf(
                0x25, 0x50, 0x44, 0x46, 0x2d, 0x31, 0x2e, 0x37,
                0x0a, 0x25, 0xe2.toByte(), 0xe3.toByte(), 0xcf.toByte(), 0xd3.toByte(), 0x0a
            ) + payload(32_111),
            byteArrayOf(
                0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte(),
                0x00, 0x10, 0x4a, 0x46, 0x49, 0x46, 0x00
            ) + payload(48_213) + byteArrayOf(0xff.toByte(), 0xd9.toByte()),
            byteArrayOf(
                0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
            ) + payload(72_319)
        )

        samples.forEachIndexed { index, original ->
            val (_, restored) = roundTrip(original)
            assertArrayEquals("Binary media sample $index changed", original, restored)
            assertEquals(original.size, restored.size)
            assertArrayEquals(
                MessageDigest.getInstance("SHA-256").digest(original),
                MessageDigest.getInstance("SHA-256").digest(restored)
            )
        }
    }

    @Test
    fun multiMegabytePayloadRoundTripsWithoutResamplingOrTruncation() {
        val original = payload(8 * 1024 * 1024 + 123)
        val (encrypted, restored) = roundTrip(original)

        assertEquals(original.size, restored.size)
        assertArrayEquals(original, restored)
        assertEquals(
            VaultCrypto.encryptedContainerLength(original.size.toLong()).toInt(),
            encrypted.size
        )
    }

    @Test
    fun temporaryZeroLengthReadsDoNotTruncateOrCorruptPayloads() {
        val original = payload(180_321)
        val encryptedOutput = ByteArrayOutputStream()
        VaultCrypto.encryptForTest(
            ZeroOnceInputStream(ByteArrayInputStream(original)),
            encryptedOutput,
            aad,
            key
        )
        val restored = ByteArrayOutputStream()
        VaultCrypto.decryptForTest(
            ZeroOnceInputStream(ByteArrayInputStream(encryptedOutput.toByteArray())),
            restored,
            aad,
            key
        )
        assertArrayEquals(original, restored.toByteArray())
    }

    @Test
    fun encryptionUsesFreshRandomNonceForEachCopy() {
        val original = payload(1024)
        val (first, firstClear) = roundTrip(original)
        val (second, secondClear) = roundTrip(original)

        assertArrayEquals(original, firstClear)
        assertArrayEquals(original, secondClear)
        assertNotEquals(
            first.copyOfRange(8, 20).toList(),
            second.copyOfRange(8, 20).toList()
        )
    }

    @Test
    fun modifiedCiphertextOrAuthenticationTagIsRejected() {
        val original = payload(100_000)
        val (encrypted, _) = roundTrip(original)
        val damaged = encrypted.copyOf()
        damaged[damaged.lastIndex] = (damaged.last().toInt() xor 0x01).toByte()

        assertThrows(Exception::class.java) {
            VaultCrypto.decryptForTest(
                ByteArrayInputStream(damaged),
                ByteArrayOutputStream(),
                aad,
                key
            )
        }
    }

    @Test
    fun wrongRecordAadIsRejected() {
        val original = payload(2048)
        val (encrypted, _) = roundTrip(original)

        assertThrows(Exception::class.java) {
            VaultCrypto.decryptForTest(
                ByteArrayInputStream(encrypted),
                ByteArrayOutputStream(),
                "different-file-id".toByteArray(Charsets.UTF_8),
                key
            )
        }
    }

    @Test
    fun originalLegacyContainerStillDecryptsByteForByte() {
        val original = payload(12_345)
        val nonce = ByteArray(12) { index -> (0x20 + index).toByte() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        val legacyContainer = nonce + cipher.doFinal(original)

        val restored = ByteArrayOutputStream()
        VaultCrypto.decryptForTest(
            ByteArrayInputStream(legacyContainer),
            restored,
            aad, // Legacy records did not use AAD.
            key
        )

        assertArrayEquals(original, restored.toByteArray())
        assertEquals(original.size, restored.size())
    }

    @Test
    fun streamingEncryptorRoundTripsExactBytes() {
        val original = payload(1_250_321)
        val encryptedOutput = ByteArrayOutputStream()
        val encrypting = VaultCrypto.encryptingOutputStreamForTest(
            encryptedOutput,
            aad,
            key
        )

        var offset = 0
        while (offset < original.size) {
            val count = minOf(17_003, original.size - offset)
            encrypting.write(original, offset, count)
            offset += count
        }
        encrypting.finish()

        val restored = ByteArrayOutputStream()
        VaultCrypto.decryptForTest(
            ByteArrayInputStream(encryptedOutput.toByteArray()),
            restored,
            aad,
            key
        )

        assertArrayEquals(original, restored.toByteArray())
        assertEquals(
            VaultCrypto.encryptedContainerLength(original.size.toLong()).toInt(),
            encryptedOutput.size()
        )
    }

    @Test
    fun abortedStreamingEncryptorDoesNotWriteAnAuthenticationTag() {
        val encryptedOutput = ByteArrayOutputStream()
        val encrypting = VaultCrypto.encryptingOutputStreamForTest(
            encryptedOutput,
            aad,
            key
        )
        encrypting.write(payload(8_192))
        encrypting.abort()

        assertThrows(Exception::class.java) {
            VaultCrypto.decryptForTest(
                ByteArrayInputStream(encryptedOutput.toByteArray()),
                ByteArrayOutputStream(),
                aad,
                key
            )
        }
    }

    @Test
    fun streamingEncryptorRejectsWritesAfterFinalization() {
        val encryptedOutput = ByteArrayOutputStream()
        val encrypting = VaultCrypto.encryptingOutputStreamForTest(
            encryptedOutput,
            aad,
            key
        )
        encrypting.write(byteArrayOf(1, 2, 3))
        encrypting.finish()

        assertThrows(IllegalStateException::class.java) {
            encrypting.write(4)
        }
    }

    private class ZeroOnceInputStream(input: InputStream) : FilterInputStream(input) {
        private var returnedZero = false
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (!returnedZero) {
                returnedZero = true
                return 0
            }
            return super.read(buffer, offset, length)
        }
    }

    private fun roundTrip(original: ByteArray): Pair<ByteArray, ByteArray> {
        val encryptedOutput = ByteArrayOutputStream()
        VaultCrypto.encryptForTest(
            ByteArrayInputStream(original),
            encryptedOutput,
            aad,
            key
        )
        val encrypted = encryptedOutput.toByteArray()

        val clearOutput = ByteArrayOutputStream()
        VaultCrypto.decryptForTest(
            ByteArrayInputStream(encrypted),
            clearOutput,
            aad,
            key
        )
        return encrypted to clearOutput.toByteArray()
    }

    private fun payload(size: Int): ByteArray {
        val result = ByteArray(size)
        Random(0x50524956L + size).nextBytes(result)
        return result
    }
}
