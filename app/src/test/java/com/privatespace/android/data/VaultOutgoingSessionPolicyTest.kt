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
