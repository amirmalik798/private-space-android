package com.privatespace.android.data

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Copies an already-authenticated private staging file to a user-selected
 * export destination without silently accepting truncation or extra bytes.
 *
 * The caller owns both streams and is responsible for rolling back the
 * destination if this function throws.
 */
internal object VaultExportCopy {
    fun copy(
        input: InputStream,
        output: OutputStream,
        expectedBytes: Long,
        onProgress: (Long) -> Unit = {}
    ) {
        require(expectedBytes >= 0L) { "Expected export size cannot be negative." }

        val buffer = ByteArray(64 * 1024)
        var copied = 0L
        try {
            while (true) {
                val count = input.read(buffer, 0, buffer.size)
                when {
                    count < 0 -> break
                    count == 0 -> {
                        // Some content streams can temporarily return zero.
                        // Fall back to a single-byte read so the loop progresses.
                        val value = input.read()
                        if (value < 0) break
                        val next = nextCount(copied, 1)
                        if (next > expectedBytes) {
                            throw IOException("The export staging file is larger than its authenticated vault record.")
                        }
                        output.write(value)
                        copied = next
                        onProgress(copied)
                    }
                    else -> {
                        val next = nextCount(copied, count)
                        if (next > expectedBytes) {
                            throw IOException("The export staging file is larger than its authenticated vault record.")
                        }
                        output.write(buffer, 0, count)
                        copied = next
                        onProgress(copied)
                    }
                }
            }

            if (copied != expectedBytes) {
                throw IOException("The export staging file is incomplete; its size does not match the authenticated vault record.")
            }
            output.flush()
        } finally {
            buffer.fill(0)
        }
    }

    private fun nextCount(current: Long, additional: Int): Long = try {
        Math.addExact(current, additional.toLong())
    } catch (failure: ArithmeticException) {
        throw IOException("The export size exceeds the supported range.", failure)
    }
}
