package com.privatespace.android.data

import java.io.IOException

/**
 * Signals that a valid on-device vault and a valid recovery vault contain
 * different records. The local vault must remain untouched until the user
 * explicitly chooses which copy to restore.
 */
internal class VaultRecoveryConflictException(message: String) : IOException(message)

/** Pure decision used when opening an existing recovery mirror. */
internal object VaultRecoveryOpenPolicy {
    fun shouldBlockAutomaticRestore(
        hasLocalIndex: Boolean,
        localIndexReadable: Boolean,
        localContentMatchesRecovery: Boolean
    ): Boolean =
        hasLocalIndex && localIndexReadable && !localContentMatchesRecovery
}
