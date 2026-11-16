package com.privatespace.android.security

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.SecretKey

/**
 * Bounded-memory AES-GCM framing for large vault payloads.
 *
 * Every frame is independently authenticated and binds its sequence number,
 * plaintext length and cumulative byte offset. An authenticated terminal frame
 * binds the final frame count and total length, so truncation, reordering,
 * insertion and trailing data are rejected. No provider needs to buffer an
 * entire multi-gigabyte GCM message before releasing authenticated plaintext.
 */
internal object ChunkedAesGcm {
    const val CHUNK_BYTES = 64 * 1024
    private const val NONCE_BYTES = 12
    private const val TAG_BYTES = 16
    private const val LENGTH_BYTES = 4
    private const val TAG_BITS = TAG_BYTES * 8
    private val random = SecureRandom()

    class EncryptingOutputStream internal constructor(
        private val output: OutputStream,
        private val aad: ByteArray,
        private val magic: ByteArray,
        private val key: SecretKey
    ) : OutputStream() {
        private val buffer = ByteArray(CHUNK_BYTES)
        private var buffered = 0
        private var frameIndex = 0L
        private var totalBytes = 0L
        private var finished = false

        init {
            output.write(magic)
        }

        override fun write(value: Int) {
            check(!finished) { "Encryption stream is already finalized." }
            buffer[buffered++] = value.toByte()
            if (buffered == buffer.size) emitFrame()
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            check(!finished) { "Encryption stream is already finalized." }
            require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
            var sourceOffset = offset
            var remaining = length
            while (remaining > 0) {
                val count = minOf(remaining, buffer.size - buffered)
                bytes.copyInto(buffer, buffered, sourceOffset, sourceOffset + count)
                buffered += count
                sourceOffset += count
                remaining -= count
                if (buffered == buffer.size) emitFrame()
            }
        }

        private fun emitFrame() {
            if (buffered == 0) return
            val nextTotal = try {
                Math.addExact(totalBytes, buffered.toLong())
            } catch (failure: ArithmeticException) {
                throw IOException("Vault payload is too large.", failure)
            }
            writeFrame(buffer, buffered, frameIndex, nextTotal)
            totalBytes = nextTotal
            frameIndex = Math.addExact(frameIndex, 1L)
            buffer.fill(0, 0, buffered)
            buffered = 0
        }

        private fun writeFrame(clear: ByteArray, length: Int, index: Long, cumulativeBytes: Long) {
            val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
            val encrypted = try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
                cipher.updateAAD(frameAad(aad, magic, index, length, cumulativeBytes))
                cipher.doFinal(clear, 0, length)
            } finally {
                // The nonce is serialized below; keep it until the write completes.
            }
            try {
                writeInt(output, length)
                output.write(nonce)
                output.write(encrypted)
            } finally {
                nonce.fill(0)
                encrypted.fill(0)
            }
        }

        override fun flush() {
            check(!finished) { "Encryption stream is already finalized." }
            output.flush()
        }

