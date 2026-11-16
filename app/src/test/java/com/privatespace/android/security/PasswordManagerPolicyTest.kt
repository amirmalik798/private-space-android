package com.privatespace.android.security

import org.junit.Assert.assertEquals
import org.junit.Test

class PasswordManagerPolicyTest {
    @Test
    fun failureCountAdvancesNormally() {
        assertEquals(1, PasswordManager.nextFailureCount(0))
        assertEquals(5, PasswordManager.nextFailureCount(4))
        assertEquals(11, PasswordManager.nextFailureCount(10))
    }

    @Test
    fun malformedFailureCountUsesConservativeLockoutThreshold() {
        assertEquals(11, PasswordManager.nextFailureCount(null))
        assertEquals(11, PasswordManager.nextFailureCount(-1))
    }

    @Test
    fun failureCountNeverOverflowsItsPersistedRange() {
        assertEquals(1_000_000, PasswordManager.nextFailureCount(1_000_000))
        assertEquals(1_000_000, PasswordManager.nextFailureCount(Int.MAX_VALUE))
    }
}
