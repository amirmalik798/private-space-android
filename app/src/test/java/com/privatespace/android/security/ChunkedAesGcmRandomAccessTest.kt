package com.privatespace.android.security

import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.io.IOException
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test

class ChunkedAesGcmRandomAccessTest {
    private val key = SecretKeySpec(ByteArray(32) { (it * 7).toByte() }, "AES")
    private val aad = "private-space:file:v2:test-record".toByteArray()

    @Test
    fun readsAuthenticatedRangesAcrossFrameBoundaries() {
        val clear = ByteArray(ChunkedAesGcm.CHUNK_BYTES * 2 + 913) { (it * 31).toByte() }
        withEncryptedFile(clear) { file ->
            val reader = VaultCrypto.openChunkedRandomAccessReaderForTest(
                RandomAccessFile(file, "r"), clear.size.toLong(), aad, key
            )
            assertNotNull(reader)
            reader!!.use {
                val offset = ChunkedAesGcm.CHUNK_BYTES - 37L
                val output = ByteArray(ChunkedAesGcm.CHUNK_BYTES + 111)
                val count = it.readAt(offset, output, 0, output.size)
                assertEquals(output.size, count)
                assertArrayEquals(
                    clear.copyOfRange(offset.toInt(), offset.toInt() + output.size),
                    output
                )
                assertEquals(-1, it.readAt(clear.size.toLong(), ByteArray(1), 0, 1))
            }
        }
    }

    @Test
    fun readsFramesInNonSequentialOrderWithReusedCipher() {
        val clear = ByteArray(ChunkedAesGcm.CHUNK_BYTES * 3 + 37) { (it * 19).toByte() }
        withEncryptedFile(clear) { file ->
            val reader = VaultCrypto.openChunkedRandomAccessReaderForTest(
                RandomAccessFile(file, "r"), clear.size.toLong(), aad, key
            )
            assertNotNull(reader)
            reader!!.use {
                val ranges = listOf(
                    (ChunkedAesGcm.CHUNK_BYTES * 2 + 7).toLong() to 121,
                    19L to 211,
                    (ChunkedAesGcm.CHUNK_BYTES + 33).toLong() to 177,
                    (ChunkedAesGcm.CHUNK_BYTES * 2 + 7).toLong() to 121,
                    (ChunkedAesGcm.CHUNK_BYTES * 3 + 3).toLong() to 29
                )
                ranges.forEach { (offset, length) ->
                    val output = ByteArray(length)
                    assertEquals(length, it.readAt(offset, output, 0, length))
                    assertArrayEquals(
                        clear.copyOfRange(offset.toInt(), offset.toInt() + length),
                        output
                    )
                }
            }
        }
    }

    @Test
    fun rejectsTamperedRequestedFrame() {
        val clear = ByteArray(ChunkedAesGcm.CHUNK_BYTES + 100) { it.toByte() }
        withEncryptedFile(clear) { file ->
            RandomAccessFile(file, "rw").use { encrypted ->
                // Corrupt a known ciphertext byte, then seek back before writing.
                // Keeping the read/write offsets explicit avoids depending on the
                // RandomAccessFile cursor side effect inside the write expression.
                val ciphertextOffset = 8L + 4L + 12L
                encrypted.seek(ciphertextOffset)
                val original = encrypted.read()
                check(original >= 0) { "Expected encrypted frame data." }
                encrypted.seek(ciphertextOffset)
                encrypted.write(original xor 0x40)
                encrypted.fd.sync()
            }
            val reader = VaultCrypto.openChunkedRandomAccessReaderForTest(
                RandomAccessFile(file, "r"), clear.size.toLong(), aad, key
            )
            assertNotNull(reader)
            try {
                reader!!.readAt(0L, ByteArray(1), 0, 1)
                fail("Tampered AES-GCM frame must not release plaintext.")
            } catch (_: IOException) {
                // Expected: the requested frame's authentication tag is invalid.
            } finally {
                reader?.close()
            }
        }
    }

    @Test
    fun rejectsTamperedTerminalFrameBeforeMediaDecoding() {
        val clear = ByteArray(ChunkedAesGcm.CHUNK_BYTES + 5) { (it * 3).toByte() }
        withEncryptedFile(clear) { file ->
            RandomAccessFile(file, "rw").use { encrypted ->
                encrypted.seek(encrypted.length() - 1L)
                val last = encrypted.read()
                encrypted.seek(encrypted.length() - 1L)
                encrypted.write(last xor 0x01)
            }
            try {
                VaultCrypto.openChunkedRandomAccessReaderForTest(
                    RandomAccessFile(file, "r"), clear.size.toLong(), aad, key
                )
                fail("Tampered terminal frame must be rejected.")
            } catch (_: IOException) {
                // Expected: terminal authentication binds total length and frame count.
            }
        }
    }

    private fun withEncryptedFile(clear: ByteArray, block: (File) -> Unit) {
        val file = File.createTempFile("vault-random-access-", ".psv")
        try {
            file.outputStream().use { output ->
                VaultCrypto.encryptForTest(ByteArrayInputStream(clear), output, aad, key)
            }
            block(file)
        } finally {
            file.delete()
        }
    }
}
