package com.privatespace.android.security

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * In-process controls for one import. Pausing happens only between bounded
 * read/encrypt chunks; stopping closes the provider stream and causes the
 * repository to discard its incomplete encrypted temporary file.
 *
 * Partial encrypted data is deliberately never resumable after process death.
 */
class VaultImportControl {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private var paused = false
    private var stopped = false
    private var finalizing = false
    private var source: InputStream? = null

    val isPaused: Boolean get() = lock.withLock { paused }
    val isStopped: Boolean get() = lock.withLock { stopped }
    val isFinalizing: Boolean get() = lock.withLock { finalizing }

    fun pause() = lock.withLock {
        if (!stopped && !finalizing) paused = true
    }

    fun resume() = lock.withLock {
        if (!stopped && !finalizing) {
            paused = false
            changed.signalAll()
        }
    }

    /**
     * Atomically closes the user-cancellable phase. Once finalization begins,
     * the encrypted payload is being committed and stop is no longer accepted.
     */
    @Throws(IOException::class)
    fun beginFinalizing() = lock.withLock {
        if (stopped) throw IOException("Import stopped by the user.")
        finalizing = true
        paused = false
        changed.signalAll()
    }

    /** Returns false when the file has already entered its atomic commit phase. */
    fun stop(): Boolean {
        val toClose = lock.withLock {
            if (stopped || finalizing) return false
            stopped = true
            paused = false
            changed.signalAll()
            source
        }
        runCatching { toClose?.close() }
        return true
    }

    @Throws(IOException::class)
    fun awaitRunning() {
        try {
            lock.withLock {
                while (paused && !stopped) changed.await()
                if (stopped) throw IOException("Import stopped by the user.")
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Import was interrupted.", interrupted)
        }
    }

    @Throws(IOException::class)
    fun attach(input: InputStream) {
        val shouldClose = lock.withLock {
            if (stopped) true else {
                source = input
                false
            }
        }
        if (shouldClose) {
            runCatching { input.close() }
            throw IOException("Import stopped by the user.")
        }
    }

    fun detach(input: InputStream) = lock.withLock {
        if (source === input) source = null
    }
}
