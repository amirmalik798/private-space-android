package com.privatespace.android.data

/**
 * Rejects a completed stream when a document provider supplied a concrete
 * size that does not match the bytes actually read.
 */
object VaultImportIntegrity {
    fun validateByteCount(reportedSize: Long?, actualBytes: Long) {
        require(actualBytes >= 0L) { "The imported file has an invalid byte count." }
        if (reportedSize != null) {
            require(reportedSize >= 0L) { "The document provider reported an invalid file size." }
            check(reportedSize == actualBytes) {
                "The selected file could not be read completely or changed during import. Nothing was added; select it again and retry."
            }
        }
    }
}
