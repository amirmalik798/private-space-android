package com.privatespace.android.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException

class PersistentVaultCryptoTest {
    @Test
    fun wrappedKeyCanBeRecoveredWithCorrectPassword() {
        val dataKey = PersistentVaultCrypto.newDataKey()
        val envelope = PersistentVaultCrypto.wrapDataKey(dataKey.copyOf(), "correct horse battery staple".toCharArray())
        val restored = PersistentVaultCrypto.unwrapDataKey(envelope, "correct horse battery staple".toCharArray())
        assertArrayEquals(dataKey, restored)
        assertNotEquals(dataKey.toList(), envelope.toList())
        dataKey.fill(0)
        restored.fill(0)
    }

    @Test
    fun wrongPasswordCannotUnwrapKey() {
        val dataKey = PersistentVaultCrypto.newDataKey()
        val envelope = PersistentVaultCrypto.wrapDataKey(dataKey.copyOf(), "correct horse battery staple".toCharArray())
        assertThrows(GeneralSecurityException::class.java) {
            PersistentVaultCrypto.unwrapDataKey(envelope, "wrong password".toCharArray())
        }
        dataKey.fill(0)
    }

    @Test
    fun encryptedPayloadRoundTripsExactBytesWithoutTransformation() {
        val input = ByteArray(1_200_123) { ((it * 37) % 251).toByte() }
        val aad = "private-space:file:test-id".toByteArray()
        val dataKey = PersistentVaultCrypto.newDataKey()
        val encrypted = ByteArrayOutputStream()
        PersistentVaultCrypto.encrypt(ByteArrayInputStream(input), encrypted, aad, dataKey)

        val restored = ByteArrayOutputStream()
        PersistentVaultCrypto.decrypt(ByteArrayInputStream(encrypted.toByteArray()), restored, aad, dataKey)
        assertArrayEquals(input, restored.toByteArray())
        assertEquals(input.size + 36, encrypted.size())
        dataKey.fill(0)
    }

    @Test
    fun shortEncryptedRecoveryIndexRoundTripsExactly() {
        val input = """{"version":1,"topics":[],"files":[],"trash":[]}""".toByteArray()
        val aad = "private-space:metadata:v2".toByteArray()
        val dataKey = PersistentVaultCrypto.newDataKey()
        val encrypted = ByteArrayOutputStream()

        PersistentVaultCrypto.encrypt(ByteArrayInputStream(input), encrypted, aad, dataKey)

        val restored = ByteArrayOutputStream()
        PersistentVaultCrypto.decrypt(ByteArrayInputStream(encrypted.toByteArray()), restored, aad, dataKey)

        assertArrayEquals(input, restored.toByteArray())
        assertEquals(input.size + 36, encrypted.size())
        dataKey.fill(0)
    }

    @Test
    fun modifiedAuthenticationContextIsRejected() {
        val input = "private content".toByteArray()
        val dataKey = PersistentVaultCrypto.newDataKey()
        val encrypted = ByteArrayOutputStream()
        PersistentVaultCrypto.encrypt(
            ByteArrayInputStream(input),
            encrypted,
            "file-a".toByteArray(),
            dataKey
        )
        assertThrows(java.io.IOException::class.java) {
            PersistentVaultCrypto.decrypt(
                ByteArrayInputStream(encrypted.toByteArray()),
                ByteArrayOutputStream(),
                "file-b".toByteArray(),
                dataKey
            )
        }
        dataKey.fill(0)
    }
}
