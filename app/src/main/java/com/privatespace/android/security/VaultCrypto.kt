package com.privatespace.android.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
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
 * New records use independently authenticated 64 KiB AES-GCM frames and an
 * authenticated terminal frame. This keeps decryption memory bounded regardless
 * of file size while detecting truncation, reordering and trailing data.
 * Previous versioned and legacy single-tag containers remain readable.
 */
object VaultCrypto {
    private const val ALIAS = "private_space_aes_v1"
    private const val IV_BYTES = 12
    private const val TAG_BYTES = 16
    private const val BUFFER_SIZE = 64 * 1024
    const val VERSIONED_CONTAINER_OVERHEAD_BYTES = 8 + IV_BYTES + TAG_BYTES
    const val MINIMUM_CHUNKED_CONTAINER_OVERHEAD_BYTES = 8 + 4 + IV_BYTES + TAG_BYTES
    fun encryptedContainerLength(plainBytes: Long): Long = ChunkedAesGcm.encryptedLength(plainBytes)

    private val LEGACY_MAGIC = byteArrayOf(0x50, 0x53, 0x56, 0x4c, 0x54, 0x30, 0x32, 0x0a)
    private val CHUNKED_MAGIC = "PSVLT03\n".toByteArray(Charsets.US_ASCII)

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
     * Random-access authenticated reader for current-format encrypted media.
     * It authenticates the terminal frame up front and every data frame on read.
     * Returns null for legacy containers, which must use the bounded full-decrypt
     * compatibility path rather than being interpreted as chunked data.
     */
    class ChunkedRandomAccessReader internal constructor(
        private val delegate: ChunkedAesGcm.RandomAccessReader,
        val sizeBytes: Long
    ) : AutoCloseable {
        fun readAt(position: Long, target: ByteArray, offset: Int, length: Int): Int =
            delegate.readAt(position, target, offset, length)
        override fun close() = delegate.close()
    }

    fun openChunkedRandomAccessReader(
        file: RandomAccessFile,
        plainBytes: Long,
        aad: ByteArray
    ): ChunkedRandomAccessReader? = openChunkedRandomAccessReaderWithKey(
        file, plainBytes, aad, existingKey()
    )

    private fun openChunkedRandomAccessReaderWithKey(
        file: RandomAccessFile,
        plainBytes: Long,
        aad: ByteArray,
        key: SecretKey
    ): ChunkedRandomAccessReader? {
        val marker = ByteArray(CHUNKED_MAGIC.size)
        try {
            file.seek(0L)
            try {
                file.readFully(marker)
            } catch (_: java.io.EOFException) {
                return null
            }
            if (!marker.contentEquals(CHUNKED_MAGIC)) return null
            return ChunkedRandomAccessReader(
                ChunkedAesGcm.openRandomAccessReader(file, plainBytes, aad, key, CHUNKED_MAGIC),
                plainBytes
            )
        } catch (failure: Throwable) {
            runCatching { file.close() }
            throw failure
        } finally {
            marker.fill(0)
        }
    }

    internal fun openChunkedRandomAccessReaderForTest(
        file: RandomAccessFile,
        plainBytes: Long,
        aad: ByteArray,
        testKey: SecretKey
    ): ChunkedRandomAccessReader? = openChunkedRandomAccessReaderWithKey(
        file, plainBytes, aad, testKey
    )

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

