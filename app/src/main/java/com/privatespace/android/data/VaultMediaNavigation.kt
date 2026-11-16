package com.privatespace.android.data

/**
 * Navigation rules for the in-app media viewer.
 *
 * Documents are deliberately filtered before navigation so a gallery swipe
 * always selects the closest photo/video in the same topic.
 */
object VaultMediaNavigation {
    fun mediaFilesInTopic(files: List<VaultFile>, topicId: String): List<VaultFile> =
        files.filter { file ->
            file.topicId == topicId &&
                (VaultFormatSupport.isImageMimeType(file.mimeType) ||
                    VaultFormatSupport.isVideoMimeType(file.mimeType))
        }

    /**
     * Returns the adjacent media item, or null at a boundary / for an invalid
     * direction. The supplied list should already be scoped to one topic.
     */
    fun adjacent(
        mediaFiles: List<VaultFile>,
        currentFileId: String,
        direction: Int
    ): VaultFile? {
        if (direction != -1 && direction != 1) return null
        val currentIndex = mediaFiles.indexOfFirst { it.id == currentFileId }
        if (currentIndex < 0) return null
        return mediaFiles.getOrNull(currentIndex + direction)
    }
}
