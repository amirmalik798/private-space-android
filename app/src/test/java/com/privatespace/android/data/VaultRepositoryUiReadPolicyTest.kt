package com.privatespace.android.data

import java.lang.reflect.Modifier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultRepositoryUiReadPolicyTest {
    @Test
    fun composeRecoveryStatusGettersDoNotAcquireTheRepositoryMonitor() {
        val uriGetter = VaultRepository::class.java.getDeclaredMethod("persistentStorageUri")
        val activeGetter = VaultRepository::class.java.getDeclaredMethod("hasActivePersistentStorage")

        assertFalse(
            "The saved recovery URI is read during Compose rendering and must remain lock-free.",
            Modifier.isSynchronized(uriGetter.modifiers)
        )
        assertFalse(
            "The active recovery flag is read during Compose rendering and must remain lock-free.",
            Modifier.isSynchronized(activeGetter.modifiers)
        )
    }

    @Test
    fun recoveryStoreReferenceUsesVolatileVisibilityForLockFreeReads() {
        val storeField = VaultRepository::class.java.getDeclaredField("persistentStore")
        assertTrue(
            "The lock-free recovery status getter requires a safely published store reference.",
            Modifier.isVolatile(storeField.modifiers)
        )
    }
}
