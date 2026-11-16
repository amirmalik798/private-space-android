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
    fun successfulKeyUnwrapWipesTheSuppliedPassword() {
        val dataKey = PersistentVaultCrypto.newDataKey()
        val envelope = PersistentVaultCrypto.wrapDataKey(
            dataKey.copyOf(),
            "correct horse battery staple".toCharArray()
        )
        val password = "correct horse battery staple".toCharArray()

        val restored = PersistentVaultCrypto.unwrapDataKey(envelope, password)

        assertArrayEquals(dataKey, restored)
        assertArrayEquals(CharArray(password.size) { Char.MIN_VALUE }, password)
        dataKey.fill(0)
        restored.fill(0)
        envelope.fill(0)
    }

    @Test
    fun failedKeyUnwrapAlsoWipesTheSuppliedPassword() {
        val dataKey = PersistentVaultCrypto.newDataKey()
        val envelope = PersistentVaultCrypto.wrapDataKey(
            dataKey.copyOf(),
            "correct horse battery staple".toCharArray()
        )
        val password = "incorrect password".toCharArray()

        assertThrows(GeneralSecurityException::class.java) {
            PersistentVaultCrypto.unwrapDataKey(envelope, password)
        }

        assertArrayEquals(CharArray(password.size) { Char.MIN_VALUE }, password)
        dataKey.fill(0)
        envelope.fill(0)
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
        assertEquals(PersistentVaultCrypto.encryptedContainerLength(input.size.toLong()).toInt(), encrypted.size())
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
        assertEquals(PersistentVaultCrypto.encryptedContainerLength(input.size.toLong()).toInt(), encrypted.size())
        dataKey.fill(0)
    }

    @Test
    fun recoveryPayloadEncryptionUsesFreshExplicitNoncesAndRoundTrips() {
        val input = """{"version":3,"topics":[],"files":[],"trash":[]}""".toByteArray()
        val aad = "private-space:metadata:v2".toByteArray()
        val dataKey = PersistentVaultCrypto.newDataKey()
        val originalKey = dataKey.copyOf()
        val first = ByteArrayOutputStream()
        val second = ByteArrayOutputStream()

        PersistentVaultCrypto.encrypt(ByteArrayInputStream(input), first, aad, dataKey)
        PersistentVaultCrypto.encrypt(ByteArrayInputStream(input), second, aad, dataKey)

        val firstBytes = first.toByteArray()
        val secondBytes = second.toByteArray()
        // Container format: 8-byte magic followed by the 12-byte GCM nonce.
        assertNotEquals(
            firstBytes.copyOfRange(12, 24).toList(),
            secondBytes.copyOfRange(12, 24).toList()
        )
        assertArrayEquals(originalKey, dataKey)

        for (encrypted in listOf(firstBytes, secondBytes)) {
            val restored = ByteArrayOutputStream()
            PersistentVaultCrypto.decrypt(ByteArrayInputStream(encrypted), restored, aad, dataKey)
            assertArrayEquals(input, restored.toByteArray())
            encrypted.fill(0)
        }
        dataKey.fill(0)
        originalKey.fill(0)
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
