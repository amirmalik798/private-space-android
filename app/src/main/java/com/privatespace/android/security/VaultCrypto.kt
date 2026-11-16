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
 * Byte-preserving, versioned AES-256-GCM container.
 *
 * The clear file payload is never decoded as an image/document, resized,
 * recompressed, normalized, or transcoded. AES-GCM encrypts the exact input
 * bytes and restores those exact bytes during decryption.
 *
 * Container layout for new records:
 *   8-byte magic | 12-byte random nonce | original bytes as ciphertext | 16-byte GCM tag
 *
 * Therefore encrypted storage uses exactly 36 bytes of container overhead.
 * Legacy records (12-byte nonce followed by ciphertext/tag) remain readable.
 */
object VaultCrypto {
    private const val ALIAS = "private_space_aes_v1"
    private const val IV_BYTES = 12
    private const val TAG_BYTES = 16
    private const val BUFFER_SIZE = 64 * 1024
    const val VERSIONED_CONTAINER_OVERHEAD_BYTES = 8 + IV_BYTES + TAG_BYTES

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
        encryptWithKey(input, output, aad, encryptionKey())
    }

    @Throws(IOException::class, GeneralSecurityException::class)
    fun decrypt(input: InputStream, output: OutputStream, aad: ByteArray) {
        decryptWithKey(input, output, aad, existingKey())
    }

    /**
     * Shared cryptographic path used by JVM tests with an ephemeral test key.
     * It is internal and does not alter the production Android Keystore path.
     */
    @Throws(IOException::class, GeneralSecurityException::class)
    internal fun encryptForTest(
        input: InputStream,
        output: OutputStream,
        aad: ByteArray,
        testKey: SecretKey
    ) {
        encryptWithKey(input, output, aad, testKey)
    }

    @Throws(IOException::class, GeneralSecurityException::class)
    internal fun decryptForTest(
        input: InputStream,
        output: OutputStream,
        aad: ByteArray,
        testKey: SecretKey
    ) {
        decryptWithKey(input, output, aad, testKey)
    }

    private fun encryptWithKey(
        input: InputStream,
        output: OutputStream,
        aad: ByteArray,
        key: SecretKey
    ) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(aad)

        val nonce = cipher.iv
        require(nonce.size == IV_BYTES) { "Unexpected AES-GCM nonce length." }

        output.write(MAGIC)
        output.write(nonce)

        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            cipher.update(buffer, 0, count)?.let { encrypted ->
                output.write(encrypted)
            }
        }

        // GCM appends its authentication tag here. It does not pad, compress,
        // or otherwise change the length of the original payload.
        output.write(cipher.doFinal())
        output.flush()
    }

    /**
     * The GCM authentication tag is checked by doFinal(). Callers must write
     * into a private temporary file and only expose that file after this method
     * succeeds, so unauthenticated partial plaintext can never be opened/shared.
     */
    private fun decryptWithKey(
        input: InputStream,
        output: OutputStream,
        aad: ByteArray,
        key: SecretKey
    ) {
        val prefix = ByteArray(MAGIC.size)
        readExactly(input, prefix, 0, prefix.size)

        val versioned = prefix.contentEquals(MAGIC)
        val nonce = ByteArray(IV_BYTES)
        if (versioned) {
            readExactly(input, nonce, 0, nonce.size)
        } else {
            // Backward compatibility with the original nonce || ciphertext format.
            prefix.copyInto(nonce, destinationOffset = 0)
            readExactly(input, nonce, MAGIC.size, IV_BYTES - MAGIC.size)
        }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, nonce))
        if (versioned) cipher.updateAAD(aad)

        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            cipher.update(buffer, 0, count)?.let { clear ->
                output.write(clear)
            }
        }

        try {
            output.write(cipher.doFinal())
            output.flush()
        } catch (badTag: AEADBadTagException) {
            throw IOException(
                "Vault integrity check failed. The encrypted data may be damaged or modified.",
                badTag
            )
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
