package com.privatespace.android.data

data class VaultTopic(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** ID of an encrypted photo in this topic used as its visual cover. */
    val thumbnailFileId: String? = null
)

data class VaultFile(
    val id: String,
    val topicId: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val addedAt: Long
)

/** Transient in-memory preview; never stored in the encrypted metadata index. */
data class VaultMediaPreview(
    val bitmap: android.graphics.Bitmap?,
    val durationMs: Long? = null
)

data class TopicSummary(
    val topic: VaultTopic,
    val fileCount: Int,
    /** Actual encrypted container bytes stored for this topic. */
    val totalBytes: Long
)

data class VaultTrashEntry(
    val file: VaultFile,
    val deletedAt: Long,
    /** Topic metadata is retained so restoring a file also restores its original topic when needed. */
    val originalTopic: VaultTopic
)

data class VaultStorageUsage(
    /** Sum of actual encrypted file lengths, including files currently held in Trash. */
    val totalBytes: Long = 0L,
    /** Actual encrypted file lengths for files in active topics, indexed by topic ID. */
    val topicBytes: Map<String, Long> = emptyMap(),
    /** Actual encrypted file lengths currently held in Trash. */
    val trashBytes: Long = 0L
)

data class VaultRestoreProgress(
    val completedBytes: Long,
    val totalBytes: Long,
    val completedFiles: Int,
    val totalFiles: Int,
    val currentFileName: String,
    val phase: String = "Restoring encrypted files"
) {
    val fraction: Float
        get() = if (totalBytes <= 0L) {
            if (completedFiles >= totalFiles) 1f else 0f
        } else {
            (completedBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
        }
}

data class VaultSnapshot(
    val topics: List<VaultTopic>,
    val files: List<VaultFile>,
    val storageUsage: VaultStorageUsage,
    val trash: List<VaultTrashEntry> = emptyList()
)

/**
 * Aggregates actual encrypted-container lengths. Missing payloads count as
 * zero so the UI never estimates occupied storage from original file sizes.
 */
object VaultStorageCalculator {
    fun calculate(
        topics: List<VaultTopic>,
        files: List<VaultFile>,
        encryptedLengthsByFileId: Map<String, Long>,
        trash: List<VaultTrashEntry> = emptyList()
    ): VaultStorageUsage {
        val topicBytes = topics.associate { it.id to 0L }.toMutableMap()
        files.forEach { file ->
            val storedLength = (encryptedLengthsByFileId[file.id] ?: 0L).coerceAtLeast(0L)
            if (file.topicId in topicBytes) {
                topicBytes[file.topicId] = (topicBytes[file.topicId] ?: 0L) + storedLength
            }
        }
        val trashBytes = trash.sumOf {
            (encryptedLengthsByFileId[it.file.id] ?: 0L).coerceAtLeast(0L)
        }
        return VaultStorageUsage(
            totalBytes = topicBytes.values.sum() + trashBytes,
            topicBytes = topicBytes,
            trashBytes = trashBytes
        )
    }
}


/** Stable, timestamp-based ordering for files displayed inside a topic. */
object VaultFileOrdering {
    fun newestFirst(files: List<VaultFile>): List<VaultFile> =
        files.sortedWith(compareByDescending<VaultFile> { it.addedAt }.thenBy { it.id })

    fun oldestFirst(files: List<VaultFile>): List<VaultFile> =
        files.sortedWith(compareBy<VaultFile> { it.addedAt }.thenBy { it.id })
}
