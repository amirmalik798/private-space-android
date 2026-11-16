package com.privatespace.android.data

import java.io.IOException
import java.io.OutputStream

/**
 * Enforces the byte length authenticated by the vault index while streaming
 * decrypted content. It never buffers or transforms payload bytes.
 */
internal class ExactLengthOutputStream(
    private val destination: OutputStream,
    private val expectedBytes: Long
) : OutputStream() {
    var bytesWritten: Long = 0L
        private set

    init {
        require(expectedBytes >= 0L) { "Expected file size is invalid." }
    }

    override fun write(value: Int) {
        ensureCapacity(1)
        destination.write(value)
        bytesWritten++
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        ensureCapacity(length)
        destination.write(bytes, offset, length)
        bytesWritten += length.toLong()
    }

    override fun flush() = destination.flush()

    fun finish() {
        if (bytesWritten != expectedBytes) {
            throw IOException(
                "Decrypted file size does not match its authenticated vault index entry."
            )
        }
        destination.flush()
    }

    private fun ensureCapacity(additionalBytes: Int) {
        val next = try {
            Math.addExact(bytesWritten, additionalBytes.toLong())
        } catch (failure: ArithmeticException) {
            throw IOException("Decrypted file size exceeds the supported range.", failure)
        }
        if (next > expectedBytes) {
            throw IOException("Decrypted file is larger than its authenticated vault index entry.")
        }
    }
}
