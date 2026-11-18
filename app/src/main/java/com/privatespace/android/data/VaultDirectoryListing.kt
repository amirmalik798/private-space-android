package com.privatespace.android.data

import java.io.File
import java.io.IOException

/**
 * Fail-closed enumeration for app-private directories that may contain
 * plaintext staging data or crash-recovery transaction files.
 *
 * java.io.File.listFiles() returns null when the directory cannot be read.
 * Treating that value as an empty directory can silently skip cleanup and
 * expose an incomplete vault state, so callers must stop and retry instead.
 */
internal object VaultDirectoryListing {
    fun listChildren(directory: File, description: String): Array<File> {
        if (!directory.isDirectory) {
            throw IOException("The $description directory is unavailable.")
        }
        return try {
            directory.listFiles()
                ?: throw IOException("Could not inspect the $description directory safely.")
        } catch (failure: SecurityException) {
            throw IOException("Could not inspect the $description directory safely.", failure)
        }
    }
}
