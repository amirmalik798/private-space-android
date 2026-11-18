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
import com.privatespace.android.security.VaultImportControl
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

class VaultRepository(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val root = File(appContext.filesDir, "vault")
    private val staging = File(appContext.cacheDir, "private-space-staging")
    private val outgoing = File(appContext.cacheDir, "outgoing")
    // Thumbnail decoding must not hold the repository transaction lock: large
    // vault index mutations and imports can continue while a preview is decoded.
    // Keep preview work bounded to one decode at a time on memory-constrained devices.
    private val mediaPreviewLock = Any()
    private val legacyPrefs = appContext.getSharedPreferences("private_space_vault", Context.MODE_PRIVATE)
    private val metadata = AtomicFile(File(root, "vault-index.enc"))
    private val recoveryPrefs = appContext.getSharedPreferences("private_space_recovery", Context.MODE_PRIVATE)
    // Compose reads these lightweight status snapshots on the main thread while
    // long repository transactions may hold the monitor during SAF/file I/O.
    // Volatile visibility keeps those reads non-blocking without weakening the
    // serialization of encrypted storage mutations below.
    @Volatile
    private var persistentStore: PersistentVaultStore? = null
    @Volatile
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
            // Fail closed if any entry cannot be removed.
            staging.listFiles()?.forEach(::deleteTemporaryEntry)
            cleanupAbandonedOutgoingSessions()
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

            // Completed external hand-offs retain the existing bounded TTL.
            // Unhanded sessions were removed above before the UI started.
            cleanupExpiredOutgoing()
        } catch (failure: Exception) {
            initializationFailure = failure
        }
    }

    /** True when this installation has a persisted Storage Access Framework tree grant. */
    /**
     * Lock-free status read for Compose. SharedPreferences provides its own
     * thread-safe snapshot; this must not wait on the vault transaction monitor.
     */
    fun persistentStorageUri(): Uri? =
        recoveryPrefs.getString(RECOVERY_TREE_URI, null)?.let(Uri::parse)

    /** Wipes the password-recoverable key from memory whenever the vault locks. */
    @Synchronized
    fun lockPersistentStorage() {
        persistentStore?.clearKey()
        persistentStore = null
        persistentTreeUri = null
    }

    /** Lock-free status read for Compose while a storage transaction is running. */
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
    fun enablePersistentStorage(
        treeUri: Uri,
        password: CharArray,
        checkCancelled: () -> Unit = {}
    ) {
        try {
            checkCancelled()
            if (isAlreadyActiveFor(treeUri, password)) return
            val index = loadIndex()
            val store = PersistentVaultStore.create(appContext, treeUri, password)
            try {
                (index.files + index.trash.map { it.file }).forEach { record ->
                    checkCancelled()
                    val localFile = encryptedFile(record.id)
                    check(localFile.isFile) {
                        "A vault file is missing and cannot be protected for reinstall recovery."
                    }
                    persistLocalEncryptedPayload(store, record.id, localFile, record.sizeBytes, checkCancelled)
                }
                val clearIndex = serializeIndex(index)
                val saved = try {
                    store.saveIndex(clearIndex, METADATA_AAD.toByteArray(Charsets.UTF_8))
                } finally {
                    clearIndex.fill(0)
                }
                checkCancelled()
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
            notifyRestoreProgress(onProgress, "Checking encrypted recovery folder")
            if (isAlreadyActiveFor(treeUri, password)) return
            val store = PersistentVaultStore.open(appContext, treeUri, password)
            try {
                val snapshot = store.readLatestIndex(METADATA_AAD.toByteArray(Charsets.UTF_8))
                try {
                    val index = parseIndexBytes(snapshot.clearBytes)
                    store.cachePayloadDocuments(
                        (index.files + index.trash.map { it.file }).map { it.id }.toSet()
                    )
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
                    // Compare the actual vault records, not the schema version.
                    // A local v3 index and a recovery v2 index can describe exactly
                    // the same vault after an app upgrade.
                    val localIndexMatchesRecovery = localIndex != null &&
                        localIndex.topics == index.topics &&
                        localIndex.files == index.files &&
                        localIndex.trash == index.trash

                    // A valid local index that differs from the authenticated
                    // recovery index may contain newer on-device work created while
                    // the recovery folder was unavailable. Never replace that
                    // working copy automatically. Let the user explicitly choose
                    // the recovery version from the dashboard if that is intended.
                    if (VaultRecoveryOpenPolicy.shouldBlockAutomaticRestore(
                            hasLocalIndex = hasLocalIndex,
                            localIndexReadable = localIndex != null,
                            localContentMatchesRecovery = localIndexMatchesRecovery
                        )
                    ) {
                        throw VaultRecoveryConflictException(
                            "The on-device vault and recovery copy contain different changes. " +
                                "Automatic restore was stopped to protect your on-device files. " +
                                "Your current vault has not been changed. Use Restore from recovery folder " +
                                "only if you want that recovery copy to replace the on-device vault."
                        )
                    }

                    if (!hasLocalIndex || !localIndexMatchesRecovery || !localPayloadsComplete) {
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
            notifyRestoreProgress(onProgress, "Opening selected recovery backup")
            if (isAlreadyActiveFor(treeUri, password)) return
            val store = PersistentVaultStore.open(appContext, treeUri, password)
            try {
                val snapshot = store.readLatestIndex(METADATA_AAD.toByteArray(Charsets.UTF_8))
                try {
                    val index = parseIndexBytes(snapshot.clearBytes)
                    store.cachePayloadDocuments(
                        (index.files + index.trash.map { it.file }).map { it.id }.toSet()
                    )
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
    fun addFile(topicId: String, uri: Uri): VaultFile =
        addFileWithProgress(topicId, uri, null, onProgress = { _, _ -> })

    /**
     * Imports into an app-private encrypted temporary file. The file is only
     * renamed and indexed after encryption, byte-count validation, fsync and
     * optional recovery-vault persistence have all succeeded.
     *
     * The repository monitor serializes index mutations and import commits.
     * Pause/stop are observed between bounded encryption chunks.
     */
    fun addFileWithProgress(
        topicId: String,
        uri: Uri,
        control: VaultImportControl?,
        onProgress: (completedBytes: Long, totalBytes: Long?) -> Unit = { _, _ -> },
        onFinalizing: () -> Unit = {}
    ): VaultFile {
        synchronized(this) {
            require(loadIndex().topics.any { it.id == topicId }) {
                "The selected topic no longer exists."
            }
        }
        control?.awaitRunning()

        val displayName = readDisplayName(uri)
        val safeName = VaultInputValidation.displayName(displayName)
        val mime = resolveMimeType(uri, safeName)
        val reportedSize = readReportedSize(uri)

        if (reportedSize != null) {
            val requiredStorage = VaultCrypto.encryptedContainerLength(reportedSize)
            require(requiredStorage <= root.usableSpace) {
                "There is not enough free storage for this file."
            }
        }

        val id = UUID.randomUUID().toString()
        val temporary = File(root, "$id.psv.tmp")
        val target = encryptedFile(id)
        var finalized = false
        var recoveryStoreUsed: PersistentVaultStore? = null
        // The janitor must not remove an encrypted import while it is active.
        VaultOutgoingSessionRegistry.markActive(temporary)

        try {
            val source = resolver.openInputStream(uri)
                ?: error("Android could not open the selected file.")
            var byteCount = 0L
            source.use { raw ->
                control?.attach(raw)
                try {
                    val counted = UnboundedCountingInputStream(raw)
                    FileOutputStream(temporary).use { encrypted ->
                        restrictFilePermissions(temporary)
                        if (control == null) {
                            VaultCrypto.encrypt(counted, encrypted, fileAad(id))
                        } else {
                            VaultCrypto.encryptControlled(
                                counted,
                                encrypted,
                                fileAad(id),
                                control
                            ) { completed ->
                                onProgress(completed, reportedSize)
                            }
                        }
                        encrypted.fd.sync()
                        byteCount = counted.bytesRead
                    }
                } finally {
                    control?.detach(raw)
                }
            }

            control?.awaitRunning()
            VaultImportIntegrity.validateByteCount(reportedSize, byteCount)
            onProgress(byteCount, reportedSize)

            val record = synchronized(this) {
                // The stop/finalize transition and vault commit share the same
                // repository lock as recovery-store locking and index mutations.
                control?.beginFinalizing()
                onFinalizing()
                atomicRename(temporary, target)
                finalized = true
                val latestIndex = loadIndex()
                require(latestIndex.topics.any { it.id == topicId }) {
                    "The selected topic was removed before this file could be added."
                }
                persistentStore?.let { store ->
                    recoveryStoreUsed = store
                    persistLocalEncryptedPayload(store, id, target, byteCount)
                }
                val now = System.currentTimeMillis()
                val created = VaultFile(id, topicId, safeName, mime, byteCount, now)
                val changedTopics = latestIndex.topics.map {
                    if (it.id == topicId) it.copy(updatedAt = now) else it
                }
                saveIndex(latestIndex.copy(
                    topics = changedTopics,
                    files = latestIndex.files + created
                ))
                created
            }
            return record
        } catch (failure: Throwable) {
            runCatching { deleteTemporaryEntry(temporary) }.onFailure(failure::addSuppressed)
            if (finalized) {
                recoveryStoreUsed?.let { store ->
                    runCatching { store.deletePayload(id) }.onFailure(failure::addSuppressed)
                }
                runCatching { deleteTemporaryEntry(target) }.onFailure(failure::addSuppressed)
            }
            runCatching { syncDirectory(root) }.onFailure(failure::addSuppressed)
            throw failure
        } finally {
            VaultOutgoingSessionRegistry.markInactive(temporary)
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
        // The durable index no longer references this ID. Record and retry
        // recovery-store deletion instead of silently retaining an orphan.
        persistentStore?.deletePayload(id)
        tombstone?.let(::deleteStagedPayload)
    }

    /**
     * Decrypts to a unique private cache file. The caller must keep it only as
     * long as needed; outgoing copies are removed automatically after 30 minutes.
     */
    @Synchronized
    fun prepareDecryptedFile(
        id: String,
        onProgress: (VaultFileOperationProgress) -> Unit = {}
    ): PreparedVaultFile {
        val record = findFile(id)
        val session = createOutgoingSession()
        val destination = File(session, VaultInputValidation.displayName(record.displayName))
        try {
            onProgress(VaultFileOperationProgress("Decrypting", 0L, record.sizeBytes, currentFileName = record.displayName))
            decryptInto(record, destination) { completed ->
                onProgress(VaultFileOperationProgress("Decrypting", completed, record.sizeBytes, currentFileName = record.displayName))
            }
            onProgress(VaultFileOperationProgress("Decryption complete", record.sizeBytes, record.sizeBytes, currentFileName = record.displayName))
            return PreparedVaultFile(destination, record.mimeType)
        } catch (failure: Throwable) {
            try {
                runCatching { deleteTemporaryEntry(session) }.onFailure(failure::addSuppressed)
            } finally {
                VaultOutgoingSessionRegistry.markInactive(session)
            }
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
        sessions.values.forEach { session ->
            try {
                deleteTemporaryEntry(session)
            } finally {
                // If deletion fails, let the process-independent janitor retry it.
                VaultOutgoingSessionRegistry.markInactive(session)
            }
        }
    }

    /** Mark prepared plaintext as handed off, retaining it only for the existing TTL. */
    @Synchronized
    fun markOutgoingHandedOff(preparedFiles: Collection<PreparedVaultFile>) {
        require(preparedFiles.isNotEmpty()) { "No prepared files were supplied for hand-off." }
        val outgoingRoot = outgoing.canonicalFile
        val sessions = linkedMapOf<String, File>()
        preparedFiles.forEach { prepared ->
            val canonicalFile = prepared.file.canonicalFile
            val session = canonicalFile.parentFile
                ?: throw IOException("The temporary outgoing file location is invalid.")
            check(canonicalFile.isFile && session.parentFile?.canonicalFile == outgoingRoot) {
                "Refusing to retain a file outside the private outgoing cache."
            }
            sessions[session.canonicalPath] = session
        }
        sessions.values.forEach { session ->
            val pending = File(session, VaultOutgoingSessionPolicy.PENDING_MARKER)
            val handedOff = File(session, VaultOutgoingSessionPolicy.HANDED_OFF_MARKER)
            check(pending.isFile && !handedOff.exists()) {
                "The prepared outgoing session is no longer pending."
            }
            Os.rename(pending.absolutePath, handedOff.absolutePath)
            VaultOutgoingSessionRegistry.markInactive(session)
            syncDirectory(session)
        }
    }

    /**
     * Decrypts a photo or video into the private staging cache rather than the
     * FileProvider-exposed outgoing directory. It must be discarded on close.
     */
    @Synchronized
    fun prepareMediaForViewing(
        id: String,
        onProgress: (VaultFileOperationProgress) -> Unit = {}
    ): PreparedVaultFile {
        val record = findFile(id)
        require(
            VaultFormatSupport.kindForFile(record.displayName, record.mimeType) !=
                VaultFormatSupport.FileKind.DOCUMENT
        ) { "Only photos and videos can be opened in the private media viewer." }

        val session = File(staging, "viewer-${UUID.randomUUID()}")
        VaultOutgoingSessionRegistry.markActive(session)
        try {
            createPrivateDirectory(staging, session.name)
        } catch (failure: Throwable) {
            VaultOutgoingSessionRegistry.markInactive(session)
            throw failure
        }
        val destination = File(session, VaultInputValidation.displayName(record.displayName))
        try {
            onProgress(VaultFileOperationProgress("Decrypting", 0L, record.sizeBytes, currentFileName = record.displayName))
            decryptInto(record, destination) { completed ->
                onProgress(VaultFileOperationProgress("Decrypting", completed, record.sizeBytes, currentFileName = record.displayName))
            }
            onProgress(VaultFileOperationProgress("Decryption complete", record.sizeBytes, record.sizeBytes, currentFileName = record.displayName))
            return PreparedVaultFile(destination, record.mimeType)
        } catch (failure: Throwable) {
            try {
                runCatching { deleteTemporaryEntry(session) }.onFailure(failure::addSuppressed)
            } finally {
                VaultOutgoingSessionRegistry.markInactive(session)
            }
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
        try {
            deleteTemporaryEntry(session)
        } finally {
            VaultOutgoingSessionRegistry.markInactive(session)
        }
    }

    /**
     * Creates an authenticated in-memory media preview. Current-format videos
     * are read through an authenticated random-access source without creating a
     * full plaintext copy; images and small legacy videos use private staging.
     * No preview or duration is written to the index, and encrypted originals
     * are never modified.
     */
    fun loadMediaPreview(id: String, maxDimensionPx: Int): VaultMediaPreview? {
        // Keep the legacy ID-based API safe for callers that do not already hold
        // an authenticated VaultSnapshot record. The expensive decode happens
        // after releasing the repository lock.
        val record = synchronized(this) {
            val index = loadIndex()
            index.files.firstOrNull { it.id == id }
                ?: index.trash.firstOrNull { it.file.id == id }?.file
        } ?: return null

        return loadMediaPreview(record, maxDimensionPx)
    }

    /**
     * Loads a preview for a file record obtained from the authenticated vault
     * snapshot. The UI already owns this verified metadata, so re-decrypting and
     * reparsing the entire index for every visible thumbnail is unnecessary.
     *
     * Payload authentication and bounded decoding are unchanged. A dedicated
     * preview lock prevents concurrent codec allocations while leaving repository
     * index transactions and import finalization unblocked.
     */
    internal fun loadMediaPreview(
        record: VaultFile,
        maxDimensionPx: Int
    ): VaultMediaPreview? = synchronized(mediaPreviewLock) {
        loadMediaPreviewRecord(record, maxDimensionPx)
    }

    private fun loadMediaPreviewRecord(
        record: VaultFile,
        maxDimensionPx: Int
    ): VaultMediaPreview? {
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

        val target = File(staging, "media-preview-${UUID.randomUUID()}.tmp")
        VaultOutgoingSessionRegistry.markActive(target)
        var previewBitmap: Bitmap? = null
        try {
            createPrivateFile(target)
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
            } finally {
                VaultOutgoingSessionRegistry.markInactive(target)
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
        } catch (_: Exception) {
            // The reader owns an open RandomAccessFile and a plaintext frame
            // cache. Release it even when Android cannot construct the decoder.
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

    /**
     * Prepares each topic file independently; a failure or cooperative cancel
     * removes the entire temporary share set before anything is handed off.
     */
    @Synchronized
    fun prepareTopicShare(
        topicId: String,
        onProgress: (VaultFileOperationProgress) -> Unit = {}
    ): List<PreparedVaultFile> {
        val index = synchronized(this) { loadIndex() }
        val records = index.files.filter { it.topicId == topicId }
        require(index.topics.any { it.id == topicId }) { "Topic not found." }
        require(records.isNotEmpty()) { "This topic has no files to share." }
        val totalBytes = runCatching {
            records.fold(0L) { total, record -> Math.addExact(total, record.sizeBytes) }
        }.getOrNull() ?: 0L

        val session = createOutgoingSession()
        var completedBytes = 0L
        try {
            val usedNames = mutableSetOf<String>()
            return records.mapIndexed { indexInTopic, record ->
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

                onProgress(
                    VaultFileOperationProgress(
                        phase = "Preparing share",
                        completedBytes = completedBytes,
                        totalBytes = totalBytes,
                        completedFiles = indexInTopic,
                        totalFiles = records.size,
                        currentFileName = record.displayName,
                        currentFileCompletedBytes = 0L,
                        currentFileTotalBytes = record.sizeBytes
                    )
                )
                val stagedFile = File(session, candidate)
                decryptInto(record, stagedFile) { currentBytes ->
                    val aggregate = runCatching {
                        Math.addExact(completedBytes, currentBytes)
                    }.getOrDefault(Long.MAX_VALUE)
                    onProgress(
                        VaultFileOperationProgress(
                            phase = "Preparing share",
                            completedBytes = aggregate,
                            totalBytes = totalBytes,
                            completedFiles = indexInTopic,
                            totalFiles = records.size,
                            currentFileName = record.displayName,
                            currentFileCompletedBytes = currentBytes,
                            currentFileTotalBytes = record.sizeBytes
                        )
                    )
                }
                completedBytes = runCatching {
                    Math.addExact(completedBytes, record.sizeBytes)
                }.getOrDefault(Long.MAX_VALUE)
                onProgress(
                    VaultFileOperationProgress(
                        phase = "Share preparation complete",
                        completedBytes = completedBytes,
                        totalBytes = totalBytes,
                        completedFiles = indexInTopic + 1,
                        totalFiles = records.size,
                        currentFileName = record.displayName,
                        currentFileCompletedBytes = record.sizeBytes,
                        currentFileTotalBytes = record.sizeBytes
                    )
                )
                PreparedVaultFile(stagedFile, record.mimeType)
            }
        } catch (failure: Throwable) {
            try {
                runCatching { deleteTemporaryEntry(session) }.onFailure(failure::addSuppressed)
            } finally {
                VaultOutgoingSessionRegistry.markInactive(session)
            }
            throw failure
        }
    }

    @Synchronized
    fun exportToUri(
        id: String,
        destination: Uri,
        onProgress: (VaultFileOperationProgress) -> Unit = {}
    ) {
        val record = findFile(id)
        exportRecordToUri(record, destination, onProgress)
    }

    @Synchronized
    fun exportToDocument(
        id: String,
        destination: DocumentFile,
        onProgress: (VaultFileOperationProgress) -> Unit = {}
    ) {
        val record = findFile(id)
        try {
            // Calculate inside the rollback scope so even an overflow cannot
            // leave the document created by the picker behind.
            val totalWorkBytes = Math.multiplyExact(record.sizeBytes, 2L)
            onProgress(
                VaultFileOperationProgress(
                    "Decrypting",
                    0L,
                    totalWorkBytes,
                    currentFileName = record.displayName,
                    currentFileCompletedBytes = 0L,
                    currentFileTotalBytes = record.sizeBytes
                )
            )
            exportRecordToDocument(record, destination) { currentBytes, phase ->
                val phaseBase = if (phase == "Writing export") record.sizeBytes else 0L
                onProgress(
                    VaultFileOperationProgress(
                        phase,
                        phaseBase + currentBytes,
                        totalWorkBytes,
                        currentFileName = record.displayName,
                        currentFileCompletedBytes = currentBytes,
                        currentFileTotalBytes = record.sizeBytes
                    )
                )
            }
            onProgress(
                VaultFileOperationProgress(
                    "Export complete",
                    totalWorkBytes,
                    totalWorkBytes,
                    1,
                    1,
                    record.displayName,
                    record.sizeBytes,
                    record.sizeBytes
                )
            )
        } catch (failure: Throwable) {
            // Includes failures from the initial/final progress callback as well
            // as decrypt/write failures; verify that SAF actually removed output.
            rollbackExportDocument(destination, failure)
            throw failure
        }
    }

    @Synchronized
    fun exportTopicToFolder(
        topicId: String,
        destination: DocumentFile,
        onProgress: (VaultFileOperationProgress) -> Unit = {}
    ) {
        val index = loadIndex()
        val topic = index.topics.firstOrNull { it.id == topicId } ?: error("Topic not found.")
        val records = index.files.filter { it.topicId == topicId }
        val folderName = VaultExportLayout.topicFolder(topic).folderName
        val folder = createExactDirectory(destination, folderName)
        try {
            exportRecords(records, folder, onProgress)
        } catch (failure: Throwable) {
            rollbackExportTree(folder, failure)
            throw failure
        }
    }

    /**
     * Exports only the requested active files into a new folder in the selected
     * destination. The folder is rolled back if any file fails to export.
     */
    @Synchronized
    fun exportFilesToFolder(
        fileIds: List<String>,
        destination: DocumentFile,
        onProgress: (VaultFileOperationProgress) -> Unit = {}
    ) {
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
            exportRecords(records, folder, onProgress)
        } catch (failure: Throwable) {
            // Match topic/all-vault export behavior: retain the original failure
            // while reporting any failure to remove partial plaintext exports.
            rollbackExportTree(folder, failure)
            throw failure
        }
    }

    /**
     * Exports the complete vault beneath a single, predictable root folder:
     * private-space-data/<topic name>/<original files>.
     */
    @Synchronized
    fun exportAllToFolder(
        destination: DocumentFile,
        onProgress: (VaultFileOperationProgress) -> Unit = {}
    ) {
        val index = loadIndex()
        val records = index.files
        // Group once instead of rescanning every file for every topic. This keeps
        // full-vault export close to O(topics + files) for large vaults.
        val recordsByTopic = records.groupBy { it.topicId }
        val totalBytes = records.fold(0L) { total, record ->
            Math.addExact(total, record.sizeBytes)
        }
        val totalWorkBytes = Math.multiplyExact(totalBytes, 2L)
        val totalFiles = records.size
        val rootFolder = createExactDirectory(
            destination,
            VaultExportLayout.ALL_TOPICS_ROOT_FOLDER_NAME
        )
        var completedWorkBytes = 0L
        var completedFiles = 0
        try {
            VaultExportLayout.allTopicFolders(index.topics).forEach { topicFolder ->
                val folder = createExactDirectory(rootFolder, topicFolder.folderName)
                val topicRecords = recordsByTopic[topicFolder.topicId].orEmpty()
                val result = exportRecords(
                    topicRecords,
                    folder,
                    onProgress,
                    totalWorkBytes,
                    totalFiles,
                    completedWorkBytes,
                    completedFiles
                )
                completedWorkBytes = result.first
                completedFiles = result.second
            }
            onProgress(
                VaultFileOperationProgress(
                    "Export complete",
                    totalWorkBytes,
                    totalWorkBytes,
                    totalFiles,
                    totalFiles
                )
            )
        } catch (failure: Throwable) {
            rollbackExportTree(rootFolder, failure)
            throw failure
        }
    }

    private fun exportRecordToUri(
        record: VaultFile,
        destination: Uri,
        onProgress: (VaultFileOperationProgress) -> Unit
    ) {
        var temporary: File? = null
        var operationFailure: Throwable? = null
        try {
            val totalWorkBytes = Math.multiplyExact(record.sizeBytes, 2L)
            // Keep the initial callback inside the rollback scope. A cancellation
            // or observer failure can happen before staging has even started,
            // but CreateDocument has already created the external destination.
            onProgress(
                VaultFileOperationProgress(
                    "Decrypting",
                    0L,
                    totalWorkBytes,
                    currentFileName = record.displayName,
                    currentFileCompletedBytes = 0L,
                    currentFileTotalBytes = record.sizeBytes
                )
            )
            temporary = createAuthenticatedStagingCopy(record) { completed ->
                onProgress(
                    VaultFileOperationProgress(
                        "Decrypting",
                        completed,
                        totalWorkBytes,
                        currentFileName = record.displayName,
                        currentFileCompletedBytes = completed,
                        currentFileTotalBytes = record.sizeBytes
                    )
                )
            }
            val output = resolver.openOutputStream(destination, "w")
                ?: error("Android could not open the export destination.")
            output.use { target ->
                FileInputStream(temporary!!).use { input ->
                    VaultExportCopy.copy(input, target, record.sizeBytes) { completed ->
                        onProgress(
                            VaultFileOperationProgress(
                                "Writing export",
                                record.sizeBytes + completed,
                                totalWorkBytes,
                                currentFileName = record.displayName,
                                currentFileCompletedBytes = completed,
                                currentFileTotalBytes = record.sizeBytes
                            )
                        )
                    }
                }
            }
            onProgress(VaultFileOperationProgress("Export complete", totalWorkBytes, totalWorkBytes, 1, 1, record.displayName, record.sizeBytes, record.sizeBytes))
        } catch (failure: Throwable) {
            operationFailure = failure
            rollbackExportDocument(destination, failure)
            throw failure
        } finally {
            temporary?.let { staged ->
                try {
                    deleteTemporaryEntry(staged)
                } catch (cleanupFailure: Throwable) {
                    val primaryFailure = operationFailure
                    if (primaryFailure != null) {
                        primaryFailure.addSuppressed(cleanupFailure)
                    } else {
                        // Do not report a successful export while a plaintext
                        // staging copy could not be removed. Roll back the
                        // approved destination where the provider permits it.
                        runCatching { removeExportDocument(destination) }
                            .onFailure(cleanupFailure::addSuppressed)
                        throw IOException(
                            "Export could not be finalized because temporary plaintext cleanup failed. " +
                                "The exported copy was removed where possible; inspect the destination before retrying.",
                            cleanupFailure
                        )
                    }
                } finally {
                    VaultOutgoingSessionRegistry.markInactive(staged)
                }
            }
        }
    }

    private fun exportRecordToDocument(
        record: VaultFile,
        destination: DocumentFile,
        onProgress: (Long, String) -> Unit
    ) {
        var temporary: File? = null
        var operationFailure: Throwable? = null
        try {
            // Include authenticated staging creation in the rollback scope. If
            // decryption or its progress/cancellation callback fails, remove the
            // already-created destination document as well as any staging copy.
            temporary = createAuthenticatedStagingCopy(record) { completed ->
                onProgress(completed, "Decrypting")
            }
            val output = resolver.openOutputStream(destination.uri, "w")
                ?: error("Android could not open the export destination.")
            output.use { target ->
                FileInputStream(temporary!!).use { input ->
                    VaultExportCopy.copy(input, target, record.sizeBytes) { completed ->
                        onProgress(completed, "Writing export")
                    }
                }
            }
        } catch (failure: Throwable) {
            operationFailure = failure
            rollbackExportDocument(destination, failure)
            throw failure
        } finally {
            temporary?.let { staged ->
                try {
                    deleteTemporaryEntry(staged)
                } catch (cleanupFailure: Throwable) {
                    val primaryFailure = operationFailure
                    if (primaryFailure != null) {
                        primaryFailure.addSuppressed(cleanupFailure)
                    } else {
                        throw cleanupFailure
                    }
                } finally {
                    VaultOutgoingSessionRegistry.markInactive(staged)
                }
            }
        }
    }

    private fun exportRecords(
        records: List<VaultFile>,
        folder: DocumentFile,
        onProgress: (VaultFileOperationProgress) -> Unit,
        totalWorkBytes: Long = Math.multiplyExact(
            records.fold(0L) { total, record -> Math.addExact(total, record.sizeBytes) },
            2L
        ),
        totalFiles: Int = records.size,
        completedWorkBytesBefore: Long = 0L,
        completedFilesBefore: Int = 0
    ): Pair<Long, Int> {
        val usedNames = mutableSetOf<String>()
        var completedWorkBytes = completedWorkBytesBefore
        var completedFiles = completedFilesBefore
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
            onProgress(
                VaultFileOperationProgress(
                    "Decrypting",
                    completedWorkBytes,
                    totalWorkBytes,
                    completedFiles,
                    totalFiles,
                    record.displayName,
                    0L,
                    record.sizeBytes
                )
            )
            exportRecordToDocument(record, target) { currentBytes, phase ->
                val phaseBase = if (phase == "Writing export") record.sizeBytes else 0L
                onProgress(
                    VaultFileOperationProgress(
                        phase,
                        (completedWorkBytes + phaseBase + currentBytes).coerceAtMost(totalWorkBytes),
                        totalWorkBytes,
                        completedFiles,
                        totalFiles,
                        record.displayName,
                        currentBytes,
                        record.sizeBytes
                    )
                )
            }
            completedWorkBytes = Math.addExact(
                completedWorkBytes,
                Math.multiplyExact(record.sizeBytes, 2L)
            )
            completedFiles++
            onProgress(
                VaultFileOperationProgress(
                    "Exporting files",
                    completedWorkBytes,
                    totalWorkBytes,
                    completedFiles,
                    totalFiles,
                    record.displayName,
                    record.sizeBytes,
                    record.sizeBytes
                )
            )
        }
        return completedWorkBytes to completedFiles
    }

    /**
     * Expires handed-off plaintext copies without touching pending sessions.
     * Staging is cleaned at process initialization and by each operation's own
     * finally block; periodic deletion could remove a file still in use.
     */
    @Synchronized
    fun cleanupExpiredOutgoing(now: Long = System.currentTimeMillis()) {
        deleteExpiredChildren(outgoing, now, OUTGOING_TTL_MS)
        persistentStore?.retryPendingPayloadDeletions()
    }

    private fun createExactDirectory(parent: DocumentFile, folderName: String): DocumentFile {
        require(parent.listFiles().none { it.name?.equals(folderName, ignoreCase = true) == true }) {
            "A file or folder named '$folderName' already exists here. Choose another location or rename it."
        }
        val created = parent.createDirectory(folderName)
            ?: error("Could not create the folder '$folderName'. Check the selected location.")
        if (created.name != folderName) {
            val mismatch = IOException(
                "The selected storage provider could not create the folder '$folderName' with its exact name."
            )
            try {
                removeExportDocument(created)
            } catch (cleanupFailure: Throwable) {
                mismatch.addSuppressed(cleanupFailure)
                throw IOException(
                    "The storage provider created an unexpected export folder and it could not be removed. " +
                        "Inspect the selected destination before continuing.",
                    mismatch
                )
            }
            throw mismatch
        }
        return created
    }

    /**
     * Removes a single SAF export item and verifies provider refusal. DocumentFile
     * can return false when a provider declines deletion; silently ignoring that
     * result leaves an incomplete plaintext export behind.
     */
    private fun removeExportDocument(document: DocumentFile) {
        VaultExportCleanup.deleteIfPresent(
            delete = { document.delete() },
            exists = { document.exists() }
        )
    }

    private fun removeExportDocument(destination: Uri) {
        val document = DocumentFile.fromSingleUri(appContext, destination)
            ?: throw IOException("Android could not locate the incomplete export destination for cleanup.")
        removeExportDocument(document)
    }

    private fun rollbackExportDocument(destination: DocumentFile, operationFailure: Throwable) {
        try {
            removeExportDocument(destination)
        } catch (cleanupFailure: Throwable) {
            operationFailure.addSuppressed(cleanupFailure)
            throw IOException(
                "Export failed and its incomplete destination could not be removed. " +
                    "Inspect the selected destination and delete any partial export.",
                operationFailure
            )
        }
    }

    private fun rollbackExportDocument(destination: Uri, operationFailure: Throwable) {
        try {
            removeExportDocument(destination)
        } catch (cleanupFailure: Throwable) {
            operationFailure.addSuppressed(cleanupFailure)
            throw IOException(
                "Export failed and its incomplete destination could not be removed. " +
                    "Inspect the selected destination and delete any partial export.",
                operationFailure
            )
        }
    }

    private fun deleteDocumentTree(directory: DocumentFile) {
        var firstFailure: Throwable? = null
        val children = try {
            directory.listFiles().toList()
        } catch (failure: Throwable) {
            firstFailure = failure
            emptyList()
        }
        children.forEach { child ->
            try {
                if (child.isDirectory) {
                    deleteDocumentTree(child)
                } else if (!child.delete() && child.exists()) {
                    throw IOException("Could not remove partial export file '${child.name ?: "unknown"}'.")
                }
            } catch (failure: Throwable) {
                val previous = firstFailure
                if (previous == null) firstFailure = failure else previous.addSuppressed(failure)
            }
        }
        try {
            if (!directory.delete() && directory.exists()) {
                throw IOException("Could not remove partial export folder '${directory.name ?: "unknown"}'.")
            }
        } catch (failure: Throwable) {
            val previous = firstFailure
            if (previous == null) firstFailure = failure else previous.addSuppressed(failure)
        }
        firstFailure?.let { throw IOException("The partial export could not be completely removed.", it) }
    }

    private fun rollbackExportTree(directory: DocumentFile, exportFailure: Throwable) {
        try {
            deleteDocumentTree(directory)
        } catch (cleanupFailure: Throwable) {
            exportFailure.addSuppressed(cleanupFailure)
            throw IOException(
                "Export failed and some partial files could not be removed. Inspect the destination folder and delete any incomplete exports.",
                exportFailure
            )
        }
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

    private fun createAuthenticatedStagingCopy(
        record: VaultFile,
        onProgress: ((Long) -> Unit)? = null
    ): File {
        val target = File(staging, "${UUID.randomUUID()}.tmp")
        VaultOutgoingSessionRegistry.markActive(target)
        try {
            decryptInto(record, target, onProgress)
            return target
        } catch (failure: Throwable) {
            try {
                runCatching { deleteTemporaryEntry(target) }.onFailure(failure::addSuppressed)
            } finally {
                VaultOutgoingSessionRegistry.markInactive(target)
            }
            throw failure
        }
    }

    private fun decryptInto(
        record: VaultFile,
        target: File,
        onProgress: ((Long) -> Unit)? = null
    ) {
        val source = encryptedFile(record.id)
        require(source.isFile) { "The encrypted file is missing from the vault." }
        try {
            FileInputStream(source).use { encrypted ->
                FileOutputStream(target).use { clear ->
                    restrictFilePermissions(target)
                    val exactLength = ExactLengthOutputStream(clear, record.sizeBytes)
                    val output = if (onProgress == null) exactLength else ProgressOutputStream(exactLength) { completed ->
                        try {
                            onProgress(completed)
                        } catch (cancelled: java.util.concurrent.CancellationException) {
                            // Cancellation must interrupt long decryptions promptly.
                            // Ordinary observer/UI failures remain isolated from
                            // the integrity-checked storage operation.
                            throw cancelled
                        } catch (_: Exception) {
                            // Progress reporting must never compromise file integrity.
                        }
                    }
                    VaultCrypto.decrypt(encrypted, output, fileAad(record.id))
                    // A valid authentication tag is necessary but the authenticated
                    // index size is also an invariant. Do not expose, share, or export
                    // a decrypted file unless both checks agree exactly.
                    exactLength.finish()
                    clear.fd.sync()
                }
            }
        } catch (failure: Throwable) {
            // A failed authentication or length check must never leave a partial
            // plaintext copy available to a later viewer/share operation.
            runCatching { deleteTemporaryEntry(target) }
                .onFailure(failure::addSuppressed)
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
        // Only snapshot the local index when an active recovery mirror may
        // need rollback. In particular, recovery restore can rebuild a damaged
        // local index while its newly opened store is still a local variable.
        val activePersistentStore = persistentStore
        val previousBytes = activePersistentStore?.let { serializeIndex(loadIndex()) }
        val bytes = serializeIndex(index, restoreTransactionId)
        try {
            val persistentSnapshot = activePersistentStore?.saveIndex(
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
                        val rollback = activePersistentStore.saveIndex(
                            checkNotNull(previousBytes) {
                                "The previous authenticated index is unavailable for recovery rollback."
                            },
                            METADATA_AAD.toByteArray(Charsets.UTF_8)
                        )
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
            previousBytes?.fill(0)
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
        expectedPlainBytes: Long,
        checkCancelled: () -> Unit = {}
    ) {
        val aad = fileAad(id)
        FileInputStream(localEncrypted).use { rawCiphertext ->
            val checkedCiphertext = CancellationCheckingInputStream(rawCiphertext, checkCancelled)
            store.writePayload(id, expectedPlainBytes, aad, checkCancelled) { recoveryEncryptor ->
                VaultCrypto.decrypt(checkedCiphertext, recoveryEncryptor, aad)
            }
        }
    }


    private fun notifyRestoreProgress(
        onProgress: (VaultRestoreProgress) -> Unit,
        phase: String
    ) {
        try {
            onProgress(
                VaultRestoreProgress(
                    completedBytes = 0L,
                    totalBytes = 0L,
                    completedFiles = 0,
                    totalFiles = 0,
                    currentFileName = "",
                    phase = phase
                )
            )
        } catch (cancelled: java.util.concurrent.CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Progress/UI observer failures must not compromise authenticated storage work.
        }
    }

    /** Rebuilds the private working copy from authenticated recovery records. */
    private fun restoreWorkingVault(
        index: VaultIndex,
        store: PersistentVaultStore,
        onProgress: (VaultRestoreProgress) -> Unit
    ) {
        notifyRestoreProgress(onProgress, "Validating encrypted recovery index")
        validateIndexForSave(index)
        recoverInterruptedRestoreFiles()
        val records = index.files + index.trash.map { it.file }
        val recoverySize = store.restoreSizeForPayloads(records.map { it.id }.toSet())
        val totalBytes = recoverySize.totalBytes
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
        var completedBytes = recoverySize.completedBytesBeforePayloads
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
                try {
                    onProgress(progress)
                } catch (cancelled: java.util.concurrent.CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // Observer/UI failures remain isolated; explicit cancellation propagates.
                }
            }
        }
        reportProgress(
            VaultRestoreProgress(
                completedBytes = completedBytes,
                totalBytes = totalBytes,
                completedFiles = 0,
                totalFiles = records.size,
                currentFileName = "",
                phase = "Preparing encrypted restore"
            )
        )
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
                                    val exactOutput = ExactLengthOutputStream(
                                        destination = localEncryptor,
                                        expectedBytes = record.sizeBytes
                                    )
                                    store.decryptPayload(
                                        id = record.id,
                                        output = exactOutput,
                                        aad = fileAad(record.id),
                                        onEncryptedBytesRead = { encryptedBytesRead ->
                                            val currentCompleted = try {
                                                Math.addExact(completedBytes, encryptedBytesRead)
                                            } catch (failure: ArithmeticException) {
                                                throw IOException("Recovery progress exceeds the supported range.", failure)
                                            }
                                            reportProgress(
                                                VaultRestoreProgress(
                                                    completedBytes = currentCompleted.coerceAtMost(totalBytes),
                                                    totalBytes = totalBytes,
                                                    completedFiles = recordIndex,
                                                    totalFiles = records.size,
                                                    currentFileName = record.displayName,
                                                    phase = "Restoring encrypted files"
                                                )
                                            )
                                        }
                                    )
                                    exactOutput.finish()
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
                    val restoredPayloadBytes = recoverySize.payloadBytesById[record.id]
                        ?: throw IOException("The encrypted recovery file size is missing.")
                    completedBytes = try {
                        Math.addExact(completedBytes, restoredPayloadBytes)
                    } catch (failure: ArithmeticException) {
                        throw IOException("Recovery progress exceeds the supported range.", failure)
                    }
                    reportProgress(
                        VaultRestoreProgress(
                            completedBytes = completedBytes,
                            totalBytes = totalBytes,
                            completedFiles = recordIndex + 1,
                            totalFiles = records.size,
                            currentFileName = record.displayName,
                            phase = "File restored securely"
                        )
                    )
                } catch (failure: Throwable) {
                    runCatching { deleteTemporaryEntry(incoming) }
                        .onFailure(failure::addSuppressed)
                    throw failure
                }
            }
            reportProgress(
                VaultRestoreProgress(
                    completedBytes = totalBytes,
                    totalBytes = totalBytes,
                    completedFiles = records.size,
                    totalFiles = records.size,
                    currentFileName = "",
                    phase = "Finalizing restored vault"
                )
            )
            // Persist a small rollback journal before the first live payload is
            // replaced. It lets the next process distinguish a committed restore
            // from a process death halfway through promotion.
            val restoreTransactionId = writeRestoreTransaction(index, records)
            incomingFiles.forEach { (id, incoming) ->
                reportProgress(
                    VaultRestoreProgress(
                        completedBytes = totalBytes,
                        totalBytes = totalBytes,
                        completedFiles = records.size,
                        totalFiles = records.size,
                        currentFileName = "",
                        phase = "Installing restored files"
                    )
                )
                replaceRestoredPayload(id, incoming)
            }
            reportProgress(
                VaultRestoreProgress(
                    completedBytes = totalBytes,
                    totalBytes = totalBytes,
                    completedFiles = records.size,
                    totalFiles = records.size,
                    currentFileName = "",
                    phase = "Committing restored vault"
                )
            )
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
                if (column >= 0 && cursor.moveToFirst()) {
                    VaultInputValidation.reportedSize(
                        cursor.getLong(column),
                        cursor.isNull(column)
                    )
                } else {
                    null
                }
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
        // Some third-party document providers throw while resolving MIME metadata
        // even though their content stream is readable. MIME is only a viewer hint;
        // a provider failure should not prevent an otherwise valid encrypted import.
        val providerMimeType = runCatching { resolver.getType(uri) }.getOrNull()
        return VaultInputValidation.mimeType(providerMimeType, fallback)
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

    private fun createOutgoingSession(): File {
        val session = createPrivateDirectory(outgoing, UUID.randomUUID().toString())
        VaultOutgoingSessionRegistry.markActive(session)
        try {
            // Created before plaintext is written; startup removes sessions that
            // never reached an external app hand-off.
            createPrivateFile(File(session, VaultOutgoingSessionPolicy.PENDING_MARKER))
            syncDirectory(session)
            return session
        } catch (failure: Throwable) {
            VaultOutgoingSessionRegistry.markInactive(session)
            runCatching { deleteTemporaryEntry(session) }.onFailure(failure::addSuppressed)
            throw failure
        }
    }

    private fun cleanupAbandonedOutgoingSessions() {
        outgoing.listFiles()?.forEach { session ->
            if (VaultOutgoingSessionPolicy.shouldDeleteAtStartup(session)) {
                deleteTemporaryEntry(session)
            }
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
        if (!file.exists()) return
        deleteTemporaryEntry(file)
        syncDirectory(root)
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
        // Every .psv.tmp file was left by an interrupted prior process and cannot be resumed.
        // Do not silently expose a vault when known residual data cannot be deleted.
        root.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".psv.tmp") }
            ?.forEach(::deleteTemporaryEntry)
    }

    private fun deleteExpiredChildren(directory: File, now: Long, ttlMs: Long) {
        directory.listFiles()?.forEach { child ->
            if (directory == outgoing && VaultOutgoingSessionPolicy.isPendingSession(child)) {
                return@forEach
            }
            val modified = child.lastModified()
            if (VaultOutgoingSessionPolicy.isExpired(modified, now, ttlMs)) {
                try {
                    deleteTemporaryEntry(child)
                } catch (failure: Exception) {
                    throw IOException(
                        "Private Space could not remove an expired decrypted temporary copy. " +
                            "Keep the app open and retry cleanup before sharing or opening more files.",
                        failure
                    )
                }
            }
        }
    }


}
