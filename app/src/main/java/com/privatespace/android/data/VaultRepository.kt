package com.privatespace.android.data

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaDataSource
import android.os.Build
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.privatespace.android.security.VaultCrypto
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.io.IOException
import java.io.OutputStream
import java.util.Locale
import java.security.MessageDigest
import java.util.UUID
import android.system.Os
import android.system.OsConstants

data class PreparedVaultFile(
    val file: File,
    val mimeType: String
)

private data class VaultIndex(
    val topics: List<VaultTopic> = emptyList(),
    val files: List<VaultFile> = emptyList(),
    val trash: List<VaultTrashEntry> = emptyList(),
    val schemaVersion: Int = VaultIndexSchema.CURRENT_VERSION
)

private class BoundedWipingByteArrayOutputStream(
    private val maximumBytes: Int
) : ByteArrayOutputStream() {
    override fun write(value: Int) {
        requireCapacity(1)
        super.write(value)
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        requireCapacity(length)
        super.write(bytes, offset, length)
    }

    private fun requireCapacity(additionalBytes: Int) {
        if (additionalBytes < 0 || additionalBytes > maximumBytes - count) {
            throw IOException("The encrypted vault index exceeds its supported size.")
        }
    }

    fun wipe() {
        buf.fill(0)
        reset()
    }
}

private class RestoreProgressOutputStream(
    private val delegate: OutputStream,
    private val expectedBytes: Long,
    private val completedBeforeFile: Long,
    private val totalBytes: Long,
    private val completedFiles: Int,
    private val totalFiles: Int,
    private val fileName: String,
    private val onProgress: (VaultRestoreProgress) -> Unit
) : OutputStream() {
    private var written = 0L
    private var lastReported = 0L

    override fun write(value: Int) {
        delegate.write(value)
        record(1)
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        delegate.write(bytes, offset, length)
        record(length.toLong())
    }

    override fun flush() = delegate.flush()

    private fun record(count: Long) {
        if (count <= 0L) return
        written = try { Math.addExact(written, count) } catch (failure: ArithmeticException) {
            throw IOException("Recovered file size is invalid.", failure)
        }
        if (written > expectedBytes) throw IOException("Recovered file is larger than its authenticated index entry.")
        if (written - lastReported >= 1024L * 1024L) {
            lastReported = written
            report()
        }
    }

    fun finish() {
        if (written != expectedBytes) {
            throw IOException("Recovered file size does not match its authenticated index entry.")
        }
        report()
    }

    private fun report() {
        val completed = try { Math.addExact(completedBeforeFile, written) } catch (failure: ArithmeticException) {
            throw IOException("Recovery progress is outside the supported range.", failure)
        }
        onProgress(
            VaultRestoreProgress(
                completedBytes = completed.coerceAtMost(totalBytes),
                totalBytes = totalBytes,
                completedFiles = completedFiles,
                totalFiles = totalFiles,
                currentFileName = fileName
            )
        )
    }
}

