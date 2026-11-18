package com.privatespace.android.data

import java.util.Locale

/** Formats byte counts using decimal SI units (1 GB = 1,000,000,000 bytes). */
internal object VaultByteFormatter {
    fun format(value: Long, locale: Locale = Locale.getDefault()): String {
        require(value >= 0L) { "Byte count cannot be negative." }
        return when {
            value < 1_000L -> "$value B"
            value < 1_000_000L -> String.format(locale, "%.1f KB", value / 1_000.0)
            value < 1_000_000_000L -> String.format(locale, "%.1f MB", value / 1_000_000.0)
            else -> String.format(locale, "%.2f GB", value / 1_000_000_000.0)
        }
    }
}
