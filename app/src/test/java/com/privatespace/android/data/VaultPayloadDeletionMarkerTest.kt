package com.privatespace.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultPayloadDeletionMarkerTest {
    private val id = "00000000-0000-0000-0000-000000000123"

    @Test
    fun newMarkerUsesAnExplicitExtension() {
        assertTrue(VaultPayloadDeletionMarker.fileName(id).endsWith(".marker"))
    }

    @Test
    fun parsesCurrentAndPreviouslyUsedMarkerNames() {
        assertEquals(id, VaultPayloadDeletionMarker.parseId(VaultPayloadDeletionMarker.fileName(id)))
        assertEquals(id, VaultPayloadDeletionMarker.parseId("$id.psv.delete"))
        assertEquals(id, VaultPayloadDeletionMarker.parseId("$id.psv.delete.txt"))
    }

    @Test
    fun rejectsUnrelatedOrNonCanonicalMarkerNames() {
        assertNull(VaultPayloadDeletionMarker.parseId("not-a-uuid.psv.delete.marker"))
        assertNull(VaultPayloadDeletionMarker.parseId("$id.psv"))
        assertNull(VaultPayloadDeletionMarker.parseId("$id.psv.delete.backup"))
    }
}
