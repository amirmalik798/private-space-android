package com.privatespace.android.data

import java.io.IOException

/**
 * SAF providers can refuse to delete an item without throwing. Treat a still
 * existing destination as a cleanup failure instead of silently leaving
 * incomplete plaintext export data behind.
 */
internal object VaultExportCleanup {
    fun deleteIfPresent(
        delete: () -> Boolean,
        exists: () -> Boolean
    ) {
        if (!delete() && exists()) {
            throw IOException("The storage provider refused to remove an incomplete export item.")
        }
    }
}
