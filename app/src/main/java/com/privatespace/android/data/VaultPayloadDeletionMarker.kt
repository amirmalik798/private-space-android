package com.privatespace.android.data

import java.util.UUID

/**
 * SAF cleanup tombstone names for encrypted recovery payloads.
 *
 * New names have an explicit extension and are created as binary documents.
 * Legacy names are still recognized, including the ".txt" variant some
 * document providers may have appended to the earlier text/plain marker.
 */
internal object VaultPayloadDeletionMarker {
    private const val CURRENT_SUFFIX = ".psv.delete.marker"
    private const val LEGACY_SUFFIX = ".psv.delete"
    private const val LEGACY_TEXT_SUFFIX = ".psv.delete.txt"

    fun fileName(id: String): String = "$id$CURRENT_SUFFIX"

    fun fileNames(id: String): List<String> = listOf(
        fileName(id),
        "$id$LEGACY_SUFFIX",
        "$id$LEGACY_TEXT_SUFFIX"
    )

    fun parseId(name: String): String? {
        val id = when {
            name.endsWith(CURRENT_SUFFIX) -> name.removeSuffix(CURRENT_SUFFIX)
            name.endsWith(LEGACY_TEXT_SUFFIX) -> name.removeSuffix(LEGACY_TEXT_SUFFIX)
            name.endsWith(LEGACY_SUFFIX) -> name.removeSuffix(LEGACY_SUFFIX)
            else -> return null
        }
        val canonical = runCatching { UUID.fromString(id).toString() }.getOrNull()
        return canonical?.takeIf { it == id }
    }
}
