package com.privatespace.android.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class ExactLengthOutputStreamTest {
    @Test
    fun preservesEveryByteWithoutTransformation() {
        val source = ByteArray(512 * 1024 + 37) { (it * 29).toByte() }
        val destination = ByteArrayOutputStream()
        val output = ExactLengthOutputStream(destination, source.size.toLong())
        output.write(source, 0, 123)
        output.write(source, 123, source.size - 123)
        output.finish()
        assertEquals(source.size.toLong(), output.bytesWritten)
        assertArrayEquals(source, destination.toByteArray())
    }

    @Test
    fun rejectsPayloadLargerThanAuthenticatedSizeBeforeWritingExtraBytes() {
        val destination = ByteArrayOutputStream()
        val output = ExactLengthOutputStream(destination, 3L)
        output.write(byteArrayOf(1, 2, 3))
        try {
            output.write(4)
            fail("Expected an over-length payload to be rejected.")
        } catch (_: IOException) {
            assertArrayEquals(byteArrayOf(1, 2, 3), destination.toByteArray())
        }
    }

    @Test
    fun rejectsTruncatedPayloadAtFinish() {
        val output = ExactLengthOutputStream(ByteArrayOutputStream(), 4L)
        output.write(byteArrayOf(1, 2, 3))
        try {
            output.finish()
            fail("Expected a truncated payload to be rejected.")
        } catch (_: IOException) {
            assertEquals(3L, output.bytesWritten)
        }
    }
}
