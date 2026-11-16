package com.privatespace.android.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultIndexSchemaTest {
    @Test
    fun currentReaderAcceptsBothLegacyAndCurrentIndexVersions() {
        assertTrue(VaultIndexSchema.isSupported(VaultIndexSchema.LEGACY_VERSION))
        assertTrue(VaultIndexSchema.isSupported(VaultIndexSchema.CURRENT_VERSION))
    }

    @Test
    fun legacySchemaIsUpgradedOnStartupButCurrentSchemaIsNotRewritten() {
        assertTrue(VaultIndexSchema.needsUpgrade(VaultIndexSchema.LEGACY_VERSION))
        assertFalse(VaultIndexSchema.needsUpgrade(VaultIndexSchema.CURRENT_VERSION))
    }

    @Test
    fun onlyCurrentSchemaRequiresTheTrashArray() {
        assertFalse(VaultIndexSchema.requiresTrashArray(VaultIndexSchema.LEGACY_VERSION))
        assertTrue(VaultIndexSchema.requiresTrashArray(VaultIndexSchema.CURRENT_VERSION))
    }

    @Test
    fun currentReaderRejectsUnknownVersionsInsteadOfRewritingThem() {
        assertFalse(VaultIndexSchema.isSupported(1))
        assertFalse(VaultIndexSchema.isSupported(4))
        assertFalse(VaultIndexSchema.isSupported(Int.MIN_VALUE))
    }
}
