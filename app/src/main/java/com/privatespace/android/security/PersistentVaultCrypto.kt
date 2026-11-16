package com.privatespace.android.security

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter

/**
 * Cryptographic primitives for the uninstall-persistent recovery copy.
 *
 * The recovery key is random and independent from Android Keystore. Only its
 * password-wrapped envelope is stored in the user-selected shared directory.
 * Payload encryption uses bounded-memory, independently authenticated AES-GCM frames
 * and preserves source bytes. The previous single-tag recovery format remains readable.
 */
object PersistentVaultCrypto {
    private const val PBKDF2_ROUNDS = 600_000
    private const val MIN_SUPPORTED_ROUNDS = 600_000
    private const val MAX_SUPPORTED_ROUNDS = 2_000_000
    private const val KEY_BYTES = 32
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val TAG_BYTES = TAG_BITS / 8
    private const val BUFFER_BYTES = 64 * 1024

    private val ENVELOPE_MAGIC = "PSKEY001".toByteArray(Charsets.US_ASCII)
    private val LEGACY_PAYLOAD_MAGIC = "PSPERS01".toByteArray(Charsets.US_ASCII)
    private val CHUNKED_PAYLOAD_MAGIC = "PSPERS02".toByteArray(Charsets.US_ASCII)
    private val ENVELOPE_AAD = "private-space:recovery-key:v1".toByteArray(Charsets.UTF_8)

