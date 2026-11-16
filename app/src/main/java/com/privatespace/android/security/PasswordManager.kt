package com.privatespace.android.security

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object PasswordManager {
    private const val PREFS = "private_space_auth"
    private const val CURRENT_ROUNDS = 600_000
    private const val LEGACY_ROUNDS = 310_000
    private const val CORRUPT_LOCKOUT_MS = 600_000L
    private const val MISSING_LOCK_VALUE = Long.MIN_VALUE
    const val MIN_PASSWORD_LENGTH = 10
    const val MAX_PASSWORD_LENGTH = 128

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun configured(context: Context): Boolean {
        val store = prefs(context)
        return store.contains("salt") && store.contains("hash")
    }

    private fun derive(password: CharArray, salt: ByteArray, rounds: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, rounds, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun set(context: Context, password: CharArray) {
        try {
            require(password.size in MIN_PASSWORD_LENGTH..MAX_PASSWORD_LENGTH) {
                "Use a password between $MIN_PASSWORD_LENGTH and $MAX_PASSWORD_LENGTH characters."
            }
            val salt = ByteArray(16).also(SecureRandom()::nextBytes)
            val hash = derive(password, salt, CURRENT_ROUNDS)
            try {
                check(
                    prefs(context).edit()
                        .putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
                        .putString("hash", Base64.encodeToString(hash, Base64.NO_WRAP))
                        .putInt("rounds", CURRENT_ROUNDS)
                        .putInt("failures", 0)
                        .putLong("lock_until", 0L)
                        .remove("lock")
                        .commit()
                ) { "Could not save the password configuration." }
            } finally {
                salt.fill(0)
                hash.fill(0)
            }
        } finally {
            password.fill('\u0000')
        }
    }

    fun verify(context: Context, password: CharArray): Boolean {
        var salt: ByteArray? = null
        var expected: ByteArray? = null
        var actual: ByteArray? = null
        try {
            if (password.size !in 1..MAX_PASSWORD_LENGTH) return false
            val store = prefs(context)
            val encodedSalt = store.getString("salt", null) ?: return false
            val encodedHash = store.getString("hash", null) ?: return false
            salt = Base64.decode(encodedSalt, Base64.NO_WRAP)
            expected = Base64.decode(encodedHash, Base64.NO_WRAP)
            if (salt.size != 16 || expected.size != 32) return false

            // Older installs did not persist the iteration count.
            val rounds = store.getInt("rounds", LEGACY_ROUNDS)
            if (rounds !in LEGACY_ROUNDS..CURRENT_ROUNDS) return false

            actual = derive(password, salt, rounds)
            return MessageDigest.isEqual(expected, actual)
        } catch (_: Exception) {
            return false
        } finally {
            password.fill('\u0000')
            salt?.fill(0)
            expected?.fill(0)
            actual?.fill(0)
        }
    }

    fun lockedUntil(context: Context): Long {
        val store = prefs(context)
        val current = runCatching {
            store.getLong("lock_until", MISSING_LOCK_VALUE)
        }.getOrNull() ?: return System.currentTimeMillis() + CORRUPT_LOCKOUT_MS
        if (current != MISSING_LOCK_VALUE) return current.coerceAtLeast(0L)

        val legacy = runCatching {
            store.getLong("lock", MISSING_LOCK_VALUE)
        }.getOrNull() ?: return System.currentTimeMillis() + CORRUPT_LOCKOUT_MS
        return if (legacy == MISSING_LOCK_VALUE) 0L else legacy.coerceAtLeast(0L)
    }

    internal fun nextFailureCount(storedCount: Int?): Int {
        // If the stored type/value is corrupted, assume the most restrictive
        // prior count instead of resetting the persisted brute-force backoff.
        val previous = storedCount?.takeIf { it >= 0 }?.coerceAtMost(999_999) ?: 10
        return (previous + 1).coerceAtMost(1_000_000)
    }

    /** Persisted lockout: 30 seconds after attempt 5, 2 minutes after 8, 10 minutes after 11. */
    fun recordFailure(context: Context): Long {
        val store = prefs(context)
        val storedCount = runCatching { store.getInt("failures", 0) }.getOrNull()
        val count = nextFailureCount(storedCount)
        val delay = when {
            count < 5 -> 0L
            count < 8 -> 30_000L
            count < 11 -> 120_000L
            else -> 600_000L
        }
        val until = System.currentTimeMillis() + delay
        check(
            store.edit()
                .putInt("failures", count)
                .putLong("lock_until", until)
                .commit()
        ) { "Could not persist authentication lockout." }
        return until
    }

    fun clearFailures(context: Context) {
        check(
            prefs(context).edit()
                .putInt("failures", 0)
                .putLong("lock_until", 0L)
                .remove("lock")
                .commit()
        ) { "Could not reset authentication state." }
    }
}
