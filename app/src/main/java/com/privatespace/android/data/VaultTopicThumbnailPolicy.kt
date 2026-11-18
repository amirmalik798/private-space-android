package com.privatespace.android.data

import java.util.UUID

/** Validation and file-type rules for encrypted topic-thumbnail references. */
internal object VaultTopicThumbnailPolicy {
    fun isPhoto(file: VaultFile): Boolean =
        VaultFormatSupport.kindForFile(file.displayName, file.mimeType) ==
            VaultFormatSupport.FileKind.PHOTO

    fun validate(
        topics: List<VaultTopic>,
        files: List<VaultFile>,
        trash: List<VaultTrashEntry>
    ) {
        val activeFilesById = files.associateBy { it.id }

        topics.forEach { topic ->
            val thumbnailId = topic.thumbnailFileId ?: return@forEach
            UUID.fromString(thumbnailId)
            val thumbnail = activeFilesById[thumbnailId]
                ?: throw IllegalArgumentException("A topic thumbnail references a missing active file.")
            require(thumbnail.topicId == topic.id && isPhoto(thumbnail)) {
                "A topic thumbnail must reference a photo saved in that same topic."
            }
        }

        trash.forEach { entry ->
            val thumbnailId = entry.originalTopic.thumbnailFileId ?: return@forEach
            UUID.fromString(thumbnailId)
            val activeThumbnail = activeFilesById[thumbnailId]
                ?.takeIf { it.topicId == entry.originalTopic.id && isPhoto(it) }
            val trashedThumbnail = trash.firstOrNull {
                it.file.id == thumbnailId &&
                    it.originalTopic.id == entry.originalTopic.id &&
                    isPhoto(it.file)
            }?.file
            require(activeThumbnail != null || trashedThumbnail != null) {
                "A Recycle Bin topic thumbnail references a missing photo."
            }
        }
    }
}
