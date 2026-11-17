package com.privatespace.android.data

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VaultExportCleanupTest {
    @Test
    fun reportsFailureWhenProviderRefusesToDeleteExistingItem() {
        assertThrows(IOException::class.java) {
            VaultExportCleanup.deleteIfPresent(
                delete = { false },
                exists = { true }
            )
        }
    }

    @Test
    fun acceptsItemThatProviderAlreadyRemoved() {
        VaultExportCleanup.deleteIfPresent(
            delete = { false },
            exists = { false }
        )
    }

    @Test
    fun doesNotNeedAnExistenceQueryAfterSuccessfulDeletion() {
        var existenceChecks = 0

        VaultExportCleanup.deleteIfPresent(
            delete = { true },
            exists = {
                existenceChecks++
                true
            }
        )

        assertEquals(0, existenceChecks)
    }
}
