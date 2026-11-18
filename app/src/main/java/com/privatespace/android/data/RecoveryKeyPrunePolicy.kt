package com.privatespace.android.data

/**
 * Keeps the newest two recovery-key envelopes plus the exact generation that
 * successfully unwrapped the active recovery data key. Including the active
 * generation prevents cleanup from deleting the only envelope known to work.
 */
internal object RecoveryKeyPrunePolicy {
    fun generationsToRetain(
        availableGenerations: List<Long>,
        activeGeneration: Long
    ): Set<Long> {
        require(activeGeneration > 0L) { "The active recovery-key generation is invalid." }
        require(availableGenerations.all { it > 0L }) {
            "The recovery folder contains an invalid key-envelope generation."
        }
        require(availableGenerations.distinct().size == availableGenerations.size) {
            "The recovery folder contains duplicate key-envelope generations."
        }
        require(activeGeneration in availableGenerations) {
            "The active recovery-key envelope is missing."
        }
        return (availableGenerations.sortedDescending().take(2) + activeGeneration).toSet()
    }
}
