package com.privatespace.android.data

import java.io.FilterInputStream
import java.io.InputStream

/** Counts streamed bytes with a Long counter and no application file-size cap. */
internal class UnboundedCountingInputStream(input: InputStream) : FilterInputStream(input) {
    var bytesRead: Long = 0L
        private set

    private fun addBytes(count: Int) {
        if (count > 0) bytesRead = Math.addExact(bytesRead, count.toLong())
    }

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) addBytes(1)
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val count = super.read(buffer, offset, length)
        addBytes(count)
        return count
    }
}
