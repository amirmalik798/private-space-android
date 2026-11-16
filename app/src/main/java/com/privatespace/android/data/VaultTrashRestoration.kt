package com.privatespace.android.data

/**
 * Resolves the destination topic for a restored file.
 *
 * A topic that was deleted together with its files is recreated with its
 * original identity when possible. If the user has already recreated a topic
 * with the same name, the restored file joins that topic instead.
 */
object VaultTrashRestoration {
    fun topicFor(
        entry: VaultTrashEntry,
        activeTopics: List<VaultTopic>,
        restoredAt: Long
    ): VaultTopic {
        return activeTopics.firstOrNull { it.id == entry.originalTopic.id }
            ?: activeTopics.firstOrNull {
                it.name.equals(entry.originalTopic.name, ignoreCase = true)
            }
            ?: entry.originalTopic.copy(updatedAt = restoredAt)
    }
}
