package com.privatespace.android.data

import java.io.File

/** Lifecycle policy for decrypted temporary files exposed through FileProvider. */
internal object VaultOutgoingSessionPolicy {
    const val PENDING_MARKER = ".handoff-pending"
    const val HANDED_OFF_MARKER = ".handed-off"

    fun isPendingSession(session: File): Boolean =
        session.isDirectory &&
            File(session, PENDING_MARKER).isFile &&
            !File(session, HANDED_OFF_MARKER).exists()

    /** Pending sessions were never handed off; legacy unmarked sessions keep their old TTL. */
    fun shouldDeleteAtStartup(session: File): Boolean = isPendingSession(session)

    fun isExpired(lastModified: Long, now: Long, ttlMs: Long): Boolean {
        require(ttlMs >= 0L) { "TTL must not be negative." }
        return lastModified <= 0L || (now >= lastModified && now - lastModified > ttlMs)
    }
}
