package com.privatespace.android.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultIndexSchemaTest {
    @Test
    fun currentReaderAcceptsLegacyTrashAndThumbnailIndexVersions() {
        assertTrue(VaultIndexSchema.isSupported(VaultIndexSchema.LEGACY_VERSION))
        assertTrue(VaultIndexSchema.isSupported(VaultIndexSchema.TRASH_VERSION))
        assertTrue(VaultIndexSchema.isSupported(VaultIndexSchema.CURRENT_VERSION))
    }

    @Test
    fun supportedOlderSchemasAreUpgradedButCurrentSchemaIsNotRewritten() {
        assertTrue(VaultIndexSchema.needsUpgrade(VaultIndexSchema.LEGACY_VERSION))
        assertTrue(VaultIndexSchema.needsUpgrade(VaultIndexSchema.TRASH_VERSION))
        assertFalse(VaultIndexSchema.needsUpgrade(VaultIndexSchema.CURRENT_VERSION))
    }

    @Test
    fun bothTrashAndThumbnailSchemasRequireTheTrashArray() {
        assertFalse(VaultIndexSchema.requiresTrashArray(VaultIndexSchema.LEGACY_VERSION))
        assertTrue(VaultIndexSchema.requiresTrashArray(VaultIndexSchema.TRASH_VERSION))
        assertTrue(VaultIndexSchema.requiresTrashArray(VaultIndexSchema.CURRENT_VERSION))
    }

    @Test
    fun currentReaderRejectsUnknownVersionsInsteadOfRewritingThem() {
        assertFalse(VaultIndexSchema.isSupported(1))
        assertFalse(VaultIndexSchema.isSupported(5))
        assertFalse(VaultIndexSchema.isSupported(Int.MIN_VALUE))
    }
}
