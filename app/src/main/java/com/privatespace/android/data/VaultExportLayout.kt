package com.privatespace.android.data

/**
 * Stable folder naming contract for user-visible vault exports.
 * All-topics exports are grouped as:
 * private-space-data/<topic name>/<original files>.
 */
data class VaultTopicExportFolder(
    val topicId: String,
    val folderName: String
)

object VaultExportLayout {
    const val ALL_TOPICS_ROOT_FOLDER_NAME = "private-space-data"

    fun topicFolder(topic: VaultTopic): VaultTopicExportFolder =
        VaultTopicExportFolder(topic.id, topic.name)

    /** Includes every topic, even topics that currently contain no files. */
    fun allTopicFolders(topics: List<VaultTopic>): List<VaultTopicExportFolder> =
        topics.map(::topicFolder)
}
