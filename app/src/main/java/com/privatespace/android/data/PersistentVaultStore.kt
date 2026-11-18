package com.privatespace.android.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.privatespace.android.security.PersistentVaultCrypto
import com.privatespace.android.security.RecoveryKeyAuthenticationException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
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
    /** Latest authenticated index generation committed in this session. */
    private var indexGeneration: Long,
    private var keyGeneration: Long,
    /** Snapshot of index documents only; payload documents are intentionally excluded. */
    private val indexDocuments: MutableList<Pair<Long, DocumentFile>>,
    /** Cached SAF handles for active and trashed payload IDs. */
    private val payloadDocuments: MutableMap<String, DocumentFile>,
    /** Child names and handles from the single open-time directory query. */
    private var pendingFolderSnapshot: List<Pair<String, DocumentFile>>?,
    /** Explicit payload deletions that the document provider has not completed. */
    private val pendingPayloadDeletionIds: MutableSet<String>,
    /** Whether the recovery-ready marker existed in the directory snapshot. */
    private var readyMarkerExists: Boolean,
    /** Highest index filename allocated or observed, including failed partial writes. */
    private var nextIndexGeneration: Long
) {
    private val resolver = context.applicationContext.contentResolver

    data class IndexSnapshot(
        val clearBytes: ByteArray,
        val encryptedDigest: String,
        val generation: Long
    )

    private class CountingDiscardingOutputStream(
        private val maximumBytes: Long
    ) : OutputStream() {
        var bytesWritten: Long = 0L
            private set

        fun resetCount() {
            bytesWritten = 0L
        }

        private fun record(count: Long) {
            val updated = try {
                Math.addExact(bytesWritten, count)
            } catch (failure: ArithmeticException) {
                throw IOException("The recovered payload size is invalid.", failure)
            }
            if (updated > maximumBytes) {
                throw IOException("The recovered payload exceeds its recorded size.")
            }
            bytesWritten = updated
        }

        override fun write(value: Int) {
            record(1L)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
            if (length > 0) record(length.toLong())
        }
    }

    private class WipingByteArrayOutputStream(initialSize: Int) : ByteArrayOutputStream(initialSize) {
        fun wipe() {
            buf.fill(0)
            reset()
        }
    }

    /**
     * Retains provider handles only for payloads authenticated by the recovery
     * index. Orphan payloads are deliberately excluded from normal read/delete
     * paths; the one-time directory snapshot is released after this lookup.
     */
    @Synchronized
    fun cachePayloadDocuments(referencedIds: Set<String>) {
        val snapshot = pendingFolderSnapshot ?: return
        val documentsByName = snapshot.toMap()
        snapshot.forEach { (name, marker) ->
            val id = parsePayloadDeletionId(name) ?: return@forEach
            if (id !in referencedIds) {
                val payload = documentsByName[payloadName(id)]
                if (payload != null && !payload.delete() && payload.exists()) {
                    throw IOException("Could not finish removing an obsolete encrypted recovery payload.")
                }
                if (!marker.delete() && marker.exists()) {
                    throw IOException("Could not remove a completed recovery-payload cleanup marker.")
                }
            } else {
                // Authenticated metadata still references this payload; never delete it.
                runCatching { marker.delete() }
            }
        }
        snapshot.forEach { (name, document) ->
            val id = parsePayloadId(name) ?: return@forEach
            if (id in referencedIds && id !in payloadDocuments) payloadDocuments[id] = document
        }
        pendingFolderSnapshot = null
    }

    @Synchronized
    fun writePayload(
        id: String,
        expectedPlainBytes: Long,
        aad: ByteArray,
        checkCancelled: () -> Unit = {},
        writePlaintext: (OutputStream) -> Unit
    ) {
        checkCancelled()
        requireValidId(id)
        require(expectedPlainBytes >= 0L) { "Invalid recovery payload size." }
        val name = payloadName(id)
        check(id !in payloadDocuments) { "A recovery copy for this file already exists." }
        val target = directory.createFile(BINARY_MIME, name)
            ?: error("Could not create an encrypted recovery file. Check device storage.")
        payloadDocuments[id] = target
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
                    checkCancelled()
                    writePlaintext(encryptedOutput)
                    checkCancelled()
                    encryptedOutput.finish()
                } catch (failure: Throwable) {
                    encryptedOutput.abort()
                    throw failure
                }
            }

            // Do not trust DocumentFile.length(): some SAF providers report an
            // unknown or stale size for a file immediately after writing it.
            // Authenticated decryption plus the exact plaintext byte count verifies
            // completeness without relying on provider metadata or making a copy.
            val verifiedOutput = CountingDiscardingOutputStream(expectedPlainBytes)
            VaultRecoveryReadback.verifyWithRetry(
                operation = "the encrypted recovery file",
                verify = {
                    checkCancelled()
                    verifiedOutput.resetCount()
                    decryptPayload(id, verifiedOutput, aad, checkCancelled)
                    if (verifiedOutput.bytesWritten != expectedPlainBytes) {
                        throw IOException("The encrypted recovery file contains an incomplete payload.")
                    }
                }
            )
        } catch (failure: Throwable) {
            val cleanup = runCatching {
                if (!target.delete() && target.exists()) {
                    throw IOException("Could not remove an incomplete encrypted recovery payload.")
                }
            }
            cleanup.onFailure { cleanupFailure ->
                failure.addSuppressed(cleanupFailure)
                runCatching { createPayloadDeletionMarker(id) }
                    .onSuccess { pendingPayloadDeletionIds += id }
                    .onFailure(failure::addSuppressed)
            }
            if (cleanup.isSuccess) payloadDocuments.remove(id, target)
            throw failure
        }
    }

    @Synchronized
    fun decryptPayload(
        id: String,
        output: OutputStream,
        aad: ByteArray,
        checkCancelled: () -> Unit = {}
    ) {
        checkCancelled()
        requireValidId(id)
        val document = payloadDocuments[id]
            ?: throw IOException("An encrypted recovery file is missing.")
        val input = resolver.openInputStream(document.uri)
            ?: throw IOException("Could not read an encrypted recovery file.")
        input.use { raw ->
            PersistentVaultCrypto.decrypt(
                CancellationCheckingInputStream(raw, checkCancelled),
                output,
                aad,
                requireKey()
            )
        }
    }

    /**
     * Writes a new immutable, encrypted index generation. A partial write is
     * ignored on recovery because its AES-GCM tag will not authenticate. The
     * preceding two generations are retained until the new one is verified.
     */
    @Synchronized
    fun saveIndex(clearIndex: ByteArray, aad: ByteArray): IndexSnapshot {
        require(clearIndex.size <= MAX_INDEX_BYTES) { "Vault index exceeds its supported size." }
        // Reuse the index-only snapshot captured when this recovery store was
        // opened. Enumerating the SAF folder here would walk every encrypted
        // payload on every import/index commit, making large-vault imports
        // increasingly expensive (approximately quadratic provider work).
        val previousGoodGeneration = indexGeneration
        val generation = VaultIndexGenerationPolicy.nextGeneration(nextIndexGeneration)
        // Reserve the generation before any provider write. If create/write/readback
        // fails, the next attempt skips this name rather than colliding with a
        // partial document left by an unreliable SAF provider.
        nextIndexGeneration = generation
        check(indexDocuments.none { it.first == generation }) {
            "A recovery index generation already exists."
        }
        val name = indexName(generation)
        val document = directory.createFile(BINARY_MIME, name)
            ?: error("Could not create an encrypted recovery index. Check device storage.")
        indexDocuments += generation to document

        // SAF providers are not equally reliable when an encrypted record is
        // emitted through several small writes. Build the bounded index envelope
        // in memory, then send it to the provider as one contiguous write (the
        // password-wrapped key files use this same single-write pattern).
        val encryptedBuffer = WipingByteArrayOutputStream(PersistentVaultCrypto.maximumEncryptedContainerLength(clearIndex.size))
        val encryptedPayload = try {
            PersistentVaultCrypto.encrypt(
                ByteArrayInputStream(clearIndex),
                encryptedBuffer,
                aad,
                requireKey()
            )
            encryptedBuffer.toByteArray()
        } finally {
            encryptedBuffer.wipe()
        }

        val expectedEncryptedDigest = PersistentVaultCrypto.sha256(encryptedPayload)
        try {
            try {
                val output = resolver.openOutputStream(document.uri, "w")
                    ?: error("Could not open the encrypted recovery index.")
                output.use {
                    it.write(encryptedPayload)
                    it.flush()
                }
            } finally {
                // The expected ciphertext is no longer needed once the provider
                // has closed the write. Do not retain a second full index copy
                // while the provider's copy is read back and authenticated.
                encryptedPayload.fill(0)
            }

            val encryptedBytes = VaultRecoveryReadback.readVerifiedBytes(
                operation = "the encrypted recovery index",
                read = { readDocumentBytes(document, PersistentVaultCrypto.maximumEncryptedContainerLength(MAX_INDEX_BYTES)) },
                verify = { candidate ->
                    if (PersistentVaultCrypto.sha256(candidate) != expectedEncryptedDigest) {
                        throw IOException("The storage provider changed the encrypted recovery index after writing.")
                    }
                    val verified = WipingByteArrayOutputStream(clearIndex.size)
                    try {
                        PersistentVaultCrypto.decrypt(
                            ByteArrayInputStream(candidate),
                            verified,
                            aad,
                            requireKey()
                        )
                        val verifiedBytes = verified.toByteArray()
                        try {
                            verifiedBytes.contentEquals(clearIndex)
                        } finally {
                            verifiedBytes.fill(0)
                        }
                    } finally {
                        verified.wipe()
                    }
                }
            )
            try {
                // Only promote this generation after the provider readback has
                // authenticated and matched the bytes we intended to save.
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
        } finally {
            encryptedPayload.fill(0)
        }
    }

    @Synchronized
    fun readLatestIndex(aad: ByteArray): IndexSnapshot {
        // open() already enumerated the recovery folder and captured these
        // small index entries. Reuse them instead of querying the SAF provider
        // for the entire directory a second time.
        val candidates = indexDocuments.sortedByDescending { it.first }

        if (candidates.isEmpty()) throw IOException("No encrypted recovery index was found.")

        var lastFailure: Throwable? = null
        for ((generation, document) in candidates) {
            try {
                val encryptedBytes = readDocumentBytes(document, PersistentVaultCrypto.maximumEncryptedContainerLength(MAX_INDEX_BYTES))
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
                        indexGeneration = generation
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
        val previousGeneration = keyGeneration
        val createdGenerations = mutableListOf<Long>()
        try {
            createdGenerations.add(
                writeKeyEnvelope(
                    resolver, directory, requireKey(), verificationPassword.copyOf(), keyGeneration + 1L
                )
            )
            createdGenerations.add(
                writeKeyEnvelope(
                    resolver, directory, requireKey(), verificationPassword.copyOf(), keyGeneration + 1L
                )
            )
        } catch (failure: Throwable) {
            createdGenerations.asReversed().forEach { generation ->
                try {
                    val document = findChild(keyName(generation))
                    if (document != null && !document.delete()) {
                        throw IOException("Could not roll back an incomplete recovery password change.")
                    }
                } catch (rollbackFailure: Throwable) {
                    failure.addSuppressed(rollbackFailure)
                }
            }
            keyGeneration = previousGeneration
            throw failure
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
    ): Long {
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

                    val saved = VaultRecoveryReadback.readVerifiedBytes(
                        operation = "the backup recovery-key envelope",
                        read = { readDocumentBytes(document, MAX_ENVELOPE_BYTES) },
                        verify = { candidate ->
                            val verified = PersistentVaultCrypto.unwrapDataKey(
                                candidate,
                                verificationPassword.copyOf()
                            )
                            try {
                                verified.contentEquals(key)
                            } finally {
                                verified.fill(0)
                            }
                        }
                    )
                    saved.fill(0)
                    keyGeneration = safeGeneration
                    return safeGeneration
                } catch (failure: Throwable) {
                    runCatching {
                        check(document.delete()) {
                            "Could not remove an incomplete recovery-key envelope."
                        }
                    }.onFailure(failure::addSuppressed)
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
        val document = payloadDocuments[id] ?: findChild(payloadName(id))
        if (document == null) {
            pendingPayloadDeletionIds.remove(id)
            return
        }
        createPayloadDeletionMarker(id)
        pendingPayloadDeletionIds += id
        check(document.delete() || !document.exists()) {
            "Could not remove the encrypted recovery copy. Private Space will retry cleanup."
        }
        payloadDocuments.remove(id, document)
        pendingPayloadDeletionIds.remove(id)
        runCatching { findChild(payloadDeletionMarkerName(id))?.delete() }
    }

    @Synchronized
    fun retryPendingPayloadDeletions() {
        pendingPayloadDeletionIds.toList().forEach(::deletePayload)
    }

    @Synchronized
    fun removeUnreferencedPayloads(referencedIds: Set<String>) {
        // This is an explicit bulk cleanup operation, so one full directory
        // pass is appropriate. Normal per-file read/write/delete paths use the
        // cached handles above and never call findFile()/listFiles().
        directory.listFiles().forEach { document ->
            val id = document.name?.let(::parsePayloadId) ?: return@forEach
            if (id !in referencedIds) {
                val removed = runCatching {
                    document.delete() || !document.exists()
                }.getOrDefault(false)
                if (removed) payloadDocuments.remove(id, document)
            }
        }
    }

    @Synchronized
    fun markReady() {
        if (readyMarkerExists) return
        val marker = directory.createFile("text/plain", READY_MARKER)
            ?: error("Could not finalize encrypted recovery storage.")
        // Reserve the marker once created, matching the previous behavior if a
        // provider fails partway through writing its contents.
        readyMarkerExists = true
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
        payloadDocuments.clear()
        indexDocuments.clear()
        pendingFolderSnapshot = null
        pendingPayloadDeletionIds.clear()
    }

    @Synchronized
    fun hasSameDataKey(other: PersistentVaultStore): Boolean =
        MessageDigest.isEqual(requireKey(), other.requireKey())

    private fun requireKey(): ByteArray {
        check(dataKey.size == DATA_KEY_BYTES) { "The recovery vault is locked." }
        return dataKey
    }

    private fun createPayloadDeletionMarker(id: String): DocumentFile {
        requireValidId(id)
        val name = payloadDeletionMarkerName(id)
        val marker = findChild(name) ?: directory.createFile("text/plain", name)
            ?: throw IOException("Could not record pending recovery-payload cleanup.")
        if (marker.name != name) {
            runCatching { marker.delete() }
            throw IOException("The storage provider could not create an exact recovery cleanup marker.")
        }
        return marker
    }

    private fun findChild(name: String): DocumentFile? =
        directory.findFile(name)?.takeIf { it.exists() }

    private fun pruneIndexGenerations(keepGenerations: Set<Long>) {
        // Keep the newest authenticated index and its previous known-good
        // generation. Failed deletions stay in the in-memory snapshot so a later
        // successful save can retry them without listing the whole SAF directory.
        val iterator = indexDocuments.iterator()
        while (iterator.hasNext()) {
            val (generation, document) = iterator.next()
            if (!VaultIndexGenerationPolicy.shouldPrune(generation, keepGenerations)) continue
            val deleted = runCatching {
                document.delete() || !document.exists()
            }.getOrDefault(false)
            if (deleted) iterator.remove()
        }
    }

    private fun readDocumentBytes(document: DocumentFile, maximumBytes: Int): ByteArray {
        val input = resolver.openInputStream(document.uri)
            ?: throw IOException("Could not read an encrypted recovery record.")
        return input.use { stream -> readBoundedBytes(stream, maximumBytes) }
    }

    companion object {
        const val FOLDER_NAME = "Private Space Vault"
        private const val BINARY_MIME = "application/octet-stream"
        private const val READY_MARKER = "vault-ready"
        private const val READY_CONTENT = "PRIVATE_SPACE_RECOVERY_V1\n"
        private const val MAX_INDEX_BYTES = 16 * 1024 * 1024
        private const val MAX_ENVELOPE_BYTES = 4 * 1024
        private const val DATA_KEY_BYTES = 32
        private const val INDEX_PREFIX = "vault-index-"
        private const val INDEX_SUFFIX = ".psv"
        private const val KEY_PREFIX = "vault-key-"
        private const val KEY_SUFFIX = ".wrap"
        private const val PAYLOAD_DELETE_SUFFIX = ".psv.delete"

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
                    val saved = VaultRecoveryReadback.readVerifiedBytes(
                        operation = "the initial recovery-key envelope",
                        read = { readBytes(context, keyDocument, MAX_ENVELOPE_BYTES) },
                        verify = { candidate ->
                            val verified = PersistentVaultCrypto.unwrapDataKey(
                                candidate,
                                verificationPassword.copyOf()
                            )
                            try {
                                verified.contentEquals(key)
                            } finally {
                                verified.fill(0)
                            }
                        }
                    )
                    saved.fill(0)
                } finally {
                    envelope.fill(0)
                }

                // Keep two independently salted/wrapped copies of the random
                // data key. Either one is sufficient to recover the vault.
                val backupPassword = verificationPassword.copyOf()
                writeKeyEnvelope(context.contentResolver, folder, key, backupPassword, 2L)
                return PersistentVaultStore(
                    context = context,
                    directory = folder,
                    dataKey = key,
                    indexGeneration = 0L,
                    keyGeneration = 2L,
                    indexDocuments = mutableListOf(),
                    payloadDocuments = mutableMapOf(),
                    pendingFolderSnapshot = null,
                    pendingPayloadDeletionIds = mutableSetOf(),
                    readyMarkerExists = false,
                    nextIndexGeneration = 0L
                )
            } catch (failure: Throwable) {
                key.fill(0)
                runCatching { deleteDocumentTree(folder) }.onFailure(failure::addSuppressed)
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
            // Use one SAF directory snapshot for index detection, key lookup,
            // and generation recovery instead of enumerating every payload file
            // separately for each check.
            val folderContents = folder.listFiles()
            val existingIndexDocuments = mutableListOf<Pair<Long, DocumentFile>>()
            val candidates = mutableListOf<Pair<Long, DocumentFile>>()
            val pendingFolderSnapshot = mutableListOf<Pair<String, DocumentFile>>()
            var readyMarkerExists = false

            // DocumentFile.findFile() searches by calling listFiles(). Read each
            // child name once and build the small in-memory lookup tables from this
            // one directory snapshot, rather than re-enumerating the whole backup
            // folder for each individual payload operation.
            folderContents.forEach { document ->
                val name = document.name ?: return@forEach
                pendingFolderSnapshot += name to document
                parseIndexGeneration(name)?.let { existingIndexDocuments += it to document }
                parseKeyGeneration(name)?.let { candidates += it to document }
                if (name == READY_MARKER) readyMarkerExists = true
            }
            candidates.sortByDescending { it.first }
            val hasCommittedIndex = existingIndexDocuments.isNotEmpty()
            check(folder.isDirectory && (readyMarkerExists || hasCommittedIndex)) {
                "The selected folder does not contain a completed or recoverable Private Space vault."
            }
            if (candidates.isEmpty()) throw IOException("The recovery-key envelope is missing.")

            var key: ByteArray? = null
            var keyGeneration = 0L
            var lastFailure: Throwable? = null
            var authenticationFailure: RecoveryKeyAuthenticationException? = null
            try {
                for ((generation, document) in candidates) {
                    try {
                        val envelope = readBytes(context, document, MAX_ENVELOPE_BYTES)
                        try {
                            key = PersistentVaultCrypto.unwrapDataKey(envelope, password.copyOf())
                            keyGeneration = generation
                        } finally {
                            envelope.fill(0)
                        }
                        break
                    } catch (failure: RecoveryKeyAuthenticationException) {
                        authenticationFailure = failure
                        lastFailure = failure
                    } catch (failure: Exception) {
                        lastFailure = failure
                    }
                }
            } finally {
                password.fill('\u0000')
            }
            val recoveredKey = key ?: throw (
                authenticationFailure
                    ?: IOException("The recovery-key envelope could not be read safely.", lastFailure)
                )
            val highestExistingGeneration = existingIndexDocuments.maxOfOrNull { it.first } ?: 0L
            return PersistentVaultStore(
                context = context.applicationContext,
                directory = folder,
                dataKey = recoveredKey,
                // readLatestIndex() sets this to the highest authenticated generation.
                indexGeneration = 0L,
                keyGeneration = keyGeneration,
                indexDocuments = existingIndexDocuments,
                payloadDocuments = mutableMapOf(),
                pendingFolderSnapshot = pendingFolderSnapshot,
                pendingPayloadDeletionIds = mutableSetOf(),
                readyMarkerExists = readyMarkerExists,
                nextIndexGeneration = highestExistingGeneration
            )
            } finally {
                password.fill('\u0000')
            }
        }

        fun isRecoveryVault(context: Context, treeUri: Uri): Boolean {
            val tree = DocumentFile.fromTreeUri(context.applicationContext, treeUri) ?: return false
            val folder = tree.findFile(FOLDER_NAME) ?: return false
            if (!folder.isDirectory) return false
            var ready = false
            var hasKeyEnvelope = false
            folder.listFiles().forEach { document ->
                val name = document.name
                if (name == READY_MARKER) ready = true
                if (name?.let(::parseKeyGeneration) != null) hasKeyEnvelope = true
            }
            return ready && hasKeyEnvelope
        }

        private fun deleteDocumentTree(document: DocumentFile) {
            var firstFailure: Throwable? = null
            val children = if (document.isDirectory) {
                try {
                    document.listFiles().toList()
                } catch (failure: Throwable) {
                    firstFailure = failure
                    emptyList()
                }
            } else {
                emptyList()
            }
            children.forEach { child ->
                try {
                    deleteDocumentTree(child)
                } catch (failure: Throwable) {
                    val previous = firstFailure
                    if (previous == null) firstFailure = failure else previous.addSuppressed(failure)
                }
            }
            try {
                if (!document.delete() && document.exists()) {
                    throw IOException("Could not remove recovery item '${document.name ?: "unknown"}'.")
                }
            } catch (failure: Throwable) {
                val previous = firstFailure
                if (previous == null) firstFailure = failure else previous.addSuppressed(failure)
            }
            firstFailure?.let { throw IOException("The incomplete recovery folder could not be fully removed.", it) }
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
                    val saved = VaultRecoveryReadback.readVerifiedBytes(
                        operation = "the backup recovery-key envelope",
                        read = { readBytesFromResolver(resolver, document, MAX_ENVELOPE_BYTES) },
                        verify = { candidate ->
                            val verified = PersistentVaultCrypto.unwrapDataKey(
                                candidate,
                                verificationPassword.copyOf()
                            )
                            try {
                                verified.contentEquals(key)
                            } finally {
                                verified.fill(0)
                            }
                        }
                    )
                    saved.fill(0)
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
            val input = resolver.openInputStream(document.uri)
                ?: throw IOException("Could not read the recovery-key envelope.")
            return input.use { stream -> readBoundedBytes(stream, maximumBytes) }
        }

        private fun readBytes(context: Context, document: DocumentFile, maximumBytes: Int): ByteArray {
            val input = context.contentResolver.openInputStream(document.uri)
                ?: throw IOException("Could not read the recovery-key envelope.")
            return input.use { stream -> readBoundedBytes(stream, maximumBytes) }
        }

        /**
         * Reads the provider's actual stream instead of trusting COLUMN_SIZE /
         * DocumentFile.length(), which may be stale or unknown for newly-written
         * SAF documents. The hard bound prevents a faulty provider from exhausting
         * memory; authentication is performed by the caller after this read.
         */
        internal fun readBoundedBytes(input: InputStream, maximumBytes: Int): ByteArray {
            require(maximumBytes > 0) { "Invalid encrypted-record size limit." }
            val output = WipingByteArrayOutputStream(minOf(maximumBytes, 32 * 1024))
            val buffer = ByteArray(32 * 1024)
            try {
                while (true) {
                    val count = input.read(buffer, 0, buffer.size)
                    when {
                        count < 0 -> break
                        count == 0 -> {
                            // Defensive handling for providers that return zero
                            // for a non-empty read instead of making progress.
                            val single = input.read()
                            if (single < 0) break
                            if (output.size() >= maximumBytes) {
                                throw IOException("An encrypted recovery record exceeds its supported size.")
                            }
                            output.write(single)
                        }
                        else -> {
                            if (output.size().toLong() + count.toLong() > maximumBytes.toLong()) {
                                throw IOException("An encrypted recovery record exceeds its supported size.")
                            }
                            output.write(buffer, 0, count)
                            buffer.fill(0, 0, count)
                        }
                    }
                }
                if (output.size() == 0) {
                    throw IOException("The storage provider returned an empty encrypted recovery record.")
                }
                return output.toByteArray()
            } finally {
                buffer.fill(0)
                output.wipe()
            }
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

        private fun payloadDeletionMarkerName(id: String) = "$id$PAYLOAD_DELETE_SUFFIX"

        private fun parsePayloadDeletionId(name: String): String? {
            if (!name.endsWith(PAYLOAD_DELETE_SUFFIX)) return null
            val id = name.removeSuffix(PAYLOAD_DELETE_SUFFIX)
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
