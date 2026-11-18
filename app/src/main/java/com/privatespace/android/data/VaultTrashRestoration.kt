package com.privatespace.android.data

/**
 * Resolves the destination topic for a restored file.
 *
 * A topic that was deleted together with its files is recreated with its
 * original identity when possible. If the thumbnail photo is the item being
 * restored, its reference is restored too; otherwise a newly recreated topic
 * starts without a thumbnail until that photo is restored.
 */
object VaultTrashRestoration {
    fun topicFor(
        entry: VaultTrashEntry,
        activeTopics: List<VaultTopic>,
        restoredAt: Long
    ): VaultTopic {
        val restoresThumbnail = entry.originalTopic.thumbnailFileId == entry.file.id &&
            VaultTopicThumbnailPolicy.isPhoto(entry.file)

        val sameId = activeTopics.firstOrNull { it.id == entry.originalTopic.id }
        if (sameId != null) {
            return if (restoresThumbnail && sameId.thumbnailFileId == null) {
                sameId.copy(updatedAt = restoredAt, thumbnailFileId = entry.file.id)
            } else {
                sameId
            }
        }

        val sameName = activeTopics.firstOrNull {
            it.name.equals(entry.originalTopic.name, ignoreCase = true)
        }
        if (sameName != null) {
            return if (restoresThumbnail && sameName.thumbnailFileId == null) {
                sameName.copy(updatedAt = restoredAt, thumbnailFileId = entry.file.id)
            } else {
                sameName
            }
        }

        return entry.originalTopic.copy(
            updatedAt = restoredAt,
            thumbnailFileId = entry.originalTopic.thumbnailFileId.takeIf { restoresThumbnail }
        )
    }

    /**
     * When a thumbnail photo is restored into a same-name replacement topic,
     * its file is assigned the replacement topic ID. Other Trash snapshots
     * must stop referring to that photo under the old topic ID.
     */
    fun reconcileRemainingEntries(
        remainingEntries: List<VaultTrashEntry>,
        restoredEntry: VaultTrashEntry,
        destinationTopic: VaultTopic
    ): List<VaultTrashEntry> {
        val originalTopic = restoredEntry.originalTopic
        val restoredPhotoIsThumbnail =
            originalTopic.thumbnailFileId == restoredEntry.file.id &&
                VaultTopicThumbnailPolicy.isPhoto(restoredEntry.file)
        if (!restoredPhotoIsThumbnail || destinationTopic.id == originalTopic.id) {
            return remainingEntries
        }
        return remainingEntries.map { entry ->
            if (
                entry.originalTopic.id == originalTopic.id &&
                entry.originalTopic.thumbnailFileId == restoredEntry.file.id
            ) {
                entry.copy(
                    originalTopic = entry.originalTopic.copy(thumbnailFileId = null)
                )
            } else {
                entry
            }
        }
    }
}