class VaultRepository(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val root = File(appContext.filesDir, "vault")
    private val staging = File(appContext.cacheDir, "private-space-staging")
    private val outgoing = File(appContext.cacheDir, "outgoing")
    private val legacyPrefs = appContext.getSharedPreferences("private_space_vault", Context.MODE_PRIVATE)
    private val metadata = AtomicFile(File(root, "vault-index.enc"))
    private val recoveryPrefs = appContext.getSharedPreferences("private_space_recovery", Context.MODE_PRIVATE)
    private var persistentStore: PersistentVaultStore? = null
    private var persistentTreeUri: Uri? = null
    private var initializationFailure: Throwable? = null

    companion object {
        private val INSTANCE_LOCK = Any()

        @Volatile
        private var sharedInstance: VaultRepository? = null

        /**
         * A process-wide repository keeps its synchronized file/index transactions
         * serialized across Activity recreation. Failed initialization is not
         * cached permanently, so a later Activity recreation can retry recovery.
         */
        fun getInstance(context: Context): VaultRepository {
            sharedInstance?.let { cached ->
                if (cached.initializationFailure == null) return cached
            }
            return synchronized(INSTANCE_LOCK) {
                val cached = sharedInstance
                if (cached != null && cached.initializationFailure == null) {
                    cached
                } else {
                    VaultRepository(context.applicationContext).also { sharedInstance = it }
                }
            }
        }

        private const val MAX_METADATA_BYTES = 16 * 1024 * 1024
        private const val OUTGOING_TTL_MS = 30L * 60L * 1000L
        private const val STAGING_TTL_MS = 24L * 60L * 60L * 1000L
        private const val METADATA_AAD = "private-space:metadata:v2"
        private const val RECOVERY_TREE_URI = "tree_uri"
        private const val RECOVERY_INDEX_DIGEST = "index_digest"
        private const val PRIVATE_DIRECTORY_MODE = 0x1C0 // 0700
        private const val PRIVATE_FILE_MODE = 0x180 // 0600
        private const val FILE_DELETE_SUFFIX = ".psv.delete"
        private const val RESTORE_TRANSACTION_FILE = "vault-restore.transaction"
        private const val MAX_RESTORE_TRANSACTION_BYTES = 32 * 1024 * 1024
    }

    init {
        try {
            ensurePrivateDirectory(root)
            ensurePrivateDirectory(staging)
            ensurePrivateDirectory(outgoing)
            // Restore an interrupted per-record promotion before trusting the
            // local index. An existing .restore.bak is the last known-good file.
            recoverInterruptedRestoreFiles()
            // No export, preview, or restore is active when a repository is
            // first created for this process. Remove all abandoned plaintext
            // staging left by a previous process before exposing the vault UI.
            staging.listFiles()?.forEach { temporary ->
                // Cache cleanup is best-effort: an undeletable private staging
                // entry must not prevent the encrypted vault from opening.
                runCatching { temporary.deleteRecursively() }
            }
            migrateLegacyMetadataIfNeeded()

            // Authenticate the committed index before recovering file operations.
            val hasCommittedIndex = metadata.baseFile.exists() || File(metadata.baseFile.path + ".bak").exists()
            val index = loadIndex()
            if (hasCommittedIndex && VaultIndexSchema.needsUpgrade(index.schemaVersion)) {
                // Upgrade v2 (including early 0.9.0 indexes with Trash) before
                // the app can be downgraded to a build that would ignore Trash.
                // AtomicFile preserves the authenticated v2 index if this write fails.
                saveIndex(index)
            }
            recoverInterruptedFileOperations(index, hasCommittedIndex)

            // Remove abandoned plaintext staging by age, not eagerly: a recreated
            // activity/repository must not delete an export still in progress.
            cleanupExpiredOutgoing()
        } catch (failure: Exception) {
            initializationFailure = failure
        }
    }

    /** True when this installation has a persisted Storage Access Framework tree grant. */
    @Synchronized
    fun persistentStorageUri(): Uri? =
        recoveryPrefs.getString(RECOVERY_TREE_URI, null)?.let(Uri::parse)

    /** Wipes the password-recoverable key from memory whenever the vault locks. */
    @Synchronized
    fun lockPersistentStorage() {
        persistentStore?.clearKey()
        persistentStore = null
        persistentTreeUri = null
    }

    @Synchronized
    fun hasActivePersistentStorage(): Boolean = persistentStore != null

    private fun isAlreadyActiveFor(treeUri: Uri, password: CharArray): Boolean {
        val activeStore = persistentStore ?: return false
        check(persistentTreeUri == treeUri) {
            "A different recovery folder is already active. Lock the vault before switching recovery folders."
        }

        // A duplicate restore/setup callback is idempotent only after the
        // supplied password opens this exact folder and unwraps the same key.
        val verifiedStore = PersistentVaultStore.open(appContext, treeUri, password.copyOf())
        try {
            check(activeStore.hasSameDataKey(verifiedStore)) {
                "The selected recovery folder no longer matches the active vault."
            }
        } finally {
            verifiedStore.clearKey()
        }
        return true
    }

    /**
     * Creates a device-local recovery folder and migrates the currently active
     * encrypted vault into it. Existing app-private data remains unchanged.
     */
    @Synchronized
    fun enablePersistentStorage(treeUri: Uri, password: CharArray) {
        try {
            if (isAlreadyActiveFor(treeUri, password)) return
            val index = loadIndex()
            val store = PersistentVaultStore.create(appContext, treeUri, password)
            try {
                (index.files + index.trash.map { it.file }).forEach { record ->
                    val localFile = encryptedFile(record.id)
                    check(localFile.isFile) {
                        "A vault file is missing and cannot be protected for reinstall recovery."
                    }
                    persistLocalEncryptedPayload(store, record.id, localFile, record.sizeBytes)
                }
                val clearIndex = serializeIndex(index)
                val saved = try {
                    store.saveIndex(clearIndex, METADATA_AAD.toByteArray(Charsets.UTF_8))
                } finally {
                    clearIndex.fill(0)
                }
                store.markReady()
                saveRecoveryPreferences(treeUri, saved.encryptedDigest)
                persistentStore = store
                persistentTreeUri = treeUri
            } catch (failure: Throwable) {
                try {
                    store.deleteRecoveryFolder()
                } catch (cleanupFailure: Throwable) {
                    failure.addSuppressed(cleanupFailure)
                } finally {
                    store.clearKey()
                }
                throw failure
            }
        } finally {
            password.fill('\u0000')
        }
    }

    /**
     * Opens an existing recovery vault. A changed authenticated index digest
     * means the durable copy is newer and the private working copy is rebuilt.
     */
    @Synchronized
    fun openPersistentStorage(
        treeUri: Uri,
        password: CharArray,
        onProgress: (VaultRestoreProgress) -> Unit = {}
    ) {
        try {
            if (isAlreadyActiveFor(treeUri, password)) return
            val store = PersistentVaultStore.open(appContext, treeUri, password)
            try {
                val snapshot = store.readLatestIndex(METADATA_AAD.toByteArray(Charsets.UTF_8))
                try {
                    val index = parseIndexBytes(snapshot.clearBytes)
                    val knownDigest = recoveryPrefs.getString(RECOVERY_INDEX_DIGEST, null)
                    val hasLocalIndex = metadata.baseFile.isFile ||
                        File(metadata.baseFile.path + ".bak").isFile

                    // Do not trust a matching digest preference or an index filename
                    // by itself. Authenticate the local index, compare its contents
                    // with the recovery index, and check every referenced payload's
                    // presence and expected container length before reusing the
                    // working copy. This is a bounded metadata/filesystem check; full
                    // GCM authentication is still performed whenever a record is read.
                    val localIndex = if (hasLocalIndex) {
                        runCatching { loadIndex() }.getOrNull()
                    } else {
                        null
                    }
                    val localPayloadsComplete = localIndex?.let { local ->
                        (local.files + local.trash.map { it.file }).all { record ->
                            val localFile = encryptedFile(record.id)
                            localFile.isFile &&
                                VaultRecoveryIntegrity.hasExpectedEncryptedLength(
                                    record.sizeBytes,
                                    localFile.length()
                                )
                        }
                    } ?: false
                    val localIndexMatchesRecovery = localIndex != null && localIndex == index

                    if (
                        !hasLocalIndex ||
                        knownDigest != snapshot.encryptedDigest ||
                        !localIndexMatchesRecovery ||
                        !localPayloadsComplete
                    ) {
                        restoreWorkingVault(index, store, onProgress)
                    }
                    // Keep unreferenced encrypted payloads. They may be newer file
                    // records from an interrupted index commit or older-index fallback.
                    store.markReady()
                    saveRecoveryPreferences(treeUri, snapshot.encryptedDigest)
                    persistentStore = store
                    persistentTreeUri = treeUri
                } finally {
                    snapshot.clearBytes.fill(0)
                }
            } catch (failure: Throwable) {
                store.clearKey()
                throw failure
            }
        } finally {
            password.fill('\u0000')
        }
    }

    /** Restores an existing encrypted recovery vault after a fresh installation. */
    @Synchronized
    fun restorePersistentVault(
        treeUri: Uri,
        password: CharArray,
        onProgress: (VaultRestoreProgress) -> Unit = {}
    ) {
        try {
            if (isAlreadyActiveFor(treeUri, password)) return
            val store = PersistentVaultStore.open(appContext, treeUri, password)
            try {
                val snapshot = store.readLatestIndex(METADATA_AAD.toByteArray(Charsets.UTF_8))
                try {
                    val index = parseIndexBytes(snapshot.clearBytes)
                    restoreWorkingVault(index, store, onProgress)
                    // Keep unreferenced encrypted payloads; an older authenticated
                    // index must never cause newer recoverable data to be deleted.
                    store.markReady()
                    saveRecoveryPreferences(treeUri, snapshot.encryptedDigest)
                    persistentStore = store
                    persistentTreeUri = treeUri
                } finally {
                    snapshot.clearBytes.fill(0)
                }
            } catch (failure: Throwable) {
                store.clearKey()
                throw failure
            }
        } finally {
            password.fill('\u0000')
        }
    }

    /** Re-wraps the same random data key when the user changes their password. */
    @Synchronized
    fun rotatePersistentPassword(password: CharArray) {
        val store = persistentStore
        if (store == null) {
            val recoveryWasConfigured = persistentStorageUri() != null
            password.fill('\u0000')
            check(!recoveryWasConfigured) {
                "Reconnect the encrypted recovery folder before changing your vault password."
            }
            return
        }
        store.rotatePassword(password)
    }

    /** Call only after the new app password has been committed successfully. */
    @Synchronized
    fun finishPersistentPasswordRotation() {
        persistentStore?.pruneKeyEnvelopes()
    }

    @Synchronized
    fun snapshot(): VaultSnapshot {
        val index = loadIndex()
        // Count the physical encrypted containers, not the original source sizes.
        // This is the storage occupied by every saved document/photo in the vault.
        val storedRecords = index.files + index.trash.map { it.file }
        val encryptedLengths = storedRecords.associate { record ->
            val file = encryptedFile(record.id)
            record.id to if (file.isFile) file.length() else 0L
        }
        return VaultSnapshot(
            topics = index.topics,
            files = index.files,
            storageUsage = VaultStorageCalculator.calculate(
                index.topics,
                index.files,
                encryptedLengths,
                index.trash
            ),
            trash = index.trash.sortedByDescending { it.deletedAt }
        )
    }

    @Synchronized
    fun topics(): List<VaultTopic> = loadIndex().topics

    @Synchronized
    fun files(): List<VaultFile> = loadIndex().files

    @Synchronized
    fun createTopic(rawName: String): VaultTopic {
        val name = VaultInputValidation.topicName(rawName)
        val index = loadIndex()
        require(index.topics.none { it.name.equals(name, ignoreCase = true) }) {
            "A topic with that name already exists."
        }
        val now = System.currentTimeMillis()
        val topic = VaultTopic(UUID.randomUUID().toString(), name, now, now)
        saveIndex(index.copy(topics = index.topics + topic))
        return topic
    }

    @Synchronized
    fun addFile(topicId: String, uri: Uri): VaultFile {
        val index = loadIndex()
        require(index.topics.any { it.id == topicId }) { "The selected topic no longer exists." }

        val displayName = readDisplayName(uri)
        val safeName = VaultInputValidation.displayName(displayName)
        val mime = resolveMimeType(uri, safeName)
        val reportedSize = readReportedSize(uri)

        // No application-level file-size ceiling. Reject early only when the
        // reported payload cannot fit in current private storage.
        if (reportedSize != null) {
            val available = root.usableSpace
            val requiredStorage = VaultCrypto.encryptedContainerLength(reportedSize)
            require(requiredStorage <= available) {
                "There is not enough free storage for this file."
            }
        }

        val id = UUID.randomUUID().toString()
        val temporary = File(root, "$id.psv.tmp")
        val target = encryptedFile(id)
        var finalized = false

        try {
            val source = resolver.openInputStream(uri)
                ?: error("Android could not open the selected file.")
            var byteCount = 0L
            source.use { raw ->
                val counted = UnboundedCountingInputStream(raw)
                FileOutputStream(temporary).use { encrypted ->
                    restrictFilePermissions(temporary)
                    VaultCrypto.encrypt(counted, encrypted, fileAad(id))
                    encrypted.fd.sync()
                    byteCount = counted.bytesRead
                }
            }

            // The provider's reported size is only a hint until the stream has
            // been consumed. Reject mismatches before the temp payload is made
            // visible as a finalized vault record.
            VaultImportIntegrity.validateByteCount(reportedSize, byteCount)

            atomicRename(temporary, target)
            finalized = true
            persistentStore?.let { store ->
                persistLocalEncryptedPayload(store, id, target, byteCount)
            }
            val now = System.currentTimeMillis()
            val record = VaultFile(id, topicId, safeName, mime, byteCount, now)
            val changedTopics = index.topics.map {
                if (it.id == topicId) it.copy(updatedAt = now) else it
            }
            try {
                saveIndex(index.copy(topics = changedTopics, files = index.files + record))
            } catch (failure: Throwable) {
                target.delete()
                runCatching { syncDirectory(root) }
                throw failure
            }
            return record
        } catch (failure: Throwable) {
            temporary.delete()
            if (finalized) target.delete()
            runCatching { syncDirectory(root) }
            throw failure
        }
    }

    /**
     * Moves a file into the encrypted Recycle Bin. The authenticated index
     * transition is atomic; the encrypted payload remains in private storage.
     */
    @Synchronized
    fun deleteFile(id: String) {
        val index = loadIndex()
        val record = index.files.firstOrNull { it.id == id } ?: return
        val originalTopic = index.topics.firstOrNull { it.id == record.topicId }
            ?: error("The file's original topic is unavailable.")
        val now = System.currentTimeMillis()
        val updatedTopics = index.topics.map {
            if (it.id == record.topicId) it.copy(updatedAt = now) else it
        }
        saveIndex(index.copy(
            topics = updatedTopics,
            files = index.files.filterNot { it.id == id },
            trash = index.trash + VaultTrashEntry(record, now, originalTopic)
        ))
    }

    /**
     * Deleting a topic removes it from the active topic list and moves each
     * contained file to Trash. Original topic metadata travels with each item
     * so an individual restore can safely recreate the topic.
     */
    @Synchronized
    fun deleteTopic(id: String) {
        val index = loadIndex()
        val topic = index.topics.firstOrNull { it.id == id } ?: return
        val removedFiles = index.files.filter { it.topicId == id }
        val now = System.currentTimeMillis()
        val trashed = removedFiles.map { VaultTrashEntry(it, now, topic) }
        saveIndex(index.copy(
            topics = index.topics.filterNot { it.id == id },
            files = index.files.filterNot { it.topicId == id },
            trash = index.trash + trashed
        ))
    }

    /** Restores one encrypted Trash item without decrypting or rewriting its payload. */
    @Synchronized
    fun restoreTrashFile(id: String) {
        val index = loadIndex()
        val entry = index.trash.firstOrNull { it.file.id == id } ?: return
        require(index.files.none { it.id == id }) { "This file is already in an active topic." }
        check(encryptedFile(id).isFile) { "The encrypted file is missing from the vault; it cannot be restored safely." }

        val now = System.currentTimeMillis()
        val destinationTopic = VaultTrashRestoration.topicFor(entry, index.topics, now)
        val topics = if (index.topics.any { it.id == destinationTopic.id }) {
            index.topics.map {
                if (it.id == destinationTopic.id) it.copy(updatedAt = now) else it
            }
        } else {
            index.topics + destinationTopic
        }
        saveIndex(index.copy(
            topics = topics,
            files = index.files + entry.file.copy(topicId = destinationTopic.id),
            trash = index.trash.filterNot { it.file.id == id }
        ))
    }

    /**
     * Permanently removes one Trash item. The encrypted payload is renamed to
     * a tombstone before the index commit, preserving crash recovery semantics.
     */
    @Synchronized
    fun permanentlyDeleteTrashFile(id: String) {
        val index = loadIndex()
        if (index.trash.none { it.file.id == id }) return
        val tombstone = stageEncryptedForDeletion(id)
        try {
            saveIndex(index.copy(trash = index.trash.filterNot { it.file.id == id }))
        } catch (failure: Throwable) {
            rollbackDeletion(id, tombstone, failure)
            throw failure
        }
        // The durable index no longer references this ID. A failed delete
        // leaves only unreferenced ciphertext, never plaintext.
        runCatching { persistentStore?.deletePayload(id) }
        tombstone?.let(::deleteStagedPayload)
    }

    /**
     * Decrypts to a unique private cache file. The caller must keep it only as
     * long as needed; outgoing copies are removed automatically after 30 minutes.
     */
    @Synchronized
    fun prepareDecryptedFile(id: String): PreparedVaultFile {
        val record = findFile(id)
        val session = createPrivateDirectory(outgoing, UUID.randomUUID().toString())
        val destination = File(session, VaultInputValidation.displayName(record.displayName))
        try {
            decryptInto(record, destination)
            return PreparedVaultFile(destination, record.mimeType)
        } catch (failure: Throwable) {
            runCatching { deleteTemporaryEntry(session) }.onFailure(failure::addSuppressed)
            throw failure
        }
    }

    /**
     * Removes only temporary decrypted files created inside the private outgoing
     * cache. Canonical-path checks prevent a malformed/stale handle from deleting
     * any file outside that dedicated directory.
     */
    @Synchronized
    fun discardPreparedOutgoing(preparedFiles: Collection<PreparedVaultFile>) {
        if (preparedFiles.isEmpty()) return
        val outgoingRoot = outgoing.canonicalFile
        val sessions = linkedMapOf<String, File>()
        preparedFiles.forEach { prepared ->
            val canonicalFile = prepared.file.canonicalFile
            val session = canonicalFile.parentFile
                ?: throw IOException("The temporary outgoing file location is invalid.")
            check(
                canonicalFile.isFile &&
                    session.parentFile?.canonicalFile == outgoingRoot
            ) {
                "Refusing to remove a temporary file outside the private outgoing cache."
            }
            sessions[session.canonicalPath] = session
        }
        sessions.values.forEach(::deleteTemporaryEntry)
    }

    /**
     * Decrypts a photo or video into the private staging cache rather than the
     * FileProvider-exposed outgoing directory. It must be discarded on close.
     */
    @Synchronized
    fun prepareMediaForViewing(id: String): PreparedVaultFile {
        val record = findFile(id)
        require(
            VaultFormatSupport.isImageMimeType(record.mimeType) ||
                VaultFormatSupport.isVideoMimeType(record.mimeType)
        ) { "Only photos and videos can be opened in the private media viewer." }

        val session = createPrivateDirectory(staging, "viewer-${UUID.randomUUID()}")
        val destination = File(session, VaultInputValidation.displayName(record.displayName))
        try {
            decryptInto(record, destination)
            return PreparedVaultFile(destination, record.mimeType)
        } catch (failure: Throwable) {
            runCatching { deleteTemporaryEntry(session) }.onFailure(failure::addSuppressed)
            throw failure
        }
    }

    /** Deletes only a viewer session created inside the private staging cache. */
    @Synchronized
    fun discardMediaForViewing(prepared: PreparedVaultFile) {
        val canonicalRoot = staging.canonicalFile
        val session = prepared.file.canonicalFile.parentFile
            ?: throw IOException("The temporary media location is invalid.")
        require(
            session.parentFile?.canonicalFile == canonicalRoot &&
                session.name.startsWith("viewer-")
        ) { "Refusing to delete a file outside the private media viewer cache." }
        deleteTemporaryEntry(session)
    }

    /**
     * Creates an authenticated in-memory media preview. Current-format videos
     * are read through an authenticated random-access source without creating a
     * full plaintext copy; images and small legacy videos use private staging.
     * No preview or duration is written to the index, and encrypted originals
     * are never modified.
     */
    @Synchronized
    fun loadMediaPreview(id: String, maxDimensionPx: Int): VaultMediaPreview? {
        val index = loadIndex()
        val record = index.files.firstOrNull { it.id == id }
            ?: index.trash.firstOrNull { it.file.id == id }?.file
            ?: return null

        val kind = VaultFormatSupport.kindForFile(record.displayName, record.mimeType)
        val isImage = kind == VaultFormatSupport.FileKind.PHOTO
        val isVideo = kind == VaultFormatSupport.FileKind.VIDEO
        if (!isImage && !isVideo) return null

        // Images still use the bounded full-authentication path. New-format
        // videos use random-access reads: Android's decoder requests only the
        // media ranges needed for the first frame, and each returned 64 KiB
        // encrypted frame is authenticated before plaintext is exposed.
        if (isImage && !VaultThumbnailPolicy.shouldGeneratePreview(record.sizeBytes)) {
            return VaultMediaPreview(null)
        }

        if (isVideo) {
            val randomAccessPreview = decodeChunkedVideoPreview(record, maxDimensionPx)
            if (randomAccessPreview != null) return randomAccessPreview

            // Older single-tag AES-GCM records cannot be safely seek-decrypted.
            // Preserve compatibility for small legacy videos only; never create
            // a full plaintext staging copy for a large legacy video.
            if (!VaultThumbnailPolicy.shouldGeneratePreview(record.sizeBytes)) {
                return VaultMediaPreview(null)
            }
        }

        val target = createPrivateFile(File(staging, "media-preview-${UUID.randomUUID()}.tmp"))
        var previewBitmap: Bitmap? = null
        try {
            // Full-file decrypt verifies the complete AES-GCM authentication tag
            // before the compatibility decoder is allowed to inspect the bytes.
            decryptInto(record, target)
            val preview = if (isImage) {
                VaultMediaPreview(decodeImageThumbnail(target, maxDimensionPx))
            } else {
                decodeVideoPreview(target, maxDimensionPx, firstFrame = true)
            }
            previewBitmap = preview.bitmap
            return preview
        } finally {
            try {
                deleteTemporaryEntry(target)
            } catch (cleanupFailure: Throwable) {
                previewBitmap?.takeIf { !it.isRecycled }?.recycle()
                throw cleanupFailure
            }
        }
    }

    private fun decodeChunkedVideoPreview(
        record: VaultFile,
        maxDimensionPx: Int
    ): VaultMediaPreview? {
        val encrypted = RandomAccessFile(encryptedFile(record.id), "r")
        val reader = try {
            VaultCrypto.openChunkedRandomAccessReader(
                encrypted,
                record.sizeBytes,
                fileAad(record.id)
            )
        } catch (failure: Throwable) {
            runCatching { encrypted.close() }
            throw failure
        } ?: run {
            encrypted.close()
            return null
        }

        val mediaSource = object : MediaDataSource() {
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
                reader.readAt(position, buffer, offset, size)

            override fun getSize(): Long = reader.sizeBytes

            override fun close() = reader.close()
        }

        return decodeVideoPreview(mediaSource, maxDimensionPx, firstFrame = true)
    }

    private fun decodeImageThumbnail(source: File, maxDimensionPx: Int): Bitmap? {
        val safeTarget = maxDimensionPx.coerceIn(32, 512)
        return VaultThumbnailPolicy.decodeSampledBitmap(
            source = source,
            targetSizePx = safeTarget,
            maximumPixels = safeTarget.toLong() * safeTarget.toLong()
        )
    }

    private fun decodeVideoPreview(
        source: File,
        maxDimensionPx: Int,
        firstFrame: Boolean = false
    ): VaultMediaPreview {
        val retriever = try {
            MediaMetadataRetriever()
        } catch (_: OutOfMemoryError) {
            return VaultMediaPreview(null)
        }
        return try {
            retriever.setDataSource(source.absolutePath)
            extractVideoPreview(retriever, maxDimensionPx, firstFrame)
        } catch (_: OutOfMemoryError) {
            VaultMediaPreview(null)
        } catch (_: Exception) {
            VaultMediaPreview(null)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun decodeVideoPreview(
        source: MediaDataSource,
        maxDimensionPx: Int,
        firstFrame: Boolean
    ): VaultMediaPreview {
        val retriever = try {
            MediaMetadataRetriever()
        } catch (_: OutOfMemoryError) {
            runCatching { source.close() }
            return VaultMediaPreview(null)
        }
        return try {
            retriever.setDataSource(source)
            extractVideoPreview(retriever, maxDimensionPx, firstFrame)
        } catch (_: OutOfMemoryError) {
            VaultMediaPreview(null)
        } catch (_: Exception) {
            VaultMediaPreview(null)
        } finally {
            runCatching { retriever.release() }
            runCatching { source.close() }
        }
    }

    private fun extractVideoPreview(
        retriever: MediaMetadataRetriever,
        maxDimensionPx: Int,
        firstFrame: Boolean
    ): VaultMediaPreview {
        val durationMs = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_DURATION
        )?.toLongOrNull()?.takeIf { it >= 0L }

        val safeTarget = maxDimensionPx.coerceIn(32, 512)
        val width = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
        )?.toIntOrNull() ?: 0
        val height = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
        )?.toIntOrNull() ?: 0
        val dimensions = if (width > 0 && height > 0) {
            VaultThumbnailPolicy.fitWithin(width, height, safeTarget)
        } else {
            safeTarget to safeTarget
        }
        val frameTimeUs = if (firstFrame) 0L else -1L

        val bitmap = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    frameTimeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    dimensions.first,
                    dimensions.second
                )
            } else {
                val fullFrame = retriever.getFrameAtTime(
                    frameTimeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                ) ?: return@runCatching null
                var scaled: Bitmap? = null
                try {
                    val target = VaultThumbnailPolicy.fitWithin(fullFrame.width, fullFrame.height, safeTarget)
                    scaled = Bitmap.createScaledBitmap(fullFrame, target.first, target.second, true)
                    scaled
                } finally {
                    if (scaled !== fullFrame && !fullFrame.isRecycled) fullFrame.recycle()
                }
            }
        }.getOrNull()

        return VaultMediaPreview(bitmap, durationMs)
    }

    /** Prepares each topic file independently; a failure removes the whole temporary share set. */
    @Synchronized
    fun prepareTopicShare(topicId: String): List<PreparedVaultFile> {
        val index = synchronized(this) { loadIndex() }
        val records = index.files.filter { it.topicId == topicId }
        require(index.topics.any { it.id == topicId }) { "Topic not found." }
        require(records.isNotEmpty()) { "This topic has no files to share." }

        val session = createPrivateDirectory(outgoing, UUID.randomUUID().toString())
        try {
            val usedNames = mutableSetOf<String>()
            return records.map { record ->
                val baseName = VaultInputValidation.displayName(record.displayName)
                var candidate = baseName
                var suffix = 2
                while (!usedNames.add(candidate.lowercase(Locale.ROOT))) {
                    val dot = baseName.lastIndexOf('.')
                    candidate = if (dot > 0) {
                        "${baseName.substring(0, dot)} ($suffix)${baseName.substring(dot)}"
                    } else {
                        "$baseName ($suffix)"
                    }
                    suffix++
                }
                val stagedFile = File(session, candidate)
                decryptInto(record, stagedFile)
                PreparedVaultFile(stagedFile, record.mimeType)
            }
        } catch (failure: Throwable) {
            runCatching { deleteTemporaryEntry(session) }.onFailure(failure::addSuppressed)
            throw failure
        }
    }

    @Synchronized
    fun exportToUri(id: String, destination: Uri) {
        val record = findFile(id)
        val temporary = createAuthenticatedStagingCopy(record)
        try {
            val output = resolver.openOutputStream(destination, "w")
                ?: error("Android could not open the export destination.")
            output.use { target ->
                FileInputStream(temporary).use { input -> input.copyTo(target, 64 * 1024) }
            }
        } catch (failure: Throwable) {
            runCatching { DocumentFile.fromSingleUri(appContext, destination)?.delete() }
            throw failure
        } finally {
            deleteTemporaryEntry(temporary)
        }
    }

    @Synchronized
    fun exportToDocument(id: String, destination: DocumentFile) {
        val record = findFile(id)
        val temporary = createAuthenticatedStagingCopy(record)
        try {
            val output = resolver.openOutputStream(destination.uri, "w")
                ?: error("Android could not open the export destination.")
            output.use { target ->
                FileInputStream(temporary).use { input -> input.copyTo(target, 64 * 1024) }
            }
        } catch (failure: Throwable) {
            destination.delete()
            throw failure
        } finally {
            deleteTemporaryEntry(temporary)
        }
    }

    @Synchronized
    fun exportTopicToFolder(topicId: String, destination: DocumentFile) {
        val index = synchronized(this) { loadIndex() }
        val topic = index.topics.firstOrNull { it.id == topicId } ?: error("Topic not found.")
        val records = index.files.filter { it.topicId == topicId }
        val folderName = VaultExportLayout.topicFolder(topic).folderName
        val folder = createExactDirectory(destination, folderName)
        try {
            exportRecords(records, folder)
        } catch (failure: Throwable) {
            deleteDocumentTree(folder)
            throw failure
        }
    }

    /**
     * Exports only the requested active files into a new folder in the selected
     * destination. The folder is rolled back if any file fails to export.
     */
    @Synchronized
    fun exportFilesToFolder(fileIds: List<String>, destination: DocumentFile) {
        require(fileIds.isNotEmpty()) { "Select at least one file to export." }
        require(fileIds.distinct().size == fileIds.size) {
            "The export selection contains duplicate files."
        }
        val index = loadIndex()
        val activeFiles = index.files.associateBy { it.id }
        val records = fileIds.map { id ->
            activeFiles[id] ?: error("A selected file is no longer available in the vault.")
        }
        val folder = createExactDirectory(destination, "private-space-selection")
        try {
            exportRecords(records, folder)
        } catch (failure: Throwable) {
            deleteDocumentTree(folder)
            throw failure
        }
    }

    /**
     * Exports the complete vault beneath a single, predictable root folder:
     * private-space-data/<topic name>/<original files>.
     *
     * The destination itself is never used as the export root, so the user
     * always gets one self-contained folder they can move or back up.
     */
    @Synchronized
    fun exportAllToFolder(destination: DocumentFile) {
        val index = synchronized(this) { loadIndex() }
        val rootFolderName = VaultExportLayout.ALL_TOPICS_ROOT_FOLDER_NAME
        val rootFolder = createExactDirectory(destination, rootFolderName)
        try {
            VaultExportLayout.allTopicFolders(index.topics).forEach { topicFolder ->
                val folder = createExactDirectory(rootFolder, topicFolder.folderName)
                exportRecords(
                    index.files.filter { it.topicId == topicFolder.topicId },
                    folder
                )
            }
        } catch (failure: Throwable) {
            deleteDocumentTree(rootFolder)
            throw failure
        }
    }

    /** Deletes stale decrypted staging files, but keeps recent share payloads available to receiving apps. */
    @Synchronized
    fun cleanupExpiredOutgoing(now: Long = System.currentTimeMillis()) {
        deleteExpiredChildren(outgoing, now, OUTGOING_TTL_MS)
        deleteExpiredChildren(staging, now, STAGING_TTL_MS)
    }

    private fun createExactDirectory(parent: DocumentFile, folderName: String): DocumentFile {
        require(parent.listFiles().none { it.name?.equals(folderName, ignoreCase = true) == true }) {
            "A file or folder named '$folderName' already exists here. Choose another location or rename it."
        }
        val created = parent.createDirectory(folderName)
            ?: error("Could not create the folder '$folderName'. Check the selected location.")
        if (created.name != folderName) {
            created.delete()
            error("The selected storage provider could not create the folder '$folderName' with its exact name.")
        }
        return created
    }

    private fun deleteDocumentTree(directory: DocumentFile) {
        directory.listFiles().forEach { child ->
            if (child.isDirectory) deleteDocumentTree(child) else child.delete()
        }
        directory.delete()
    }

    private fun exportRecords(records: List<VaultFile>, folder: DocumentFile) {
        val usedNames = mutableSetOf<String>()
        records.forEach { record ->
            val original = VaultInputValidation.displayName(record.displayName)
            var candidate = original
            var suffix = 2
            while (!usedNames.add(candidate.lowercase(Locale.ROOT))) {
                val dot = original.lastIndexOf('.')
                candidate = if (dot > 0) {
                    "${original.substring(0, dot)} ($suffix)${original.substring(dot)}"
                } else {
                    "$original ($suffix)"
                }
                suffix++
            }
            val target = folder.createFile(record.mimeType, candidate)
                ?: error("Could not create an export for '$candidate'.")
            exportToDocument(record.id, target)
        }
    }

    private fun createAuthenticatedStagingCopy(record: VaultFile): File {
        val target = File(staging, "${UUID.randomUUID()}.tmp")
        try {
            decryptInto(record, target)
            return target
        } catch (failure: Throwable) {
            runCatching { deleteTemporaryEntry(target) }.onFailure(failure::addSuppressed)
            throw failure
        }
    }

    private fun decryptInto(record: VaultFile, target: File) {
        val source = encryptedFile(record.id)
        require(source.isFile) { "The encrypted file is missing from the vault." }
        try {
            FileInputStream(source).use { encrypted ->
                FileOutputStream(target).use { clear ->
                    restrictFilePermissions(target)
                    val exactLength = ExactLengthOutputStream(clear, record.sizeBytes)
                    VaultCrypto.decrypt(encrypted, exactLength, fileAad(record.id))
                    // A valid authentication tag is necessary but the authenticated
                    // index size is also an invariant. Do not expose, share, or export
                    // a decrypted file unless both checks agree exactly.
                    exactLength.finish()
                    clear.fd.sync()
                }
            }
        } catch (failure: Throwable) {
            target.delete()
            throw failure
        }
    }

    @Synchronized
    private fun findFile(id: String): VaultFile =
        loadIndex().files.firstOrNull { it.id == id } ?: error("File not found in the vault.")

    private fun encryptedFile(id: String): File {
        UUID.fromString(id)
        return File(root, "$id.psv")
    }

    private fun fileAad(id: String) = "private-space:file:v2:$id".toByteArray(Charsets.UTF_8)

    /**
     * Reports a failed constructor-time recovery attempt without touching disk.
     * UI startup calls this on a worker thread before presenting the password screen.
     */
    fun ensureInitialized() {
        initializationFailure?.let {
            throw IllegalStateException(
                it.message ?: "Vault initialization failed. Preserve app data and do not clear storage.",
                it
            )
        }
    }

    @Synchronized
    private fun loadIndex(): VaultIndex {
        ensureInitialized()
        val hasBase = metadata.baseFile.exists()
        val hasBackup = File(metadata.baseFile.path + ".bak").exists()
        if (!hasBase && !hasBackup) return VaultIndex()

        val output = BoundedWipingByteArrayOutputStream(MAX_METADATA_BYTES)
        try {
            metadata.openRead().use { encrypted ->
                VaultCrypto.decrypt(encrypted, output, METADATA_AAD.toByteArray(Charsets.UTF_8))
            }
            val clear = output.toByteArray()
            try {
                val json = JSONObject(String(clear, Charsets.UTF_8))
                val schemaVersion = json.optInt("version")
                require(VaultIndexSchema.isSupported(schemaVersion)) {
                    "Unsupported vault index version. Preserve app data and use a compatible app version."
                }

                val topicArray = json.getJSONArray("topics")
                val topics = (0 until topicArray.length()).map { index ->
                    val item = topicArray.getJSONObject(index)
                    val id = item.getString("id")
                    UUID.fromString(id)
                    VaultTopic(
                        id = id,
                        name = VaultInputValidation.topicName(item.getString("name")),
                        createdAt = item.getLong("created"),
                        updatedAt = item.getLong("updated")
                    )
                }
                require(topics.map { it.id }.distinct().size == topics.size) {
                    "Vault index contains duplicate topic identifiers."
                }

                val topicIds = topics.map { it.id }.toSet()
                fun readFile(item: JSONObject, requireActiveTopic: Boolean): VaultFile {
                    val id = item.getString("id")
                    val topicId = item.getString("topic")
                    UUID.fromString(id)
                    UUID.fromString(topicId)
                    if (requireActiveTopic) {
                        require(topicId in topicIds) { "Vault index contains a file with no topic." }
                    }
                    val size = item.getLong("size")
                    require(size >= 0L) { "Vault index contains an invalid file size." }
                    return VaultFile(
                        id = id,
                        topicId = topicId,
                        displayName = VaultInputValidation.displayName(item.getString("name")),
                        mimeType = VaultInputValidation.mimeType(item.getString("mime")),
                        sizeBytes = size,
                        addedAt = item.getLong("added")
                    )
                }

                val fileArray = json.getJSONArray("files")
                val files = (0 until fileArray.length()).map { index ->
                    readFile(fileArray.getJSONObject(index), requireActiveTopic = true)
                }

                // Legacy v2 indexes may predate Trash. Current v3 indexes
                // must contain the array; a missing field is malformed metadata
                // and must not be mistaken for an empty Recycle Bin.
                val trashArray = if (VaultIndexSchema.requiresTrashArray(schemaVersion)) {
                    json.getJSONArray("trash")
                } else {
                    json.optJSONArray("trash") ?: JSONArray()
                }
                val trash = (0 until trashArray.length()).map { index ->
                    val item = trashArray.getJSONObject(index)
                    val deletedAt = item.getLong("deleted")
                    require(deletedAt >= 0L) { "Vault index contains an invalid Trash date." }
                    val file = readFile(item.getJSONObject("file"), requireActiveTopic = false)
                    val topicItem = item.getJSONObject("topic")
                    val topicId = topicItem.getString("id")
                    UUID.fromString(topicId)
                    val originalTopic = VaultTopic(
                        id = topicId,
                        name = VaultInputValidation.topicName(topicItem.getString("name")),
                        createdAt = topicItem.getLong("created"),
                        updatedAt = topicItem.getLong("updated")
                    )
                    require(file.topicId == originalTopic.id) {
                        "Vault Trash entry does not match its original topic."
                    }
                    VaultTrashEntry(file, deletedAt, originalTopic)
                }

                val allFileIds = files.map { it.id } + trash.map { it.file.id }
                require(allFileIds.distinct().size == allFileIds.size) {
                    "Vault index contains duplicate active or trashed file identifiers."
                }
                return VaultIndex(topics, files, trash, schemaVersion)
            } finally {
                clear.fill(0)
            }
        } finally {
            output.wipe()
        }
    }

    @Synchronized
    private fun saveIndex(index: VaultIndex, restoreTransactionId: String? = null) {
        validateIndexForSave(index)
        val previousBytes = serializeIndex(runCatching { loadIndex() }.getOrDefault(VaultIndex()))
        val bytes = serializeIndex(index, restoreTransactionId)
        try {
            val persistentSnapshot = persistentStore?.saveIndex(
                bytes,
                METADATA_AAD.toByteArray(Charsets.UTF_8)
            )

            try {
                val stream = metadata.startWrite()
                try {
                    VaultCrypto.encrypt(
                        ByteArrayInputStream(bytes),
                        stream,
                        METADATA_AAD.toByteArray(Charsets.UTF_8)
                    )
                    stream.fd.sync()
                    metadata.finishWrite(stream)
                } catch (failure: Throwable) {
                    metadata.failWrite(stream)
                    throw failure
                }
            } catch (localFailure: Throwable) {
                // Do not leave the durable mirror ahead of a rolled-back local
                // operation. Restore the previous authenticated index as a new
                // generation; any payload staged for the failed operation then
                // remains only an unreferenced encrypted orphan.
                if (persistentSnapshot != null) {
                    try {
                        val rollback = persistentStore?.saveIndex(
                            previousBytes,
                            METADATA_AAD.toByteArray(Charsets.UTF_8)
                        ) ?: error("The recovery store became unavailable during rollback.")
                        val tree = persistentTreeUri
                            ?: error("Persistent recovery folder is unavailable.")
                        runCatching { saveRecoveryPreferences(tree, rollback.encryptedDigest) }
                    } catch (rollbackFailure: Throwable) {
                        localFailure.addSuppressed(rollbackFailure)
                    }
                }
                throw localFailure
            }

            persistentSnapshot?.let { saved ->
                val tree = persistentTreeUri
                    ?: throw IllegalStateException("Persistent recovery folder is unavailable.")
                runCatching { saveRecoveryPreferences(tree, saved.encryptedDigest) }
            }
        } finally {
            previousBytes.fill(0)
            bytes.fill(0)
        }
    }

    private fun validateIndexForSave(index: VaultIndex) {
        val topicIds = index.topics.map { it.id }.toSet()
        require(index.files.all { it.topicId in topicIds }) { "Cannot save a file without a valid topic." }
        require(index.files.map { it.id }.distinct().size == index.files.size) {
            "Cannot save duplicate active file identifiers."
        }
        require(index.trash.all { it.file.topicId == it.originalTopic.id && it.deletedAt >= 0L }) {
            "Cannot save an invalid Trash entry."
        }
        val allFileIds = index.files.map { it.id } + index.trash.map { it.file.id }
        require(allFileIds.distinct().size == allFileIds.size) {
            "Cannot save duplicate active or trashed file identifiers."
        }
    }

    private fun serializeIndex(index: VaultIndex, restoreTransactionId: String? = null): ByteArray {
        fun topicObject(topic: VaultTopic) = JSONObject()
            .put("id", topic.id)
            .put("name", topic.name)
            .put("created", topic.createdAt)
            .put("updated", topic.updatedAt)
        fun fileObject(file: VaultFile) = JSONObject()
            .put("id", file.id)
            .put("topic", file.topicId)
            .put("name", file.displayName)
            .put("mime", file.mimeType)
            .put("size", file.sizeBytes)
            .put("added", file.addedAt)

        val json = JSONObject().put("version", VaultIndexSchema.CURRENT_VERSION)
        val topicArray = JSONArray()
        index.topics.forEach { topic -> topicArray.put(topicObject(topic)) }
        val fileArray = JSONArray()
        index.files.forEach { file -> fileArray.put(fileObject(file)) }
        val trashArray = JSONArray()
        index.trash.forEach { entry ->
            trashArray.put(
                JSONObject()
                    .put("deleted", entry.deletedAt)
                    .put("file", fileObject(entry.file))
                    .put("topic", topicObject(entry.originalTopic))
            )
        }
        json.put("topics", topicArray)
            .put("files", fileArray)
            .put("trash", trashArray)
        restoreTransactionId?.let { json.put("restoreTransactionId", it) }
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_METADATA_BYTES) { "Vault index has reached its supported size limit." }
        return bytes
    }

    private fun saveRecoveryPreferences(treeUri: Uri, digest: String) {
        check(
            recoveryPrefs.edit()
                .putString(RECOVERY_TREE_URI, treeUri.toString())
                .putString(RECOVERY_INDEX_DIGEST, digest)
                .commit()
        ) { "Could not save the recovery-folder reference." }
    }

    /**
     * Streams from the current Keystore-encrypted record into the independent
     * recovery encryptor. The original local AES-GCM tag is verified before the
     * recovery tag is finalized, so no plaintext staging file is written.
     */
    private fun persistLocalEncryptedPayload(
        store: PersistentVaultStore,
        id: String,
        localEncrypted: File,
        expectedPlainBytes: Long
    ) {
        val aad = fileAad(id)
        FileInputStream(localEncrypted).use { localCiphertext ->
            store.writePayload(id, expectedPlainBytes, aad) { recoveryEncryptor ->
                VaultCrypto.decrypt(localCiphertext, recoveryEncryptor, aad)
            }
        }
    }


    /** Rebuilds the private working copy from authenticated recovery records. */
    private fun restoreWorkingVault(
        index: VaultIndex,
        store: PersistentVaultStore,
        onProgress: (VaultRestoreProgress) -> Unit
    ) {
        validateIndexForSave(index)
        recoverInterruptedRestoreFiles()
        val records = index.files + index.trash.map { it.file }
        val totalBytes = records.fold(0L) { total, record ->
            try { Math.addExact(total, record.sizeBytes) } catch (failure: ArithmeticException) {
                throw IOException("Recovery backup size exceeds the supported range.", failure)
            }
        }
        val requiredLocalBytes = records.fold(0L) { total, record ->
            try {
                Math.addExact(total, VaultCrypto.encryptedContainerLength(record.sizeBytes))
            } catch (failure: ArithmeticException) {
                throw IOException("Recovery backup storage requirement exceeds the supported range.", failure)
            }
        }
        // Staging needs the complete encrypted payload set while the previous
        // working copy remains untouched. Reserve space for the encrypted index,
        // the rollback journal, and filesystem/index commit overhead as well.
        val serializedIndexForCapacity = serializeIndex(index)
        val requiredCommitBytes = try {
            val encryptedIndexBytes = VaultCrypto.encryptedContainerLength(serializedIndexForCapacity.size.toLong())
            val journalBytes = Math.addExact(192L, Math.multiplyExact(records.size.toLong(), 64L))
            Math.addExact(Math.addExact(encryptedIndexBytes, journalBytes), 1024L * 1024L)
        } catch (failure: ArithmeticException) {
            throw IOException("Recovery backup storage requirement exceeds the supported range.", failure)
        } finally {
            serializedIndexForCapacity.fill(0)
        }
        val totalRequiredBytes = try {
            Math.addExact(requiredLocalBytes, requiredCommitBytes)
        } catch (failure: ArithmeticException) {
            throw IOException("Recovery backup storage requirement exceeds the supported range.", failure)
        }
        if (root.usableSpace < totalRequiredBytes) {
            throw IOException(
                "There is not enough free device storage to restore this encrypted vault safely. " +
                    "The restore needs space for the encrypted files and its commit records. " +
                    "Free additional device storage and try again. Your recovery backup has not been changed."
            )
        }
        val incomingFiles = mutableMapOf<String, File>()
        var completedBytes = 0L
        var lastProgressAtNanos = 0L
        var restoreFailure: Throwable? = null
        fun reportProgress(progress: VaultRestoreProgress) {
            val now = System.nanoTime()
            if (
                lastProgressAtNanos == 0L ||
                progress.completedFiles >= progress.totalFiles ||
                now - lastProgressAtNanos >= 500_000_000L
            ) {
                lastProgressAtNanos = now
                runCatching { onProgress(progress) }
            }
        }
        reportProgress(VaultRestoreProgress(0L, totalBytes, 0, records.size, "Preparing recovery"))
        try {
            records.forEachIndexed { recordIndex, record ->
                val incoming = File(root, "${record.id}.restore.tmp")
                check(!incoming.exists()) { "An interrupted vault restore needs recovery." }
                try {
                    VaultRecoveryReadback.verifyWithRetry(verify = {
                        try {
                            FileOutputStream(incoming).use { encrypted ->
                                restrictFilePermissions(incoming)
                                val localEncryptor = VaultCrypto.encryptingOutputStream(
                                    encrypted,
                                    fileAad(record.id)
                                )
                                try {
                                    // The recovery GCM tag must authenticate before the
                                    // local ciphertext tag is finalized. No clear restore
                                    // copy is ever written to disk.
                                    val progressOutput = RestoreProgressOutputStream(
                                        delegate = localEncryptor,
                                        expectedBytes = record.sizeBytes,
                                        completedBeforeFile = completedBytes,
                                        totalBytes = totalBytes,
                                        completedFiles = recordIndex,
                                        totalFiles = records.size,
                                        fileName = record.displayName,
                                        onProgress = ::reportProgress
                                    )
                                    store.decryptPayload(record.id, progressOutput, fileAad(record.id))
                                    progressOutput.finish()
                                    localEncryptor.finish()
                                    encrypted.fd.sync()
                                } catch (failure: Throwable) {
                                    localEncryptor.abort()
                                    throw failure
                                }
                            }
                        } catch (failure: Throwable) {
                            runCatching { deleteTemporaryEntry(incoming) }
                                .onFailure(failure::addSuppressed)
                            throw failure
                        }
                    })
                    incomingFiles[record.id] = incoming
                    completedBytes = Math.addExact(completedBytes, record.sizeBytes)
                    reportProgress(
                        VaultRestoreProgress(
                            completedBytes = completedBytes,
                            totalBytes = totalBytes,
                            completedFiles = recordIndex + 1,
                            totalFiles = records.size,
                            currentFileName = record.displayName
                        )
                    )
                } catch (failure: Throwable) {
                    runCatching { deleteTemporaryEntry(incoming) }
                        .onFailure(failure::addSuppressed)
                    throw failure
                }
            }
            reportProgress(VaultRestoreProgress(totalBytes, totalBytes, records.size, records.size, "Finalizing restored vault"))
            // Persist a small rollback journal before the first live payload is
            // replaced. It lets the next process distinguish a committed restore
            // from a process death halfway through promotion.
            val restoreTransactionId = writeRestoreTransaction(index, records)
            incomingFiles.forEach { (id, incoming) -> replaceRestoredPayload(id, incoming) }
            // The encrypted AtomicFile index is the commit point. Recovery compares
            // its authenticated contents with the journal before keeping or rolling
            // back any promoted payloads.
            saveIndex(index, restoreTransactionId)
            recoverInterruptedRestoreFiles()
        } catch (failure: Throwable) {
            restoreFailure = failure
            runCatching { recoverInterruptedRestoreFiles() }
                .onFailure(failure::addSuppressed)
            throw failure
        } finally {
            records.forEach { record ->
                try {
                    deleteTemporaryEntry(File(root, "${record.id}.restore.tmp"))
                } catch (cleanupFailure: Throwable) {
                    if (restoreFailure != null) {
                        restoreFailure.addSuppressed(cleanupFailure)
                    } else {
                        throw cleanupFailure
                    }
                }
            }
        }
    }

    private fun replaceRestoredPayload(id: String, incoming: File) {
        val destination = encryptedFile(id)
        val expectedIncoming = File(root, "$id.restore.tmp")
        check(incoming.canonicalFile == expectedIncoming.canonicalFile && incoming.isFile) {
            "The restored encrypted record is missing or outside private storage."
        }
        val backup = File(root, "$id.restore.bak")
        check(!backup.exists()) { "An interrupted vault restore needs recovery." }
        if (destination.exists()) Os.rename(destination.absolutePath, backup.absolutePath)
        try {
            Os.rename(incoming.absolutePath, destination.absolutePath)
            // Keep the old payload until the new encrypted index is committed.
            // The transaction journal removes these backups only after commit.
            syncDirectory(root)
        } catch (failure: Throwable) {
            runCatching {
                if (destination.exists()) destination.delete()
                if (backup.exists()) Os.rename(backup.absolutePath, destination.absolutePath)
                if (incoming.exists()) incoming.delete()
                syncDirectory(root)
            }.onFailure(failure::addSuppressed)
            throw failure
        }
    }

    private data class RestoreJournalRecord(
        val id: String,
        val hadOriginal: Boolean
    )

    private fun writeRestoreTransaction(index: VaultIndex, records: List<VaultFile>): String {
        val transactionId = UUID.randomUUID().toString()
        val serializedIndex = serializeIndex(index)
        val targetDigest = try {
            MessageDigest.getInstance("SHA-256").digest(serializedIndex)
                .joinToString("") { "%02x".format(it) }
        } finally {
            serializedIndex.fill(0)
        }
        val journalRecords = JSONArray()
        records.forEach { record ->
            journalRecords.put(
                JSONObject()
                    .put("id", record.id)
                    .put("hadOriginal", encryptedFile(record.id).isFile)
            )
        }
        val journalBytes = JSONObject()
            .put("version", 1)
            .put("transactionId", transactionId)
            .put("targetIndexSha256", targetDigest)
            .put("records", journalRecords)
            .toString()
            .toByteArray(Charsets.UTF_8)
        require(journalBytes.size <= MAX_RESTORE_TRANSACTION_BYTES) {
            "Recovery transaction metadata exceeds its supported size."
        }

        val journal = File(root, RESTORE_TRANSACTION_FILE)
        val temporary = File(root, "$RESTORE_TRANSACTION_FILE.tmp")
        check(!journal.exists() && !temporary.exists()) {
            "An earlier recovery transaction needs to be reconciled before restoring."
        }
        try {
            FileOutputStream(temporary).use { output ->
                restrictFilePermissions(temporary)
                output.write(journalBytes)
                output.fd.sync()
            }
            Os.rename(temporary.absolutePath, journal.absolutePath)
            syncDirectory(root)
        } catch (failure: Throwable) {
            runCatching { temporary.delete() }.onFailure(failure::addSuppressed)
            throw failure
        } finally {
            journalBytes.fill(0)
        }
        return transactionId
    }

    private fun readRestoreCommitTransactionId(): String? {
        val hasBase = metadata.baseFile.isFile
        val hasBackup = File(metadata.baseFile.path + ".bak").isFile
        if (!hasBase && !hasBackup) return null
        val output = BoundedWipingByteArrayOutputStream(MAX_METADATA_BYTES)
        return try {
            metadata.openRead().use { encrypted ->
                VaultCrypto.decrypt(encrypted, output, METADATA_AAD.toByteArray(Charsets.UTF_8))
            }
            val clear = output.toByteArray()
            try {
                JSONObject(String(clear, Charsets.UTF_8))
                    .optString("restoreTransactionId")
                    .takeIf { it.matches(Regex("[0-9a-fA-F-]{36}")) }
            } finally {
                clear.fill(0)
            }
        } finally {
            output.wipe()
        }
    }

    private fun recoverInterruptedRestoreFiles() {
        val journal = File(root, RESTORE_TRANSACTION_FILE)
        if (journal.isFile) {
            val journalBytes = try {
                check(journal.length() in 1L..MAX_RESTORE_TRANSACTION_BYTES.toLong()) {
                    "The interrupted restore journal is invalid. Preserve app data and the recovery folder."
                }
                journal.readBytes()
            } catch (failure: Throwable) {
                throw IOException(
                    "The interrupted restore journal could not be read safely. Preserve app data and the recovery folder.",
                    failure
                )
            }

            val records = try {
                val json = JSONObject(String(journalBytes, Charsets.UTF_8))
                require(json.getInt("version") == 1) { "Unsupported restore journal version." }
                val transactionId = json.getString("transactionId")
                UUID.fromString(transactionId)
                val digest = json.getString("targetIndexSha256")
                require(digest.matches(Regex("[0-9a-f]{64}"))) { "Invalid restore journal digest." }
                val array = json.getJSONArray("records")
                val parsed = (0 until array.length()).map { index ->
                    val item = array.getJSONObject(index)
                    val id = item.getString("id")
                    UUID.fromString(id)
                    RestoreJournalRecord(id, item.getBoolean("hadOriginal"))
                }
                require(parsed.map { it.id }.distinct().size == parsed.size) {
                    "The restore journal contains duplicate file identifiers."
                }
                Triple(transactionId, digest, parsed)
            } catch (failure: Throwable) {
                journalBytes.fill(0)
                throw IOException(
                    "The interrupted restore journal is invalid. Preserve app data and the recovery folder.",
                    failure
                )
            }
            journalBytes.fill(0)

            val (transactionId, targetDigest, journalRecords) = records
            val currentDigest = runCatching {
                val currentIndex = serializeIndex(loadIndex())
                try {
                    MessageDigest.getInstance("SHA-256").digest(currentIndex)
                        .joinToString("") { "%02x".format(it) }
                } finally {
                    currentIndex.fill(0)
                }
            }.getOrNull()
            val committed = runCatching { readRestoreCommitTransactionId() }.getOrNull() == transactionId &&
                currentDigest == targetDigest

            if (committed) {
                // The new authenticated index is durable: keep the new payloads
                // and finish deleting rollback copies left by process death.
                journalRecords.forEach { record ->
                    val backup = File(root, "${record.id}.restore.bak")
                    if (backup.exists()) {
                        check(backup.delete()) { "Could not finalize a restored vault record." }
                    }
                }
            } else {
                // The old index is still authoritative. Restore every original
                // payload and remove only records that did not exist before this
                // transaction, including files promoted just before interruption.
                journalRecords.asReversed().forEach { record ->
                    val destination = encryptedFile(record.id)
                    val backup = File(root, "${record.id}.restore.bak")
                    if (backup.exists()) {
                        if (destination.exists()) {
                            check(destination.delete()) { "Could not roll back a restored vault record." }
                        }
                        Os.rename(backup.absolutePath, destination.absolutePath)
                    } else if (!record.hadOriginal && destination.exists()) {
                        check(destination.delete()) { "Could not remove an uncommitted restored vault record." }
                    } else if (record.hadOriginal) {
                        check(destination.isFile) {
                            "An original vault record is missing during restore rollback. Preserve app data and the recovery folder."
                        }
                    }
                }
            }

            journalRecords.forEach { record ->
                val temporary = File(root, "${record.id}.restore.tmp")
                if (temporary.exists()) deleteTemporaryEntry(temporary)
            }
            check(journal.delete()) { "Could not finalize the interrupted restore journal." }
            syncDirectory(root)
        } else {
            // Backward compatibility with restores started by older app builds,
            // which did not yet write a transaction journal.
            root.listFiles()?.filter { it.name.endsWith(".restore.bak") }?.forEach { backup ->
                val id = backup.name.removeSuffix(".restore.bak")
                if (runCatching { UUID.fromString(id) }.isFailure) return@forEach
                val destination = encryptedFile(id)
                // Before journaled restores existed, a leftover backup meant the
                // process died before the old restore path finished this record.
                // The old encrypted payload is therefore the safe version to keep.
                if (destination.exists()) {
                    check(destination.delete()) { "Could not roll back an interrupted vault restore." }
                }
                Os.rename(backup.absolutePath, destination.absolutePath)
            }
        }
        val journalTemporary = File(root, "$RESTORE_TRANSACTION_FILE.tmp")
        if (journalTemporary.exists()) deleteTemporaryEntry(journalTemporary)
        root.listFiles()?.filter { it.name.endsWith(".restore.tmp") }?.forEach(::deleteTemporaryEntry)
        syncDirectory(root)
    }

    private fun parseIndexBytes(clear: ByteArray): VaultIndex {
        require(clear.size <= MAX_METADATA_BYTES) { "Encrypted recovery index exceeds its supported size." }
        val json = JSONObject(String(clear, Charsets.UTF_8))
        val schemaVersion = json.optInt("version")
        require(VaultIndexSchema.isSupported(schemaVersion)) {
            "Unsupported vault index version. Preserve the recovery folder and use a compatible app version."
        }

        val topicArray = json.getJSONArray("topics")
        val topics = (0 until topicArray.length()).map { index ->
            val item = topicArray.getJSONObject(index)
            val id = item.getString("id")
            UUID.fromString(id)
            VaultTopic(
                id = id,
                name = VaultInputValidation.topicName(item.getString("name")),
                createdAt = item.getLong("created"),
                updatedAt = item.getLong("updated")
            )
        }
        require(topics.map { it.id }.distinct().size == topics.size) {
            "Vault index contains duplicate topic identifiers."
        }

        val topicIds = topics.map { it.id }.toSet()
        fun readFile(item: JSONObject, requireActiveTopic: Boolean): VaultFile {
            val id = item.getString("id")
            val topicId = item.getString("topic")
            UUID.fromString(id)
            UUID.fromString(topicId)
            if (requireActiveTopic) require(topicId in topicIds) {
                "Vault index contains a file with no topic."
            }
            val size = item.getLong("size")
            require(size >= 0L) { "Vault index contains an invalid file size." }
            return VaultFile(
                id = id,
                topicId = topicId,
                displayName = VaultInputValidation.displayName(item.getString("name")),
                mimeType = VaultInputValidation.mimeType(item.getString("mime")),
                sizeBytes = size,
                addedAt = item.getLong("added")
            )
        }

        val fileArray = json.getJSONArray("files")
        val files = (0 until fileArray.length()).map { index ->
            readFile(fileArray.getJSONObject(index), requireActiveTopic = true)
        }
        val trashArray = if (VaultIndexSchema.requiresTrashArray(schemaVersion)) {
            json.getJSONArray("trash")
        } else {
            json.optJSONArray("trash") ?: JSONArray()
        }
        val trash = (0 until trashArray.length()).map { index ->
            val item = trashArray.getJSONObject(index)
            val deletedAt = item.getLong("deleted")
            require(deletedAt >= 0L) { "Vault index contains an invalid Trash date." }
            val file = readFile(item.getJSONObject("file"), requireActiveTopic = false)
            val topicItem = item.getJSONObject("topic")
            val topicId = topicItem.getString("id")
            UUID.fromString(topicId)
            val originalTopic = VaultTopic(
                id = topicId,
                name = VaultInputValidation.topicName(topicItem.getString("name")),
                createdAt = topicItem.getLong("created"),
                updatedAt = topicItem.getLong("updated")
            )
            require(file.topicId == originalTopic.id) {
                "Vault Trash entry does not match its original topic."
            }
            VaultTrashEntry(file, deletedAt, originalTopic)
        }

        val allFileIds = files.map { it.id } + trash.map { it.file.id }
        require(allFileIds.distinct().size == allFileIds.size) {
            "Vault index contains duplicate active or trashed file identifiers."
        }
        return VaultIndex(topics, files, trash, schemaVersion)
    }
    /**
     * One-time migration of the old plaintext SharedPreferences index.
     * Old keys are removed only after the encrypted AtomicFile is committed.
     */
    private fun migrateLegacyMetadataIfNeeded() {
        val hasBase = metadata.baseFile.exists()
        val hasBackup = File(metadata.baseFile.path + ".bak").exists()
        if (hasBase || hasBackup) {
            // A prior migration may have committed the encrypted index but
            // failed while removing the old plaintext preferences.
            if (legacyPrefs.contains("topics") || legacyPrefs.contains("files")) {
                loadIndex() // Verify the encrypted index before deleting the old copy.
                check(
                    legacyPrefs.edit()
                        .remove("topics")
                        .remove("files")
                        .commit()
                ) { "Could not remove the migrated plaintext index." }
            }
            return
        }
        if (!legacyPrefs.contains("topics") && !legacyPrefs.contains("files")) return

        try {
            val topicArray = JSONArray(legacyPrefs.getString("topics", "[]") ?: "[]")
            val topics = (0 until topicArray.length()).map { index ->
                val item = topicArray.getJSONObject(index)
                val id = item.getString("id")
                UUID.fromString(id)
                val oldName = item.getString("name")
                val safeName = runCatching { VaultInputValidation.topicName(oldName) }
                    .getOrElse {
                        VaultInputValidation.topicName(
                            oldName.replace('/', '_').replace('\\', '_')
                                .filterNot { character -> character.isISOControl() }
                                .take(64)
                        )
                    }
                VaultTopic(id, safeName, item.getLong("created"), item.getLong("updated"))
            }
            val topicIds = topics.map { it.id }.toSet()

            val fileArray = JSONArray(legacyPrefs.getString("files", "[]") ?: "[]")
            val files = (0 until fileArray.length()).map { index ->
                val item = fileArray.getJSONObject(index)
                val id = item.getString("id")
                val topicId = item.getString("topic")
                UUID.fromString(id)
                UUID.fromString(topicId)
                require(topicId in topicIds) { "Legacy vault index contains a file with no topic." }
                val size = item.getLong("size")
                require(size >= 0L) { "Legacy vault index has an invalid file size." }
                VaultFile(
                    id,
                    topicId,
                    VaultInputValidation.displayName(item.getString("name")),
                    VaultInputValidation.mimeType(item.getString("mime")),
                    size,
                    item.getLong("added")
                )
            }

            // Do not generate a replacement key if old encrypted payloads still exist.
            // A new key would make a successful metadata migration look healthy while
            // silently making those legacy payloads permanently unreadable.
            if (files.any { File(root, "${it.id}.psv").isFile }) {
                VaultCrypto.requireExistingKey()
            }
            saveIndex(VaultIndex(topics, files))
            check(
                legacyPrefs.edit()
                    .remove("topics")
                    .remove("files")
                    .commit()
            ) { "Encrypted migration completed, but old index cleanup did not finish." }
        } catch (failure: Throwable) {
            throw IllegalStateException(
                "The existing vault index could not be migrated safely. The original data has been kept; do not clear app data.",
                failure
            )
        }
    }

    private fun readDisplayName(uri: Uri): String {
        return runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "document-${System.currentTimeMillis()}"
    }

    private fun readReportedSize(uri: Uri): Long? {
        return runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (column >= 0 && cursor.moveToFirst()) cursor.getLong(column).takeIf { it >= 0L } else null
            }
        }.getOrNull()
    }

    private fun resolveMimeType(uri: Uri, name: String): String {
        // Prefer a known extension mapping when a document provider reports a
        // generic MIME type. No content conversion is performed: this value is
        // only used by Android to select a compatible external viewer.
        VaultFormatSupport.mimeTypeForName(name)?.let { return it }

        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val fallback = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
        return VaultInputValidation.mimeType(resolver.getType(uri), fallback)
    }

    private fun ensurePrivateDirectory(directory: File) {
        check(directory.exists() || directory.mkdirs()) { "Could not create private vault storage." }
        check(directory.isDirectory) { "Private vault storage is not a directory." }
        Os.chmod(directory.absolutePath, PRIVATE_DIRECTORY_MODE)
    }

    private fun createPrivateDirectory(parent: File, name: String): File {
        val directory = File(parent, name)
        check(directory.mkdir()) { "Could not create a temporary sharing folder." }
        try {
            Os.chmod(directory.absolutePath, PRIVATE_DIRECTORY_MODE)
            return directory
        } catch (failure: Throwable) {
            runCatching { directory.deleteRecursively() }.onFailure(failure::addSuppressed)
            throw failure
        }
    }

    private fun createPrivateFile(file: File): File {
        check(file.createNewFile()) { "Could not create a private temporary file." }
        try {
            restrictFilePermissions(file)
            return file
        } catch (failure: Throwable) {
            runCatching { file.delete() }.onFailure(failure::addSuppressed)
            throw failure
        }
    }

    private fun restrictFilePermissions(file: File) {
        Os.chmod(file.absolutePath, PRIVATE_FILE_MODE)
    }

    private fun deleteTemporaryEntry(entry: File) {
        if (entry.exists() && !entry.deleteRecursively()) {
            throw IOException("Could not remove temporary private data from app storage.")
        }
    }

    /** Same-filesystem atomic rename followed by a directory sync. */
    private fun atomicRename(source: File, destination: File) {
        check(source.parentFile?.canonicalPath == destination.parentFile?.canonicalPath) {
            "Vault file operations must stay inside private storage."
        }
        check(source.exists()) { "The encrypted vault file is missing." }
        check(!destination.exists()) { "A vault recovery file already exists." }
        Os.rename(source.absolutePath, destination.absolutePath)
        try {
            syncDirectory(source.parentFile ?: root)
        } catch (failure: Exception) {
            runCatching {
                if (destination.exists() && !source.exists()) {
                    Os.rename(destination.absolutePath, source.absolutePath)
                    syncDirectory(source.parentFile ?: root)
                }
            }.onFailure(failure::addSuppressed)
            throw failure
        }
    }

    private fun syncDirectory(directory: File) {
        val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try {
            Os.fsync(descriptor)
        } finally {
            Os.close(descriptor)
        }
    }

    private fun stageEncryptedForDeletion(id: String): File? {
        val source = encryptedFile(id)
        if (!source.exists()) return null
        val tombstone = File(root, "$id$FILE_DELETE_SUFFIX")
        check(!tombstone.exists()) { "A previous deletion is awaiting recovery." }
        atomicRename(source, tombstone)
        return tombstone
    }

    private fun rollbackDeletion(id: String, tombstone: File?, failure: Throwable) {
        if (tombstone == null || !tombstone.exists()) return
        val original = encryptedFile(id)
        if (original.exists()) return
        runCatching { atomicRename(tombstone, original) }.onFailure(failure::addSuppressed)
    }

    private fun deleteStagedPayload(file: File) {
        runCatching {
            if (file.exists() && file.delete()) syncDirectory(root)
        }
    }

    /**
     * Recovers interrupted import/permanent-deletion transactions from the
     * authenticated index. Both active files and Trash entries reference
     * encrypted payloads. Without a committed index, payloads are preserved.
     */
    private fun recoverInterruptedFileOperations(index: VaultIndex, hasCommittedIndex: Boolean) {
        val referencedIds = (index.files.map { it.id } + index.trash.map { it.file.id })
            .toMutableSet()
        root.listFiles()?.filter { it.name.endsWith(FILE_DELETE_SUFFIX) }?.forEach { tombstone ->
            val id = tombstone.name.removeSuffix(FILE_DELETE_SUFFIX)
            if (runCatching { UUID.fromString(id) }.isFailure) return@forEach
            val original = encryptedFile(id)
            if (!hasCommittedIndex || id in referencedIds) {
                if (!original.exists()) atomicRename(tombstone, original)
                else deleteStagedPayload(tombstone)
            } else {
                deleteStagedPayload(tombstone)
            }
        }

        removeAbandonedTemporaryFiles()
        if (hasCommittedIndex) {
            root.listFiles()?.filter { it.name.endsWith(".psv") }?.forEach { payload ->
                val id = payload.name.removeSuffix(".psv")
                if (runCatching { UUID.fromString(id) }.isSuccess && id !in referencedIds) {
                    // Import crashed after payload rename but before index commit.
                    deleteStagedPayload(payload)
                }
            }
        }
    }

    private fun removeAbandonedTemporaryFiles() {
        // Repository initialization runs before the UI can start a new import.
        // Every .psv.tmp file here was left by an interrupted prior process;
        // these incomplete encrypted records cannot be resumed and only waste space.
        root.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".psv.tmp") }
            ?.forEach { temporary -> runCatching { deleteTemporaryEntry(temporary) } }
    }

    private fun deleteExpiredChildren(directory: File, now: Long, ttlMs: Long) {
        directory.listFiles()?.forEach { child ->
            val modified = child.lastModified()
            val ageIsUnknown = modified <= 0L
            val expired = modified > 0L && now >= modified && now - modified > ttlMs
            if (ageIsUnknown || expired) {
                // Cache deletion failure is contained to this entry. Keep it in
                // app-private storage and retry later instead of locking the vault.
                runCatching { deleteTemporaryEntry(child) }
            }
        }
    }


}