        fun finish() {
            check(!finished) { "Encryption stream is already finalized." }
            emitFrame()
            val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
            val terminal = try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
                cipher.updateAAD(frameAad(aad, magic, frameIndex, 0, totalBytes))
                cipher.doFinal(ByteArray(0))
            } catch (failure: Throwable) {
                nonce.fill(0)
                throw failure
            }
            try {
                writeInt(output, 0)
                output.write(nonce)
                output.write(terminal)
                output.flush()
                finished = true
            } finally {
                nonce.fill(0)
                terminal.fill(0)
                buffer.fill(0)
            }
        }

        fun abort() {
            finished = true
            buffer.fill(0)
            buffered = 0
        }

        override fun close() = abort()
    }

    fun encryptingOutputStream(
        output: OutputStream,
        aad: ByteArray,
        key: SecretKey,
        magic: ByteArray
    ): EncryptingOutputStream {
        require(magic.size == 8) { "Invalid encrypted-container marker." }
        return EncryptingOutputStream(output, aad.copyOf(), magic.copyOf(), key)
    }

    @Throws(IOException::class)
    fun decryptAfterMagic(
        input: InputStream,
        output: OutputStream,
        aad: ByteArray,
        key: SecretKey,
        magic: ByteArray
    ) {
        var frameIndex = 0L
        var totalBytes = 0L
        while (true) {
            val length = readInt(input)
            if (length < 0 || length > CHUNK_BYTES) {
                throw IOException("Encrypted vault frame has an invalid length.")
            }
            val nonce = ByteArray(NONCE_BYTES)
            readExactly(input, nonce)
            val encrypted = ByteArray(length + TAG_BYTES)
            try {
                readExactly(input, encrypted)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
                cipher.updateAAD(frameAad(aad, magic, frameIndex, length, totalBytes))
                val clear = try {
                    cipher.doFinal(encrypted)
                } catch (failure: Exception) {
                    throw IOException("Vault integrity check failed. The encrypted data may be damaged or modified.", failure)
                }
                try {
                    if (length == 0) {
                        if (clear.isNotEmpty()) throw IOException("Invalid encrypted vault terminal frame.")
                        if (input.read() != -1) throw IOException("Encrypted vault contains unexpected trailing data.")
                        return
                    }
                    if (clear.size != length) throw IOException("Encrypted vault frame length does not match.")
                    output.write(clear)
                    totalBytes = try {
                        Math.addExact(totalBytes, clear.size.toLong())
                    } catch (failure: ArithmeticException) {
                        throw IOException("Vault payload is too large.", failure)
                    }
                    frameIndex = Math.addExact(frameIndex, 1L)
                } finally {
                    clear.fill(0)
                }
            } finally {
                nonce.fill(0)
                encrypted.fill(0)
            }
        }
    }

    fun encryptedLength(plainBytes: Long): Long {
        require(plainBytes >= 0L)
        val frames = if (plainBytes == 0L) 0L else 1L + (plainBytes - 1L) / CHUNK_BYTES
        return try {
            Math.addExact(
                Math.addExact(8L, plainBytes),
                Math.multiplyExact(frames + 1L, (LENGTH_BYTES + NONCE_BYTES + TAG_BYTES).toLong())
            )
        } catch (failure: ArithmeticException) {
            throw IllegalArgumentException("Vault payload is too large.", failure)
        }
    }

    private fun frameAad(
        aad: ByteArray,
        magic: ByteArray,
        index: Long,
        length: Int,
        cumulativeBytes: Long
    ): ByteArray {
        val frame = ByteBuffer.allocate(8 + 8 + 4 + 8)
            .put(magic)
            .putLong(index)
            .putInt(length)
            .putLong(cumulativeBytes)
            .array()
        return aad + frame
    }

    private fun writeInt(output: OutputStream, value: Int) {
        output.write((value ushr 24) and 0xff)
        output.write((value ushr 16) and 0xff)
        output.write((value ushr 8) and 0xff)
        output.write(value and 0xff)
    }

    private fun readInt(input: InputStream): Int {
        val bytes = ByteArray(4)
        readExactly(input, bytes)
        return ((bytes[0].toInt() and 0xff) shl 24) or
            ((bytes[1].toInt() and 0xff) shl 16) or
            ((bytes[2].toInt() and 0xff) shl 8) or
            (bytes[3].toInt() and 0xff)
    }

    private fun readExactly(input: InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val count = input.read(target, offset, target.size - offset)
            when {
                count < 0 -> throw EOFException("Encrypted vault record is truncated.")
                count == 0 -> {
                    val value = input.read()
                    if (value < 0) throw EOFException("Encrypted vault record is truncated.")
                    target[offset++] = value.toByte()
                }
                else -> offset += count
            }
        }
    }
}
