package com.privatespace.android.data

/**
 * Encrypted vault-index schema compatibility.
 *
 * Version 2 is the existing topic/file schema. Version 3 adds encrypted Trash
 * records. Version 3 readers keep accepting version 2 indexes so existing
 * installs can upgrade without rebuilding or re-encrypting user files.
 */
object VaultIndexSchema {
    const val LEGACY_VERSION = 2
    const val CURRENT_VERSION = 3

    fun isSupported(version: Int): Boolean =
        version == LEGACY_VERSION || version == CURRENT_VERSION

    fun needsUpgrade(version: Int): Boolean =
        version == LEGACY_VERSION
}
