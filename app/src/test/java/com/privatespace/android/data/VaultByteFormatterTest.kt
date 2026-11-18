package com.privatespace.android.data

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class VaultByteFormatterTest {
    @Test
    fun formatsGigabytesUsingDecimalUnits() {
        assertEquals("1.96 GB", VaultByteFormatter.format(1_960_000_000L, Locale.US))
    }

    @Test
    fun oneGigabyteIsOneBillionBytes() {
        assertEquals("1.00 GB", VaultByteFormatter.format(1_000_000_000L, Locale.US))
    }

    @Test
    fun usesDecimalMegabytesAndKilobytesBelowOneGigabyte() {
        assertEquals("125.0 MB", VaultByteFormatter.format(125_000_000L, Locale.US))
        assertEquals("12.5 KB", VaultByteFormatter.format(12_500L, Locale.US))
    }

    @Test
    fun smallAndZeroValuesRemainReadable() {
        assertEquals("0 B", VaultByteFormatter.format(0L, Locale.US))
        assertEquals("999 B", VaultByteFormatter.format(999L, Locale.US))
    }
}
