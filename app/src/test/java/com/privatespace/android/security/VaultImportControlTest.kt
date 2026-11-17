package com.privatespace.android.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class VaultImportControlTest {
    @Test
    fun pausedImportWaitsUntilResumed() {
        val control = VaultImportControl()
        control.pause()
        val started = CountDownLatch(1)
        val passed = AtomicBoolean(false)
        val worker = Thread {
            started.countDown()
            control.awaitRunning()
            passed.set(true)
        }
        worker.start()
        assertTrue(started.await(1, TimeUnit.SECONDS))
        Thread.sleep(40)
        assertFalse(passed.get())
        control.resume()
        worker.join(1_000)
        assertTrue(passed.get())
    }

    @Test
    fun stopWakesPausedImportAndFailsClosed() {
        val control = VaultImportControl()
        control.pause()
        val started = CountDownLatch(1)
        val stopped = AtomicBoolean(false)
        val worker = Thread {
            started.countDown()
            try {
                control.awaitRunning()
            } catch (_: IOException) {
                stopped.set(true)
            }
        }
        worker.start()
        assertTrue(started.await(1, TimeUnit.SECONDS))
        control.stop()
        worker.join(1_000)
        assertTrue(stopped.get())
        assertTrue(control.isStopped)
    }

    @Test
    fun stopIsRejectedAfterFinalizationBegins() {
        val control = VaultImportControl()
        control.beginFinalizing()
        assertFalse(control.stop())
        assertTrue(control.isFinalizing)
        assertFalse(control.isStopped)
    }

    @Test
    fun finalizationCannotBeginAfterStop() {
        val control = VaultImportControl()
        assertTrue(control.stop())
        var rejected = false
        try {
            control.beginFinalizing()
        } catch (_: IOException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test
    fun stopClosesAttachedSourceStream() {
        val control = VaultImportControl()
        var closed = false
        val source = object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
            override fun close() {
                closed = true
                super.close()
            }
        }
        control.attach(source)
        control.stop()
        assertTrue(closed)
    }
}
