package com.privatespace.android.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

class ProgressOutputStreamTest {
    @Test
    fun forwardsExactBytesAndReportsCumulativeProgress() {
        val expected = ByteArray(150_321) { (it % 251).toByte() }
        val destination = ByteArrayOutputStream()
        val updates = mutableListOf<Long>()
        val output = ProgressOutputStream(destination) { updates += it }

        output.write(expected, 0, 65_536)
        output.write(expected, 65_536, expected.size - 65_536)
        output.flush()

        assertArrayEquals(expected, destination.toByteArray())
        assertEquals(listOf(65_536L, expected.size.toLong()), updates)
    }

    @Test
    fun reportsSingleByteWritesWithoutChangingContent() {
        val destination = ByteArrayOutputStream()
        val updates = mutableListOf<Long>()
        val output = ProgressOutputStream(destination, updates::add)

        output.write(7)
        output.write(8)

        assertArrayEquals(byteArrayOf(7, 8), destination.toByteArray())
        assertEquals(listOf(1L, 2L), updates)
    }
}
