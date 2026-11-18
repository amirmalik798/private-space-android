package com.privatespace.android.data

/**
 * Encrypted vault-index schema compatibility.
 *
 * Version 2 is the original topic/file schema. Version 3 adds encrypted Trash
 * records. Version 4 adds optional topic-thumbnail references. Older versions
 * remain readable so upgrades never require rebuilding or re-encrypting files.
 */
object VaultIndexSchema {
    const val LEGACY_VERSION = 2
    const val TRASH_VERSION = 3
    const val CURRENT_VERSION = 4

    fun isSupported(version: Int): Boolean =
        version == LEGACY_VERSION || version == TRASH_VERSION || version == CURRENT_VERSION

    fun needsUpgrade(version: Int): Boolean =
        isSupported(version) && version != CURRENT_VERSION

    fun requiresTrashArray(version: Int): Boolean =
        version >= TRASH_VERSION && isSupported(version)
}
