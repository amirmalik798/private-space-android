package com.privatespace.android.data

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultOutgoingSessionPolicyTest {
    @Test
    fun pendingPlaintextSessionIsRemovedAtNextProcessStart() {
        val root = Files.createTempDirectory("private-space-outgoing").toFile()
        try {
            val session = File(root, "session").apply { mkdir() }
            File(session, VaultOutgoingSessionPolicy.PENDING_MARKER).createNewFile()
            assertTrue(VaultOutgoingSessionPolicy.isPendingSession(session))
            assertTrue(VaultOutgoingSessionPolicy.shouldDeleteAtStartup(session))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun handedOffSessionIsNotTreatedAsAbandoned() {
        val root = Files.createTempDirectory("private-space-outgoing").toFile()
        try {
            val session = File(root, "session").apply { mkdir() }
            File(session, VaultOutgoingSessionPolicy.HANDED_OFF_MARKER).createNewFile()
            assertFalse(VaultOutgoingSessionPolicy.isPendingSession(session))
            assertFalse(VaultOutgoingSessionPolicy.shouldDeleteAtStartup(session))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun handedOffPlaintextExpiresOnlyAfterTheConfiguredTtl() {
        val lastModified = 10_000L
        val ttl = 30L * 60L * 1_000L
        assertFalse(VaultOutgoingSessionPolicy.isExpired(lastModified, lastModified + ttl, ttl))
        assertTrue(VaultOutgoingSessionPolicy.isExpired(lastModified, lastModified + ttl + 1L, ttl))
    }

    @Test
    fun unknownTimestampIsTreatedAsExpired() {
        assertTrue(VaultOutgoingSessionPolicy.isExpired(0L, 50_000L, 1_000L))
        assertTrue(VaultOutgoingSessionPolicy.isExpired(-1L, 50_000L, 1_000L))
    }

    @Test
    fun futureTimestampIsNotTreatedAsExpired() {
        assertFalse(VaultOutgoingSessionPolicy.isExpired(20_000L, 10_000L, 1_000L))
    }

    @Test
    fun backgroundCleanupProtectsAnActivePendingSession() {
        val root = Files.createTempDirectory("private-space-outgoing").toFile()
        try {
            val session = File(root, "session").apply { mkdir() }
            File(session, VaultOutgoingSessionPolicy.PENDING_MARKER).createNewFile()
            assertFalse(
                VaultOutgoingSessionPolicy.shouldDeleteInBackground(
                    session,
                    now = System.currentTimeMillis(),
                    ttlMs = 1L,
                    activeInCurrentProcess = true
                )
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun backgroundCleanupRemovesPendingSessionsFromAnEndedProcess() {
        val root = Files.createTempDirectory("private-space-outgoing").toFile()
        try {
            val session = File(root, "session").apply { mkdir() }
            File(session, VaultOutgoingSessionPolicy.PENDING_MARKER).createNewFile()
            assertTrue(
                VaultOutgoingSessionPolicy.shouldDeleteInBackground(
                    session,
                    now = System.currentTimeMillis(),
                    ttlMs = 30L * 60L * 1_000L,
                    activeInCurrentProcess = false
                )
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun stagingLeaseProtectsAnActivePreviewDuringBackgroundCleanup() {
        val root = Files.createTempDirectory("private-space-staging").toFile()
        try {
            val preview = File(root, "preview.tmp").apply { writeText("temporary") }
            VaultOutgoingSessionRegistry.markActive(preview)
            assertTrue(VaultOutgoingSessionRegistry.isActive(preview))
            VaultOutgoingSessionRegistry.markInactive(preview)
            assertFalse(VaultOutgoingSessionRegistry.isActive(preview))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun backgroundCleanupRemovesExpiredHandedOffSessions() {
        val root = Files.createTempDirectory("private-space-outgoing").toFile()
        try {
            val session = File(root, "session").apply { mkdir() }
            File(session, VaultOutgoingSessionPolicy.HANDED_OFF_MARKER).createNewFile()
            assertTrue(session.setLastModified(1_000L))
            assertTrue(
                VaultOutgoingSessionPolicy.shouldDeleteInBackground(
                    session,
                    now = 1_000L + 30L * 60L * 1_000L + 1L,
                    ttlMs = 30L * 60L * 1_000L,
                    activeInCurrentProcess = false
                )
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun legacyUnmarkedSessionKeepsItsExistingTtlBehavior() {
        val root = Files.createTempDirectory("private-space-outgoing").toFile()
        try {
            val session = File(root, "session").apply { mkdir() }
            assertFalse(VaultOutgoingSessionPolicy.shouldDeleteAtStartup(session))
        } finally {
            root.deleteRecursively()
        }
    }
}
