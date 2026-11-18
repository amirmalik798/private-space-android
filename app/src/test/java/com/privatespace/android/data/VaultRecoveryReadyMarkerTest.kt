package com.privatespace.android.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultRecoveryReadyMarkerTest {
    @Test
    fun newMarkerNameHasAnExplicitExtensionForSafProviders() {
        assertTrue(VaultRecoveryReadyMarker.FILE_NAME.endsWith(".marker"))
    }

    @Test
    fun recognizesBothCurrentAndLegacyMarkerNames() {
        assertTrue(VaultRecoveryReadyMarker.matches(VaultRecoveryReadyMarker.FILE_NAME))
        assertTrue(VaultRecoveryReadyMarker.matches(VaultRecoveryReadyMarker.LEGACY_FILE_NAME))
    }

    @Test
    fun doesNotTreatProviderRenamedFilesOrLookalikesAsValidMarkers() {
        assertFalse(VaultRecoveryReadyMarker.matches("vault-ready.txt"))
        assertFalse(VaultRecoveryReadyMarker.matches("vault-ready.marker.txt"))
        assertFalse(VaultRecoveryReadyMarker.matches("other.marker"))
        assertFalse(VaultRecoveryReadyMarker.matches(null))
    }
}
