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
