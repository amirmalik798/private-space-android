package com.privatespace.android.data

import java.io.FilterInputStream
import java.io.InputStream

/**
 * Checks a caller-owned cancellation signal at bounded stream-read boundaries.
 * Bytes read during a cancelled operation are rejected before reaching a cipher.
 */
internal class CancellationCheckingInputStream(
    input: InputStream,
    private val checkCancelled: () -> Unit
) : FilterInputStream(input) {
    override fun read(): Int {
        checkCancelled()
        val value = super.read()
        checkCancelled()
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        checkCancelled()
        val count = super.read(buffer, offset, length)
        checkCancelled()
        return count
    }
}