    /**
     * Streaming sink for restoring an authenticated recovery record directly
     * into the local Keystore-encrypted format. Call finish() only after the
     * upstream recovery decryptor has verified its GCM authentication tag.
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
        aad: ByteArray
    ): EncryptingOutputStream =
        createEncryptingOutputStream(output, aad, encryptionKey())

    internal fun encryptingOutputStreamForTest(
        output: OutputStream,
        aad: ByteArray,
        testKey: SecretKey
    ): EncryptingOutputStream =
        createEncryptingOutputStream(output, aad, testKey)

    private fun createEncryptingOutputStream(
        output: OutputStream,
        aad: ByteArray,
        key: SecretKey
    ): EncryptingOutputStream =
        EncryptingOutputStream(ChunkedAesGcm.encryptingOutputStream(output, aad, key, CHUNKED_MAGIC))

    /**
     * Import variant that reports exact plaintext bytes and observes user
     * controls between bounded chunks. The destination must remain a private
     * temporary file until finish() and the caller's size check both succeed.
     */
    @Throws(IOException::class, GeneralSecurityException::class)
    fun encryptControlled(
        input: InputStream,
        output: OutputStream,
        aad: ByteArray,
        control: VaultImportControl,
        onProgress: (Long) -> Unit
    ) {
        val encrypting = encryptingOutputStream(output, aad)
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        try {
            while (true) {
                control.awaitRunning()
                val count = readChunkOrEof(input, buffer)
                if (count < 0) break
                try {
                    encrypting.write(buffer, 0, count)
                    total = Math.addExact(total, count.toLong())
                    onProgress(total)
                } finally {
                    buffer.fill(0, 0, count)
                }
            }
            control.awaitRunning()
            encrypting.finish()
        } catch (failure: Throwable) {
            encrypting.abort()
            throw failure
        } finally {
            buffer.fill(0)
        }
    }

    private fun encryptWithKey(
        input: InputStream,
        output: OutputStream,
        aad: ByteArray,
        key: SecretKey
    ) {
        val encrypting = createEncryptingOutputStream(output, aad, key)
        val buffer = ByteArray(BUFFER_SIZE)
        try {
            while (true) {
                val count = readChunkOrEof(input, buffer)
                if (count < 0) break
                try {
                    encrypting.write(buffer, 0, count)
                } finally {
                    buffer.fill(0, 0, count)
                }
            }
            encrypting.finish()
        } catch (failure: Throwable) {
            encrypting.abort()
            throw failure
        } finally {
            buffer.fill(0)
        }
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
        val prefix = ByteArray(LEGACY_MAGIC.size)
        readExactly(input, prefix, 0, prefix.size)
        if (prefix.contentEquals(CHUNKED_MAGIC)) {
            ChunkedAesGcm.decryptAfterMagic(input, output, aad, key, CHUNKED_MAGIC)
            return
        }
        val versioned = prefix.contentEquals(LEGACY_MAGIC)
        val nonce = ByteArray(IV_BYTES)
        if (versioned) {
            readExactly(input, nonce, 0, nonce.size)
        } else {
            prefix.copyInto(nonce, destinationOffset = 0)
            readExactly(input, nonce, LEGACY_MAGIC.size, IV_BYTES - LEGACY_MAGIC.size)
        }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, nonce))
        if (versioned) cipher.updateAAD(aad)

        val buffer = ByteArray(BUFFER_SIZE)
        try {
            while (true) {
                val count = readChunkOrEof(input, buffer)
                if (count < 0) break
                try {
                    val clear = cipher.update(buffer, 0, count)
                    if (clear != null) {
                        try { output.write(clear) } finally { clear.fill(0) }
                    }
                } finally {
                    buffer.fill(0, 0, count)
                }
            }

            val finalClear = try {
                cipher.doFinal()
            } catch (badTag: AEADBadTagException) {
                throw IOException(
                    "Vault integrity check failed. The encrypted data may be damaged or modified.",
                    badTag
                )
            }
            try {
                output.write(finalClear)
                output.flush()
            } finally {
                finalClear.fill(0)
            }
        } finally {
            buffer.fill(0)
        }
    }

    /** Falls back to a single byte if a provider temporarily returns zero. */
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
                count < 0 -> throw EOFException("Encrypted record is truncated.")
                count == 0 -> {
                    val single = input.read()
                    if (single < 0) throw EOFException("Encrypted record is truncated.")
                    target[offset + position] = single.toByte()
                    position++
                }
                else -> position += count
            }
        }
    }
}
