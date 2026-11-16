package com.privatespace.android.security

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
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

    class EncryptingOutputStream internal constructor(
        private val output: OutputStream,
        private val aad: ByteArray,
        private val magic: ByteArray,
        private val key: SecretKey
    ) : OutputStream() {
        private val buffer = ByteArray(CHUNK_BYTES)
        private val encryptedBuffer = ByteArray(CHUNK_BYTES + TAG_BYTES)
        private val terminalBuffer = ByteArray(TAG_BYTES)
        private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        private val frameAadBuffer = ByteArray(aad.size + 28)
        private val frameHeaderBuffer = ByteArray(LENGTH_BYTES + NONCE_BYTES)
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
            // Reinitialize the cipher per frame, but reuse its provider instance.
            // Let Android Keystore generate the nonce: keys configured with
            // randomizedEncryptionRequired must not accept caller-supplied IVs.
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val nonce = cipher.iv
            require(nonce.size == NONCE_BYTES) { "Unexpected AES-GCM nonce length." }
            val encryptedLength = try {
                updateFrameAad(cipher, aad, magic, index, length, cumulativeBytes, frameAadBuffer)
                cipher.doFinal(clear, 0, length, encryptedBuffer, 0)
            } catch (failure: Throwable) {
                nonce.fill(0)
                throw failure
            }
            try {
                check(encryptedLength == length + TAG_BYTES) { "Unexpected AES-GCM frame length." }
                writeInt(frameHeaderBuffer, 0, length)
                nonce.copyInto(frameHeaderBuffer, LENGTH_BYTES)
                output.write(frameHeaderBuffer)
                output.write(encryptedBuffer, 0, length + TAG_BYTES)
            } finally {
                frameHeaderBuffer.fill(0)
                nonce.fill(0)
                encryptedBuffer.fill(0, 0, length + TAG_BYTES)
            }
        }

        override fun flush() {
            check(!finished) { "Encryption stream is already finalized." }
            output.flush()
        }

        fun finish() {
            check(!finished) { "Encryption stream is already finalized." }
            emitFrame()
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val nonce = cipher.iv
            require(nonce.size == NONCE_BYTES) { "Unexpected AES-GCM nonce length." }
            val terminalLength = try {
                updateFrameAad(cipher, aad, magic, frameIndex, 0, totalBytes, frameAadBuffer)
                cipher.doFinal(ByteArray(0), 0, 0, terminalBuffer, 0)
            } catch (failure: Throwable) {
                nonce.fill(0)
                throw failure
            }
            try {
                check(terminalLength == TAG_BYTES) { "Unexpected AES-GCM terminal frame length." }
                writeInt(frameHeaderBuffer, 0, 0)
                nonce.copyInto(frameHeaderBuffer, LENGTH_BYTES)
                output.write(frameHeaderBuffer)
                output.write(terminalBuffer, 0, TAG_BYTES)
                frameHeaderBuffer.fill(0)
                output.flush()
                finished = true
                aad.fill(0)
                magic.fill(0)
                frameAadBuffer.fill(0)
                frameHeaderBuffer.fill(0)
            } finally {
                nonce.fill(0)
                terminalBuffer.fill(0)
                encryptedBuffer.fill(0)
                buffer.fill(0)
            }
        }

        fun abort() {
            finished = true
            buffer.fill(0)
            encryptedBuffer.fill(0)
            terminalBuffer.fill(0)
            aad.fill(0)
            magic.fill(0)
            frameAadBuffer.fill(0)
            frameHeaderBuffer.fill(0)
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
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val nonce = ByteArray(NONCE_BYTES)
        val encryptedBuffer = ByteArray(CHUNK_BYTES + TAG_BYTES)
        val clearBuffer = ByteArray(CHUNK_BYTES)
        val frameAadBuffer = ByteArray(aad.size + 28)
        val lengthBuffer = ByteArray(LENGTH_BYTES)
        try {
            while (true) {
                val length = readInt(input, lengthBuffer)
                if (length < 0 || length > CHUNK_BYTES) {
                    throw IOException("Encrypted vault frame has an invalid length.")
                }
                readExactly(input, nonce)
                val encryptedLength = length + TAG_BYTES
                readExactly(input, encryptedBuffer, encryptedLength)
                val frameTotal = if (length == 0) totalBytes else try {
                    Math.addExact(totalBytes, length.toLong())
                } catch (failure: ArithmeticException) {
                    throw IOException("Vault payload is too large.", failure)
                }
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
                updateFrameAad(cipher, aad, magic, frameIndex, length, frameTotal, frameAadBuffer)
                val clearLength = try {
                    cipher.doFinal(encryptedBuffer, 0, encryptedLength, clearBuffer, 0)
                } catch (failure: Exception) {
                    throw IOException("Vault integrity check failed. The encrypted data may be damaged or modified.", failure)
                }
                try {
                    if (length == 0) {
                        if (clearLength != 0) throw IOException("Invalid encrypted vault terminal frame.")
                        if (input.read() != -1) throw IOException("Encrypted vault contains unexpected trailing data.")
                        output.flush()
                        return
                    }
                    if (clearLength != length) throw IOException("Encrypted vault frame length does not match.")
                    output.write(clearBuffer, 0, clearLength)
                    totalBytes = frameTotal
                    frameIndex = Math.addExact(frameIndex, 1L)
                } finally {
                    nonce.fill(0)
                    encryptedBuffer.fill(0, 0, encryptedLength)
                    clearBuffer.fill(0, 0, maxOf(clearLength, 0))
                }
            }
        } finally {
            nonce.fill(0)
            encryptedBuffer.fill(0)
            clearBuffer.fill(0)
            frameAadBuffer.fill(0)
            lengthBuffer.fill(0)
        }
    }

    /**
     * Authenticated random-access reader for the current chunked container.
     * Every returned frame is independently AES-GCM authenticated. The terminal
     * frame is verified at open, binding the expected total length and frame count.
     * Unrequested payload frames are not claimed to have been fully verified;
     * normal full-file decrypt still authenticates every frame before export/open.
     */
    class RandomAccessReader internal constructor(
        private val file: RandomAccessFile,
        private val plainBytes: Long,
        aad: ByteArray,
        private val key: SecretKey,
        magic: ByteArray
    ) : AutoCloseable {
        private val aadBytes = aad.copyOf()
        private val magicBytes = magic.copyOf()
        private val frameCount = if (plainBytes == 0L) 0L else 1L + (plainBytes - 1L) / CHUNK_BYTES
        private var cachedFrameIndex = -1L
        private var cachedFrame = ByteArray(0)
        private var closed = false

        init {
            try {
                val expectedLength = encryptedLength(plainBytes)
                if (file.length() != expectedLength) {
                    throw IOException("Encrypted video container length does not match its authenticated size.")
                }
                file.seek(0L)
                val marker = ByteArray(magicBytes.size)
                file.readFully(marker)
                if (!marker.contentEquals(magicBytes)) {
                    throw IOException("Unsupported encrypted video container.")
                }
                verifyTerminalFrame()
            } catch (failure: Throwable) {
                close()
                throw failure
            }
        }

        @Synchronized
        fun readAt(position: Long, target: ByteArray, offset: Int, length: Int): Int {
            check(!closed) { "Encrypted media source is closed." }
            require(offset >= 0 && length >= 0 && offset <= target.size - length)
            if (position < 0L) throw IOException("Invalid media read position.")
            if (length == 0) return 0
            if (position >= plainBytes) return -1

            var sourcePosition = position
            var destinationOffset = offset
            var remaining = minOf(length.toLong(), plainBytes - position).toInt()
            val requested = remaining
            while (remaining > 0) {
                val frameIndex = sourcePosition / CHUNK_BYTES
                val frameOffset = (sourcePosition % CHUNK_BYTES).toInt()
                val frame = loadFrame(frameIndex)
                val count = minOf(remaining, frame.size - frameOffset)
                if (count <= 0) throw IOException("Encrypted media frame is incomplete.")
                frame.copyInto(target, destinationOffset, frameOffset, frameOffset + count)
                sourcePosition += count
                destinationOffset += count
                remaining -= count
            }
            return requested
        }

        private fun loadFrame(index: Long): ByteArray {
            if (cachedFrameIndex == index) return cachedFrame
            if (index !in 0 until frameCount) throw IOException("Encrypted media frame is outside the file.")
            cachedFrame.fill(0)
            val expectedLength = minOf(CHUNK_BYTES.toLong(), plainBytes - index * CHUNK_BYTES).toInt()
            val containerOffset = 8L + index * (CHUNK_BYTES.toLong() + LENGTH_BYTES + NONCE_BYTES + TAG_BYTES)
            file.seek(containerOffset)
            val lengthBytes = ByteArray(LENGTH_BYTES)
            file.readFully(lengthBytes)
            val actualLength = ((lengthBytes[0].toInt() and 0xff) shl 24) or
                ((lengthBytes[1].toInt() and 0xff) shl 16) or
                ((lengthBytes[2].toInt() and 0xff) shl 8) or
                (lengthBytes[3].toInt() and 0xff)
            lengthBytes.fill(0)
            if (actualLength != expectedLength) throw IOException("Encrypted media frame length is invalid.")

            val nonce = ByteArray(NONCE_BYTES)
            val encrypted = ByteArray(expectedLength + TAG_BYTES)
            val clear = ByteArray(expectedLength)
            val frameAad = ByteArray(aadBytes.size + 28)
            try {
                file.readFully(nonce)
                file.readFully(encrypted)
                val cumulative = Math.addExact(Math.multiplyExact(index, CHUNK_BYTES.toLong()), expectedLength.toLong())
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
                updateFrameAad(cipher, aadBytes, magicBytes, index, expectedLength, cumulative, frameAad)
                val clearLength = try {
                    cipher.doFinal(encrypted, 0, encrypted.size, clear, 0)
                } catch (failure: Exception) {
                    throw IOException("Vault integrity check failed while reading the video thumbnail.", failure)
                }
                if (clearLength != expectedLength) throw IOException("Encrypted media frame length does not match.")
                cachedFrame = clear
                cachedFrameIndex = index
                return cachedFrame
            } catch (failure: Throwable) {
                clear.fill(0)
                throw failure
            } finally {
                nonce.fill(0)
                encrypted.fill(0)
                frameAad.fill(0)
            }
        }

        private fun verifyTerminalFrame() {
            val terminalOffset = 8L + plainBytes + frameCount * (LENGTH_BYTES + NONCE_BYTES + TAG_BYTES)
            file.seek(terminalOffset)
            val header = ByteArray(LENGTH_BYTES)
            val nonce = ByteArray(NONCE_BYTES)
            val tag = ByteArray(TAG_BYTES)
            val frameAad = ByteArray(aadBytes.size + 28)
            try {
                file.readFully(header)
                val terminalLength = ((header[0].toInt() and 0xff) shl 24) or
                    ((header[1].toInt() and 0xff) shl 16) or
                    ((header[2].toInt() and 0xff) shl 8) or
                    (header[3].toInt() and 0xff)
                if (terminalLength != 0) throw IOException("Encrypted media terminal frame is invalid.")
                file.readFully(nonce)
                file.readFully(tag)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
                updateFrameAad(cipher, aadBytes, magicBytes, frameCount, 0, plainBytes, frameAad)
                val clear = try {
                    cipher.doFinal(tag)
                } catch (failure: Exception) {
                    throw IOException("Encrypted media terminal integrity check failed.", failure)
                }
                try {
                    if (clear.isNotEmpty()) throw IOException("Encrypted media terminal frame is invalid.")
                } finally {
                    clear.fill(0)
                }
            } finally {
                header.fill(0)
                nonce.fill(0)
                tag.fill(0)
                frameAad.fill(0)
            }
        }

        @Synchronized
        override fun close() {
            if (closed) return
            closed = true
            cachedFrame.fill(0)
            cachedFrame = ByteArray(0)
            aadBytes.fill(0)
            magicBytes.fill(0)
            runCatching { file.close() }
        }
    }

    fun openRandomAccessReader(
        file: RandomAccessFile,
        plainBytes: Long,
        aad: ByteArray,
        key: SecretKey,
        magic: ByteArray
    ): RandomAccessReader {
        require(plainBytes >= 0L)
        return RandomAccessReader(file, plainBytes, aad, key, magic)
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

    /**
     * Reuses one fixed-size AAD buffer per stream. A 1 TiB payload contains
     * over sixteen million frames; allocating two arrays and a ByteBuffer for
     * every frame would create avoidable gigabytes of short-lived garbage.
     */
    private fun updateFrameAad(
        cipher: Cipher,
        aad: ByteArray,
        magic: ByteArray,
        index: Long,
        length: Int,
        cumulativeBytes: Long,
        buffer: ByteArray
    ) {
        require(buffer.size == aad.size + 28)
        aad.copyInto(buffer)
        var offset = aad.size
        magic.copyInto(buffer, offset)
        offset += magic.size
        writeLong(buffer, offset, index)
        offset += 8
        writeInt(buffer, offset, length)
        offset += 4
        writeLong(buffer, offset, cumulativeBytes)
        cipher.updateAAD(buffer, 0, buffer.size)
    }

    private fun writeLong(target: ByteArray, offset: Int, value: Long) {
        for (index in 0 until 8) {
            target[offset + index] = (value ushr (56 - index * 8)).toByte()
        }
    }

    private fun readInt(input: InputStream, buffer: ByteArray): Int {
        readExactly(input, buffer)
        return ((buffer[0].toInt() and 0xff) shl 24) or
            ((buffer[1].toInt() and 0xff) shl 16) or
            ((buffer[2].toInt() and 0xff) shl 8) or
            (buffer[3].toInt() and 0xff)
    }

    private fun writeInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    private fun readExactly(input: InputStream, target: ByteArray, length: Int) {
        require(length in 0..target.size)
        var offset = 0
        while (offset < length) {
            val count = input.read(target, offset, length - offset)
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
