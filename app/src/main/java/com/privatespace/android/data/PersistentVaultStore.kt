package com.privatespace.android.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.privatespace.android.security.PersistentVaultCrypto
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Durable encrypted mirror stored in a user-selected shared document tree.
 *
 * Files are named only with random record IDs. Topic names, source filenames,
 * MIME types, sizes, and the index contents are inside authenticated ciphertext.
 * The shared directory contains only encrypted payloads and password-wrapped keys.
 */
internal class PersistentVaultStore private constructor(
    context: Context,
    private val directory: DocumentFile,
    private var dataKey: ByteArray,
    private var indexGeneration: Long,
    private var keyGeneration: Long
) {
    private val resolver = context.applicationContext.contentResolver

    data class IndexSnapshot(
        val clearBytes: ByteArray,
        val encryptedDigest: String,
        val generation: Long
    )

    private class DiscardingOutputStream : OutputStream() {
        override fun write(value: Int) = Unit
        override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
    }

    private class WipingByteArrayOutputStream(initialSize: Int) : ByteArrayOutputStream(initialSize) {
        fun wipe() {
            buf.fill(0)
            reset()
        }
    }

    @Synchronized
    fun writePayload(
        id: String,
        expectedPlainBytes: Long,
        aad: ByteArray,
        writePlaintext: (OutputStream) -> Unit
    ) {
        requireValidId(id)
        require(expectedPlainBytes >= 0L) { "Invalid recovery payload size." }
        val name = payloadName(id)
        check(findChild(name) == null) { "A recovery copy for this file already exists." }
        val target = directory.createFile(BINARY_MIME, name)
            ?: error("Could not create an encrypted recovery file. Check device storage.")
        try {
            val output = resolver.openOutputStream(target.uri, "w")
                ?: error("Could not open the encrypted recovery file for writing.")
            output.use { rawOutput ->
                val encryptedOutput = PersistentVaultCrypto.encryptingOutputStream(
                    rawOutput,
                    aad,
                    requireKey()
                )
                try {
                    // The caller authenticates the original ciphertext while
                    // streaming clear bytes directly into this encryptor.
                    writePlaintext(encryptedOutput)
                    encryptedOutput.finish()
                } catch (failure: Throwable) {
                    encryptedOutput.abort()
                    throw failure
                }
            }

            val expectedContainerBytes = Math.addExact(expectedPlainBytes, 36L)
            check(target.length() == expectedContainerBytes) {
                "The encrypted recovery file was not completely written."
            }
        } catch (failure: Throwable) {
            runCatching { target.delete() }
            throw failure
        }
    }

    @Synchronized
    fun decryptPayload(id: String, output: OutputStream, aad: ByteArray) {
        requireValidId(id)
        val document = findChild(payloadName(id))
            ?: throw IOException("An encrypted recovery file is missing.")
        val input = resolver.openInputStream(document.uri)
            ?: throw IOException("Could not read an encrypted recovery file.")
        input.use { PersistentVaultCrypto.decrypt(it, output, aad, requireKey()) }
    }

    /**
     * Writes a new immutable, encrypted index generation. A partial write is
     * ignored on recovery because its AES-GCM tag will not authenticate. The
     * preceding two generations are retained until the new one is verified.
     */
    @Synchronized
    fun saveIndex(clearIndex: ByteArray, aad: ByteArray): IndexSnapshot {
        require(clearIndex.size <= MAX_INDEX_BYTES) { "Vault index exceeds its supported size." }
        // A process may have stopped while writing the previous generation.
        // Skip any partial filename rather than getting stuck reusing it.
        val previousGoodGeneration = indexGeneration
        val highestExisting = directory.listFiles().mapNotNull { document ->
            document.name?.let(::parseIndexGeneration)
        }.maxOrNull() ?: 0L
        val generation = Math.addExact(maxOf(indexGeneration, highestExisting), 1L)
        val name = indexName(generation)
        check(findChild(name) == null) { "A recovery index generation already exists." }
        val document = directory.createFile(BINARY_MIME, name)
            ?: error("Could not create an encrypted recovery index. Check device storage.")

        try {
            val output = resolver.openOutputStream(document.uri, "w")
                ?: error("Could not open the encrypted recovery index.")
            output.use {
                PersistentVaultCrypto.encrypt(
                    ByteArrayInputStream(clearIndex),
                    it,
                    aad,
                    requireKey()
                )
            }

            val encryptedBytes = readDocumentBytes(document, MAX_INDEX_BYTES + CONTAINER_OVERHEAD)
            try {
                val verified = WipingByteArrayOutputStream(clearIndex.size)
                try {
                    PersistentVaultCrypto.decrypt(
                        ByteArrayInputStream(encryptedBytes),
                        verified,
                        aad,
                        requireKey()
                    )
                    val verifiedBytes = verified.toByteArray()
                    try {
                        check(verifiedBytes.contentEquals(clearIndex)) {
                            "The recovery index could not be verified after writing."
                        }
                    } finally {
                        verifiedBytes.fill(0)
                    }
                } finally {
                    verified.wipe()
                }

                indexGeneration = generation
                val snapshot = IndexSnapshot(
                    clearBytes = ByteArray(0),
                    encryptedDigest = PersistentVaultCrypto.sha256(encryptedBytes),
                    generation = generation
                )
                pruneIndexGenerations(setOf(generation, previousGoodGeneration))
                return snapshot
            } finally {
                encryptedBytes.fill(0)
            }
        } catch (failure: Throwable) {
            // Leave a possibly partial file unreferenced. Its tag will fail and
            // the next save uses a new generation rather than overwriting it.
            throw failure
        }
    }

    @Synchronized
    fun readLatestIndex(aad: ByteArray): IndexSnapshot {
        val candidates = directory.listFiles()
            .mapNotNull { document ->
                val name = document.name ?: return@mapNotNull null
                parseIndexGeneration(name)?.let { it to document }
            }
            .sortedByDescending { it.first }

        if (candidates.isEmpty()) throw IOException("No encrypted recovery index was found.")

        var lastFailure: Throwable? = null
        for ((generation, document) in candidates) {
            try {
                val encryptedBytes = readDocumentBytes(document, MAX_INDEX_BYTES + CONTAINER_OVERHEAD)
                try {
                    val clear = WipingByteArrayOutputStream(MAX_INDEX_BYTES.coerceAtMost(64 * 1024))
                    try {
                        PersistentVaultCrypto.decrypt(
                            ByteArrayInputStream(encryptedBytes),
                            clear,
                            aad,
                            requireKey()
                        )
                        val clearBytes = clear.toByteArray()
                        require(clearBytes.size <= MAX_INDEX_BYTES) {
                            clearBytes.fill(0)
                            "Encrypted recovery index exceeds its supported size."
                        }
                        indexGeneration = maxOf(indexGeneration, generation)
                        val digest = PersistentVaultCrypto.sha256(encryptedBytes)
                        return IndexSnapshot(clearBytes, digest, generation)
                    } finally {
                        clear.wipe()
                    }
                } finally {
                    encryptedBytes.fill(0)
                }
            } catch (failure: Exception) {
                lastFailure = failure
            }
        }
        throw IOException(
            "No valid encrypted recovery index could be authenticated.",
            lastFailure
        )
    }

    @Synchronized
    fun rotatePassword(password: CharArray) {
        val verificationPassword = password.copyOf()
        try {
            val firstPassword = verificationPassword.copyOf()
            writeKeyEnvelope(resolver, directory, requireKey(), firstPassword, keyGeneration + 1L)
            val secondPassword = verificationPassword.copyOf()
            writeKeyEnvelope(resolver, directory, requireKey(), secondPassword, keyGeneration + 1L)
        } finally {
            verificationPassword.fill('\u0000')
            password.fill('\u0000')
        }
    }

    private fun writeKeyEnvelope(
        contentResolver: android.content.ContentResolver,
        targetDirectory: DocumentFile,
        key: ByteArray,
        password: CharArray,
        generation: Long
    ) {
        val verificationPassword = password.copyOf()
        try {
            val envelope = PersistentVaultCrypto.wrapDataKey(key, password)
            try {
                val highestExisting = targetDirectory.listFiles().mapNotNull { document ->
                    document.name?.let(::parseKeyGeneration)
                }.maxOrNull() ?: 0L
                val safeGeneration = maxOf(generation, highestExisting + 1L)
                val document = targetDirectory.createFile(BINARY_MIME, keyName(safeGeneration))
                    ?: error("Could not create a recovery-key envelope.")
                try {
                    val output = contentResolver.openOutputStream(document.uri, "w")
                        ?: error("Could not open a recovery-key envelope.")
                    output.use { it.write(envelope); it.flush() }

                    val saved = readDocumentBytes(document, MAX_ENVELOPE_BYTES)
                    val verified = PersistentVaultCrypto.unwrapDataKey(saved, verificationPassword)
                    try {
                        check(verified.contentEquals(key)) {
                            "A recovery-key envelope failed verification."
                        }
                    } finally {
                        verified.fill(0)
                        saved.fill(0)
                    }
                    keyGeneration = safeGeneration
                } catch (failure: Throwable) {
                    runCatching { document.delete() }
                    throw failure
                }
            } finally {
                envelope.fill(0)
            }
        } finally {
            verificationPassword.fill('\u0000')
            password.fill('\u0000')
        }
    }

    @Synchronized
    fun pruneKeyEnvelopes() {
        directory.listFiles()
            .mapNotNull { document ->
                val name = document.name ?: return@mapNotNull null
                parseKeyGeneration(name)?.let { it to document }
            }
            .sortedByDescending { it.first }
            .drop(2)
            .forEach { (_, document) -> runCatching { document.delete() } }
    }

    @Synchronized
    fun deletePayload(id: String) {
        requireValidId(id)
        findChild(payloadName(id))?.let { document ->
            check(document.delete()) { "Could not remove the encrypted recovery copy." }
        }
    }

    @Synchronized
    fun removeUnreferencedPayloads(referencedIds: Set<String>) {
        directory.listFiles().forEach { document ->
            val name = document.name ?: return@forEach
            val id = parsePayloadId(name) ?: return@forEach
            if (id !in referencedIds) runCatching { document.delete() }
        }
    }

    @Synchronized
    fun markReady() {
        if (findChild(READY_MARKER) != null) return
        val marker = directory.createFile("text/plain", READY_MARKER)
            ?: error("Could not finalize encrypted recovery storage.")
        val output = resolver.openOutputStream(marker.uri, "w")
            ?: error("Could not finalize encrypted recovery storage.")
        output.use { it.write(READY_CONTENT.toByteArray(Charsets.US_ASCII)); it.flush() }
    }

    @Synchronized
    fun deleteRecoveryFolder() {
        deleteDocumentTree(directory)
    }

    @Synchronized
    fun clearKey() {
        dataKey.fill(0)
        dataKey = ByteArray(0)
    }

    private fun requireKey(): ByteArray {
        check(dataKey.size == DATA_KEY_BYTES) { "The recovery vault is locked." }
        return dataKey
    }

    private fun findChild(name: String): DocumentFile? =
        directory.findFile(name)?.takeIf { it.exists() }

    private fun pruneIndexGenerations(keepGenerations: Set<Long>) {
        directory.listFiles()
            .mapNotNull { document ->
                val name = document.name ?: return@mapNotNull null
                parseIndexGeneration(name)?.let { it to document }
            }
            .filterNot { (generation, _) -> generation in keepGenerations }
            .forEach { (_, document) -> runCatching { document.delete() } }
    }

    private fun readDocumentBytes(document: DocumentFile, maximumBytes: Int): ByteArray {
        val reportedLength = document.length()
        require(reportedLength in 1..maximumBytes.toLong()) {
            "An encrypted recovery record has an invalid size."
        }
        val input = resolver.openInputStream(document.uri)
            ?: throw IOException("Could not read an encrypted recovery record.")
        return input.use { stream ->
            val output = ByteArrayOutputStream(reportedLength.toInt())
            val buffer = ByteArray(32 * 1024)
            try {
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    require(output.size().toLong() + count <= maximumBytes) {
                        "An encrypted recovery record exceeds its supported size."
                    }
                    output.write(buffer, 0, count)
                    buffer.fill(0, 0, count)
                }
                output.toByteArray()
            } finally {
                buffer.fill(0)
            }
        }
    }

    companion object {
        const val FOLDER_NAME = "Private Space Vault"
        private const val BINARY_MIME = "application/octet-stream"
        private const val READY_MARKER = "vault-ready"
        private const val READY_CONTENT = "PRIVATE_SPACE_RECOVERY_V1\n"
        private const val MAX_INDEX_BYTES = 16 * 1024 * 1024
        private const val MAX_ENVELOPE_BYTES = 4 * 1024
        private const val CONTAINER_OVERHEAD = 8 + 12 + 16
        private const val DATA_KEY_BYTES = 32
        private const val INDEX_PREFIX = "vault-index-"
        private const val INDEX_SUFFIX = ".psv"
        private const val KEY_PREFIX = "vault-key-"
        private const val KEY_SUFFIX = ".wrap"

        fun create(context: Context, treeUri: Uri, password: CharArray): PersistentVaultStore {
            try {
            val tree = DocumentFile.fromTreeUri(context.applicationContext, treeUri)
                ?: throw IOException("The selected recovery folder is unavailable.")
            require(tree.isDirectory && tree.canWrite()) {
                "Choose a writable folder in device storage."
            }
            val existingFolder = tree.findFile(FOLDER_NAME)
            if (existingFolder != null) {
                val hasIndex = existingFolder.listFiles().any { document ->
                    document.name?.let(::parseIndexGeneration) != null
                }
                val isReady = existingFolder.findFile(READY_MARKER)?.exists() == true
                check(!isReady && !hasIndex) {
                    "A completed or recoverable Private Space vault already exists here. Restore it instead of replacing it."
                }
                // A pre-index interrupted migration has no committed manifest.
                // The source vault is still present in this installation, so its
                // incomplete encrypted staging folder can be safely retried.
                deleteDocumentTree(existingFolder)
            }
            val folder = tree.createDirectory(FOLDER_NAME)
                ?: throw IOException("Could not create the Private Space recovery folder.")
            val key = PersistentVaultCrypto.newDataKey()
            val verificationPassword = password.copyOf()
            try {
                val keyToWrap = key.copyOf()
                val envelope = try {
                    PersistentVaultCrypto.wrapDataKey(keyToWrap, password)
                } finally {
                    keyToWrap.fill(0)
                }
                try {
                    val keyDocument = folder.createFile(BINARY_MIME, keyName(1L))
                        ?: throw IOException("Could not create the recovery-key envelope.")
                    val output = context.contentResolver.openOutputStream(keyDocument.uri, "w")
                        ?: throw IOException("Could not write the recovery-key envelope.")
                    output.use { it.write(envelope); it.flush() }
                    val saved = readBytes(context, keyDocument, MAX_ENVELOPE_BYTES)
                    val verified = PersistentVaultCrypto.unwrapDataKey(saved, verificationPassword)
                    try {
                        check(verified.contentEquals(key)) {
                            "The recovery-key envelope failed verification."
                        }
                    } finally {
                        verified.fill(0)
                        saved.fill(0)
                    }
                } finally {
                    envelope.fill(0)
                }

                // Keep two independently salted/wrapped copies of the random
                // data key. Either one is sufficient to recover the vault.
                val backupPassword = verificationPassword.copyOf()
                writeKeyEnvelope(context.contentResolver, folder, key, backupPassword, 2L)
                return PersistentVaultStore(context, folder, key, 0L, 2L)
            } catch (failure: Throwable) {
                key.fill(0)
                deleteDocumentTree(folder)
                throw failure
            } finally {
                verificationPassword.fill('\u0000')
            }
            } finally {
                password.fill('\u0000')
            }
        }

        fun open(context: Context, treeUri: Uri, password: CharArray): PersistentVaultStore {
            try {
            val tree = DocumentFile.fromTreeUri(context.applicationContext, treeUri)
                ?: throw IOException("The selected recovery folder is unavailable.")
            val folder = tree.findFile(FOLDER_NAME)
                ?: throw IOException("No Private Space recovery vault exists in this folder.")
            val hasCommittedIndex = folder.listFiles().any { document ->
                document.name?.let(::parseIndexGeneration) != null
            }
            check(folder.isDirectory && (folder.findFile(READY_MARKER)?.exists() == true || hasCommittedIndex)) {
                "The selected folder does not contain a completed or recoverable Private Space vault."
            }

            val candidates = folder.listFiles()
                .mapNotNull { document ->
                    val name = document.name ?: return@mapNotNull null
                    parseKeyGeneration(name)?.let { it to document }
                }
                .sortedByDescending { it.first }
            if (candidates.isEmpty()) throw IOException("The recovery-key envelope is missing.")

            var key: ByteArray? = null
            var keyGeneration = 0L
            var lastFailure: Throwable? = null
            try {
                for ((generation, document) in candidates) {
                    try {
                        val envelope = readBytes(context, document, MAX_ENVELOPE_BYTES)
                        key = PersistentVaultCrypto.unwrapDataKey(envelope, password.copyOf())
                        envelope.fill(0)
                        keyGeneration = generation
                        break
                    } catch (failure: Exception) {
                        lastFailure = failure
                    }
                }
            } finally {
                password.fill('\u0000')
            }
            val recoveredKey = key ?: throw IOException(
                "Incorrect vault password or damaged recovery-key envelope.",
                lastFailure
            )
            val indexGeneration = folder.listFiles().mapNotNull { document ->
                document.name?.let(::parseIndexGeneration)
            }.maxOrNull() ?: 0L
            return PersistentVaultStore(
                context.applicationContext,
                folder,
                recoveredKey,
                indexGeneration,
                keyGeneration
            )
            } finally {
                password.fill('\u0000')
            }
        }

        fun isRecoveryVault(context: Context, treeUri: Uri): Boolean {
            val tree = DocumentFile.fromTreeUri(context.applicationContext, treeUri) ?: return false
            val folder = tree.findFile(FOLDER_NAME) ?: return false
            return folder.isDirectory &&
                folder.findFile(READY_MARKER)?.exists() == true &&
                folder.listFiles().any { it.name?.let(::parseKeyGeneration) != null }
        }

        private fun deleteDocumentTree(document: DocumentFile) {
            if (document.isDirectory) {
                document.listFiles().forEach(::deleteDocumentTree)
            }
            document.delete()
        }

        private fun writeKeyEnvelope(
            resolver: android.content.ContentResolver,
            directory: DocumentFile,
            key: ByteArray,
            password: CharArray,
            generation: Long
        ) {
            val verificationPassword = password.copyOf()
            try {
                val envelope = PersistentVaultCrypto.wrapDataKey(key, password)
                try {
                    val document = directory.createFile(BINARY_MIME, keyName(generation))
                        ?: throw IOException("Could not create the backup recovery-key envelope.")
                    val output = resolver.openOutputStream(document.uri, "w")
                        ?: throw IOException("Could not write the backup recovery-key envelope.")
                    output.use { it.write(envelope); it.flush() }
                    val saved = readBytesFromResolver(resolver, document, MAX_ENVELOPE_BYTES)
                    val verified = PersistentVaultCrypto.unwrapDataKey(saved, verificationPassword)
                    try {
                        check(verified.contentEquals(key)) {
                            "The backup recovery-key envelope failed verification."
                        }
                    } finally {
                        verified.fill(0)
                        saved.fill(0)
                    }
                } finally {
                    envelope.fill(0)
                }
            } finally {
                verificationPassword.fill(0.toChar())
                password.fill(0.toChar())
            }
        }

        private fun readBytesFromResolver(
            resolver: android.content.ContentResolver,
            document: DocumentFile,
            maximumBytes: Int
        ): ByteArray {
            val length = document.length()
            require(length in 1..maximumBytes.toLong()) { "The recovery-key envelope is invalid." }
            val input = resolver.openInputStream(document.uri)
                ?: throw IOException("Could not read the recovery-key envelope.")
            return input.use { stream ->
                val bytes = stream.readBytes()
                require(bytes.size <= maximumBytes) { "The recovery-key envelope is too large." }
                bytes
            }
        }

        private fun readBytes(context: Context, document: DocumentFile, maximumBytes: Int): ByteArray {
            val length = document.length()
            require(length in 1..maximumBytes.toLong()) { "The recovery-key envelope is invalid." }
            val input = context.contentResolver.openInputStream(document.uri)
                ?: throw IOException("Could not read the recovery-key envelope.")
            return input.use { it.readBytes().also { bytes ->
                require(bytes.size <= maximumBytes) { "The recovery-key envelope is too large." }
            } }
        }

        private fun requireValidId(id: String) {
            UUID.fromString(id)
        }

        private fun payloadName(id: String) = "$id.psv"

        private fun parsePayloadId(name: String): String? {
            if (!name.endsWith(".psv")) return null
            val id = name.removeSuffix(".psv")
            return runCatching { UUID.fromString(id).toString() }.getOrNull()
                ?.takeIf { it == id }
        }

        private fun indexName(generation: Long) =
            INDEX_PREFIX + generation.toString().padStart(20, '0') + INDEX_SUFFIX

        private fun parseIndexGeneration(name: String): Long? {
            if (!name.startsWith(INDEX_PREFIX) || !name.endsWith(INDEX_SUFFIX)) return null
            return name.removePrefix(INDEX_PREFIX).removeSuffix(INDEX_SUFFIX).toLongOrNull()
        }

        private fun keyName(generation: Long) =
            KEY_PREFIX + generation.toString().padStart(20, '0') + KEY_SUFFIX

        private fun parseKeyGeneration(name: String): Long? {
            if (!name.startsWith(KEY_PREFIX) || !name.endsWith(KEY_SUFFIX)) return null
            return name.removePrefix(KEY_PREFIX).removeSuffix(KEY_SUFFIX).toLongOrNull()
        }
    }
}
