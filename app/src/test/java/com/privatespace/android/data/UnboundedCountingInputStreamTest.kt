package com.privatespace.android.data

import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class UnboundedCountingInputStreamTest {
    @Test
    fun countsPayloadsLargerThanTwoGiBWithoutAnApplicationCeiling() {
        val expectedBytes = 2L * 1024L * 1024L * 1024L + 123_457L
        val counted = UnboundedCountingInputStream(VirtualZeroInputStream(expectedBytes))
        val buffer = ByteArray(64 * 1024)
        while (counted.read(buffer) >= 0) {
            // Synthetic bytes avoid allocating or writing a multi-GB test file.
        }
        assertEquals(expectedBytes, counted.bytesRead)
    }

    private class VirtualZeroInputStream(private var remaining: Long) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0L) return -1
            remaining--
            return 0
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (remaining <= 0L) return -1
            val count = minOf(length.toLong(), remaining).toInt()
            buffer.fill(0, offset, offset + count)
            remaining -= count.toLong()
            return count
        }
    }
}
