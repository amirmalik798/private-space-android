package com.privatespace.android.data

import java.io.ByteArrayInputStream
import java.util.concurrent.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CancellationCheckingInputStreamTest {
    @Test
    fun delegatesReadsWhileOperationIsActive() {
        val input = CancellationCheckingInputStream(ByteArrayInputStream(byteArrayOf(1, 2, 3))) { }
        val bytes = ByteArray(3)
        assertEquals(3, input.read(bytes))
        assertArrayEquals(byteArrayOf(1, 2, 3), bytes)
    }

    @Test
    fun checksCancellationBeforeReading() {
        val input = CancellationCheckingInputStream(ByteArrayInputStream(byteArrayOf(1, 2, 3))) {
            throw CancellationException("cancelled")
        }
        assertThrows(CancellationException::class.java) { input.read() }
    }

    @Test
    fun rejectsBytesWhenCancellationArrivesDuringRead() {
        var checks = 0
        val input = CancellationCheckingInputStream(ByteArrayInputStream(byteArrayOf(1, 2, 3))) {
            checks++
            if (checks == 2) throw CancellationException("cancelled")
        }
        val target = ByteArray(3)
        assertThrows(CancellationException::class.java) { input.read(target) }
    }
}
