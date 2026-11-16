package com.privatespace.android.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Versioned AES-256-GCM file format.
 *
 * New records authenticate their purpose/record ID as AAD. Legacy records from
 * the first Private Space build (12-byte IV followed by ciphertext) remain
 * readable, but all newly written records use the versioned format.
 */
object VaultCrypto {
    private const val ALIAS = "private_space_aes_v1"
    private const val IV_BYTES = 12
    private const val BUFFER_SIZE = 64 * 1024
    private val MAGIC = byteArrayOf(0x50, 0x53, 0x56, 0x4c, 0x54, 0x30, 0x32, 0x0a)

    private fun existingKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return store.getKey(ALIAS, null) as? SecretKey
            ?: throw GeneralSecurityException(
                "The vault encryption key is unavailable. Do not clear app data or reinstall the app."
            )
    }

    private fun encryptionKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        if (store.containsAlias(ALIAS)) {
            throw GeneralSecurityException("The existing vault encryption key is invalid and was not replaced.")
        }

        val builder = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)

        // The key cannot be used while the Android device itself is locked.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setUnlockedDeviceRequired(true)
        }

        return KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        ).apply { init(builder.build()) }.generateKey()
    }

    /** Fails closed when existing encrypted files have lost their Keystore key. */
    fun requireExistingKey() {
        existingKey()
    }

    @Throws(IOException::class, GeneralSecurityException::class)
    fun encrypt(input: InputStream, output: OutputStream, aad: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        cipher.updateAAD(aad)

        val iv = cipher.iv
        require(iv.size == IV_BYTES) { "Unexpected AES-GCM nonce length." }
        output.write(MAGIC)
        output.write(iv)

        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            cipher.update(buffer, 0, count)?.let(output::write)
        }
        output.write(cipher.doFinal())
        output.flush()
    }

    /**
     * Authentication is completed by doFinal(). Callers must write to a
     * private temporary file and only expose it after this method succeeds.
     * That prevents unauthenticated partial plaintext from being opened/shared.
     */
    @Throws(IOException::class, GeneralSecurityException::class)
    fun decrypt(input: InputStream, output: OutputStream, aad: ByteArray) {
        val prefix = ByteArray(MAGIC.size)
        readExactly(input, prefix, 0, prefix.size)

        val versioned = prefix.contentEquals(MAGIC)
        val iv = ByteArray(IV_BYTES)
        if (versioned) {
            readExactly(input, iv, 0, iv.size)
        } else {
            // Backward compatibility with the original IV || ciphertext format.
            prefix.copyInto(iv, destinationOffset = 0)
            readExactly(input, iv, MAGIC.size, IV_BYTES - MAGIC.size)
        }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, existingKey(), GCMParameterSpec(128, iv))
        if (versioned) cipher.updateAAD(aad)

        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            cipher.update(buffer, 0, count)?.let(output::write)
        }

        try {
            output.write(cipher.doFinal())
            output.flush()
        } catch (badTag: AEADBadTagException) {
            throw IOException("Vault integrity check failed. The encrypted data may be damaged or modified.", badTag)
        }
    }

    private fun readExactly(input: InputStream, target: ByteArray, offset: Int, length: Int) {
        var position = 0
        while (position < length) {
            val count = input.read(target, offset + position, length - position)
            if (count < 0) throw EOFException("Encrypted record is truncated.")
            if (count == 0) continue
            position += count
        }
    }
}
