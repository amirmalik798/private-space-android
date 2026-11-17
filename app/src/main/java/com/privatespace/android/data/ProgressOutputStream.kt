package com.privatespace.android.data

import java.io.OutputStream

/**
 * Counts bytes passing through a streaming operation without buffering,
 * transforming, or retaining plaintext. The callback is invoked after bytes
 * have been successfully forwarded to the downstream output.
 */
internal class ProgressOutputStream(
    private val downstream: OutputStream,
    private val onProgress: (Long) -> Unit
) : OutputStream() {
    private var written = 0L

    override fun write(value: Int) {
        downstream.write(value)
        written = Math.addExact(written, 1L)
        onProgress(written)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        downstream.write(buffer, offset, length)
        if (length > 0) {
            written = Math.addExact(written, length.toLong())
            onProgress(written)
        }
    }

    override fun flush() = downstream.flush()
    override fun close() = downstream.close()
}
