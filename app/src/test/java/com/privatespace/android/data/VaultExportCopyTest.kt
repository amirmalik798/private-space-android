package com.privatespace.android.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class VaultExportCopyTest {
    @Test
    fun copiesExactBytesAndReportsMonotonicProgress() {
        val source = ByteArray(150_000) { (it % 251).toByte() }
        val destination = ByteArrayOutputStream()
        val progress = mutableListOf<Long>()

        VaultExportCopy.copy(
            ByteArrayInputStream(source),
            destination,
            source.size.toLong(),
            { progress.add(it) }
        )

        assertArrayEquals(source, destination.toByteArray())
        assertEquals(source.size.toLong(), progress.last())
        assertEquals(progress, progress.sorted())
    }

    @Test
    fun rejectsShortSourceInsteadOfTreatingItAsSuccessfulExport() {
        val destination = ByteArrayOutputStream()

        try {
            VaultExportCopy.copy(
                ByteArrayInputStream(byteArrayOf(1, 2, 3)),
                destination,
                4L
            )
            fail("A truncated staging source must fail.")
        } catch (_: IOException) {
            assertArrayEquals(byteArrayOf(1, 2, 3), destination.toByteArray())
        }
    }

    @Test
    fun rejectsExtraSourceBytesBeforeWritingBeyondExpectedLength() {
        val destination = ByteArrayOutputStream()

        try {
            VaultExportCopy.copy(
                ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)),
                destination,
                3L
            )
            fail("An oversized staging source must fail.")
        } catch (_: IOException) {
            assertArrayEquals(byteArrayOf(), destination.toByteArray())
        }
    }

    @Test
    fun copiesEmptyFileOnlyWhenExpectedLengthIsZero() {
        val destination = ByteArrayOutputStream()
        VaultExportCopy.copy(ByteArrayInputStream(byteArrayOf()), destination, 0L)
        assertArrayEquals(byteArrayOf(), destination.toByteArray())
    }

    @Test
    fun handlesStreamsThatTemporarilyReturnZeroForBulkReads() {
        val source = byteArrayOf(7, 8, 9)
        val input = object : ByteArrayInputStream(source) {
            private var returnZero = true

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (returnZero) {
                    returnZero = false
                    return 0
                }
                return super.read(buffer, offset, length)
            }
        }
        val destination = ByteArrayOutputStream()

        VaultExportCopy.copy(input, destination, source.size.toLong())

        assertArrayEquals(source, destination.toByteArray())
    }
}
