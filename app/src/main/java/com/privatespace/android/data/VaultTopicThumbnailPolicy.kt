package com.privatespace.android.data

import java.util.UUID

/** Validation and file-type rules for encrypted topic-thumbnail references. */
internal object VaultTopicThumbnailPolicy {
    fun isPhoto(file: VaultFile): Boolean =
        VaultFormatSupport.kindForFile(file.displayName, file.mimeType) ==
            VaultFormatSupport.FileKind.PHOTO


    fun isPreviewEligible(file: VaultFile): Boolean =
        isPhoto(file) && VaultThumbnailPolicy.shouldGeneratePreview(file.sizeBytes)

    /**
     * Validates gallery/source metadata before thumbnail import. A null size
     * means the provider did not report one; the encrypted stream's measured
     * byte count is checked again before the import is committed.
     */
    fun validatePhotoSource(displayName: String, mimeType: String, plainBytes: Long?) {
        require(
            VaultFormatSupport.kindForFile(displayName, mimeType) ==
                VaultFormatSupport.FileKind.PHOTO
        ) { "Choose an image file for the topic thumbnail." }
        if (plainBytes != null) {
            require(VaultThumbnailPolicy.shouldGeneratePreview(plainBytes)) {
                "Topic thumbnails support photos up to 16 MB so previews remain fast and memory-bounded."
            }
        }
    }
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
