package com.privatespace.android.data

/**
 * Names for the recovery-folder completion marker.
 *
 * Keep accepting the original extensionless name for backups created by older
 * app versions. New markers use an explicit extension because AndroidX
 * DocumentFile.createFile() may append one inferred from the MIME type when
 * the requested display name has no extension.
 */
internal object VaultRecoveryReadyMarker {
    const val FILE_NAME = "vault-ready.marker"
    const val LEGACY_FILE_NAME = "vault-ready"
    const val CONTENT = "PRIVATE_SPACE_RECOVERY_V1\n"

    fun matches(fileName: String?): Boolean =
        fileName == FILE_NAME || fileName == LEGACY_FILE_NAME
}
