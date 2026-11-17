package com.privatespace.android.data

/**
 * One in-place file operation status. This is deliberately a single operation
 * snapshot, not a persistent queue entry or a second list of work items.
 */
data class VaultFileOperationProgress(
    val phase: String,
    val completedBytes: Long,
    val totalBytes: Long,
    val completedFiles: Int = 0,
    val totalFiles: Int = 1,
    val currentFileName: String = ""
) {
    val fraction: Float
        get() = if (totalBytes <= 0L) 0f
        else (completedBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
}