    fun newDataKey(): ByteArray = ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes)

    fun encryptedContainerLength(plainBytes: Long): Long = ChunkedAesGcm.encryptedLength(plainBytes)

    fun maximumEncryptedContainerLength(plainBytes: Int): Int =
        Math.toIntExact(encryptedContainerLength(plainBytes.toLong()))

    /**
     * Returns a portable envelope, not the raw key. The supplied password
     * array is always wiped before this method returns or throws.
     */
    fun wrapDataKey(dataKey: ByteArray, password: CharArray): ByteArray {
        try {
            require(dataKey.size == KEY_BYTES) { "Invalid recovery key size." }
            require(password.size in 1..128) { "Invalid vault password." }

            val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
            val nonce = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)
            val wrappingKey = deriveWrappingKey(password, salt, PBKDF2_ROUNDS)
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.ENCRYPT_MODE,
                    SecretKeySpec(wrappingKey, "AES"),
                    GCMParameterSpec(TAG_BITS, nonce)
                )
                cipher.updateAAD(ENVELOPE_AAD)
                val wrapped = cipher.doFinal(dataKey)
                return ENVELOPE_MAGIC +
                    intBytes(PBKDF2_ROUNDS) +
                    salt +
                    nonce +
                    wrapped
            } finally {
                wrappingKey.fill(0)
                salt.fill(0)
                nonce.fill(0)
            }
        } finally {
            password.fill('\u0000')
        }
    }

    /** Authenticates and unwraps a key envelope; wrong passwords fail closed. */
    @Throws(GeneralSecurityException::class, IOException::class)
    fun unwrapDataKey(envelope: ByteArray, password: CharArray): ByteArray {
        var salt: ByteArray? = null
        var nonce: ByteArray? = null
        var wrappingKey: ByteArray? = null
        var wrappedBytes: ByteArray? = null
        try {
            val expectedLength = ENVELOPE_MAGIC.size + 4 + SALT_BYTES + NONCE_BYTES + KEY_BYTES + 16
            require(envelope.size == expectedLength) { "The recovery key file is invalid or incomplete." }
            require(envelope.copyOfRange(0, ENVELOPE_MAGIC.size).contentEquals(ENVELOPE_MAGIC)) {
                "Unsupported recovery key format."
            }

            val roundsOffset = ENVELOPE_MAGIC.size
            val rounds = readInt(envelope, roundsOffset)
            require(rounds in MIN_SUPPORTED_ROUNDS..MAX_SUPPORTED_ROUNDS) {
                "Unsupported recovery key parameters."
            }

            var offset = roundsOffset + 4
            salt = envelope.copyOfRange(offset, offset + SALT_BYTES)
            offset += SALT_BYTES
            nonce = envelope.copyOfRange(offset, offset + NONCE_BYTES)
            offset += NONCE_BYTES
            val wrapped = envelope.copyOfRange(offset, envelope.size)
            wrappedBytes = wrapped

            require(password.size in 1..128) { "Invalid vault password." }
            wrappingKey = deriveWrappingKey(password, salt, rounds)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(wrappingKey, "AES"),
                GCMParameterSpec(TAG_BITS, nonce)
            )
            cipher.updateAAD(ENVELOPE_AAD)

            val clear = try {
                cipher.doFinal(wrapped)
            } catch (badTag: AEADBadTagException) {
                throw GeneralSecurityException("Incorrect password or damaged recovery key.", badTag)
            }
            if (clear.size != KEY_BYTES) {
                clear.fill(0)
                throw GeneralSecurityException("The recovery key has an invalid length.")
            }
            return clear
        } finally {
            password.fill('\u0000')
            salt?.fill(0)
            nonce?.fill(0)
            wrappingKey?.fill(0)
            wrappedBytes?.fill(0)
        }
    }

    /**
     * Streaming authenticated-encryption sink. This lets an already-encrypted
     * local record be decrypted directly into the recovery encryptor without
     * ever writing a plaintext staging file to disk.
     */
    class EncryptingOutputStream internal constructor(
        private val delegate: ChunkedAesGcm.EncryptingOutputStream
    ) : OutputStream() {
        override fun write(value: Int) = delegate.write(value)
        override fun write(bytes: ByteArray, offset: Int, length: Int) = delegate.write(bytes, offset, length)
        override fun flush() = delegate.flush()
        fun finish() = delegate.finish()
        fun abort() = delegate.abort()
        override fun close() = abort()
    }

    fun encryptingOutputStream(
        output: OutputStream,
        aad: ByteArray,
        dataKey: ByteArray
    ): EncryptingOutputStream {
        require(dataKey.size == KEY_BYTES) { "Invalid recovery key size." }
        val key = SecretKeySpec(dataKey.copyOf(), "AES")
        return EncryptingOutputStream(
            ChunkedAesGcm.encryptingOutputStream(output, aad, key, CHUNKED_PAYLOAD_MAGIC)
        )
    }

    fun encrypt(input: InputStream, output: OutputStream, aad: ByteArray, dataKey: ByteArray) {
        val encrypted = encryptingOutputStream(output, aad, dataKey)
        val buffer = ByteArray(BUFFER_BYTES)
        try {
            while (true) {
                val count = readChunkOrEof(input, buffer)
                if (count < 0) break
                try { encrypted.write(buffer, 0, count) } finally { buffer.fill(0, 0, count) }
            }
            encrypted.finish()
        } catch (failure: Throwable) {
            encrypted.abort()
            throw failure
        } finally {
            buffer.fill(0)
        }
    }

    /**
     * Authentication is completed before callers may promote the temporary
     * plaintext file to an active record or expose it to another application.
     */
    @Throws(IOException::class, GeneralSecurityException::class)
    fun decrypt(input: InputStream, output: OutputStream, aad: ByteArray, dataKey: ByteArray) {
        require(dataKey.size == KEY_BYTES) { "Invalid recovery key size." }
        val magic = ByteArray(8)
        readExactly(input, magic, 0, magic.size)
        val keyMaterial = dataKey.copyOf()
        try {
            val key = SecretKeySpec(keyMaterial, "AES")
            when {
                magic.contentEquals(CHUNKED_PAYLOAD_MAGIC) ->
                    ChunkedAesGcm.decryptAfterMagic(input, output, aad, key, CHUNKED_PAYLOAD_MAGIC)
                magic.contentEquals(LEGACY_PAYLOAD_MAGIC) ->
                    decryptLegacyPayload(input, output, aad, keyMaterial)
                else -> throw IOException("Unsupported recovery payload format.")
            }
        } finally {
            magic.fill(0)
            keyMaterial.fill(0)
        }
    }

    /**
     * Decrypts the previous single-tag recovery format with BC's lightweight
     * streaming GCM implementation. Some JCA providers buffer an entire GCM
     * decryption until doFinal(); this path keeps old multi-gigabyte backups
     * bounded to fixed-size input/output buffers as well.
     */
    private fun decryptLegacyPayload(input: InputStream, output: OutputStream, aad: ByteArray, key: ByteArray) {
        val nonce = ByteArray(NONCE_BYTES)
        val buffer = ByteArray(BUFFER_BYTES)
        val clearBuffer = ByteArray(BUFFER_BYTES + TAG_BYTES)
        try {
            readExactly(input, nonce, 0, nonce.size)
            val cipher = GCMBlockCipher(AESEngine.newInstance())
            cipher.init(
                false,
                AEADParameters(KeyParameter(key), TAG_BITS, nonce, aad)
            )
            while (true) {
                val count = readChunkOrEof(input, buffer)
                if (count < 0) break
                try {
                    val clearCount = cipher.processBytes(buffer, 0, count, clearBuffer, 0)
                    if (clearCount > 0) output.write(clearBuffer, 0, clearCount)
                    clearBuffer.fill(0, 0, clearCount)
                } finally {
                    buffer.fill(0, 0, count)
                }
            }
            val finalCount = try {
                cipher.doFinal(clearBuffer, 0)
            } catch (failure: InvalidCipherTextException) {
                throw IOException("Recovery vault integrity check failed.", failure)
            }
            if (finalCount > 0) output.write(clearBuffer, 0, finalCount)
            output.flush()
        } finally {
            nonce.fill(0)
            buffer.fill(0)
            clearBuffer.fill(0)
        }
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun deriveWrappingKey(password: CharArray, salt: ByteArray, rounds: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, rounds, KEY_BYTES * 8)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun intBytes(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte()
    )

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)

    private fun readChunkOrEof(input: InputStream, buffer: ByteArray): Int {
        val count = input.read(buffer, 0, buffer.size)
        if (count != 0) return count
        val single = input.read()
        if (single < 0) return -1
        buffer[0] = single.toByte()
        return 1
    }

    private fun readExactly(input: InputStream, target: ByteArray, offset: Int, length: Int) {
        var position = 0
        while (position < length) {
            val count = input.read(target, offset + position, length - position)
            when {
                count < 0 -> throw EOFException("Recovery vault record is truncated.")
                count == 0 -> {
                    val single = input.read()
                    if (single < 0) throw EOFException("Recovery vault record is truncated.")
                    target[offset + position] = single.toByte()
                    position++
                }
                else -> position += count
            }
        }
    }
}
