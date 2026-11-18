package com.privatespace.android.data

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-local leases for temporary outgoing sessions. A WorkManager cleanup
 * may run while the app is backgrounded, so it must not delete a file currently
 * being prepared. After process death this in-memory registry resets and
 * abandoned pending sessions become eligible for cleanup.
 */
internal object VaultOutgoingSessionRegistry {
    private val activeSessionPaths = ConcurrentHashMap.newKeySet<String>()

    fun markActive(session: File) {
        activeSessionPaths += session.absolutePath
    }

    fun markInactive(session: File) {
        activeSessionPaths.remove(session.absolutePath)
    }

    fun isActive(session: File): Boolean =
        session.absolutePath in activeSessionPaths
}
