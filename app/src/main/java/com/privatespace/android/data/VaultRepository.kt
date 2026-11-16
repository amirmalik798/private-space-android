package com.privatespace.android.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
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
import java.io.IOException
import java.util.Locale
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
    private val legacyPrefs = appContext.getSharedPreferences("private_space_vault", Context.MODE_PRIVATE)
    private val metadata = AtomicFile(File(root, "vault-index.enc"))
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
        private const val INCOMPLETE_IMPORT_TTL_MS = 7L * 24L * 60L * 60L * 1000L
        private const val METADATA_AAD = "private-space:metadata:v2"
        private const val PRIVATE_DIRECTORY_MODE = 0x1C0 // 0700
        private const val PRIVATE_FILE_MODE = 0x180 // 0600
        private const val FILE_DELETE_SUFFIX = ".psv.delete"
    }

    init {
        try {
            ensurePrivateDirectory(root)
            ensurePrivateDirectory(staging)
            ensurePrivateDirectory(outgoing)
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
            val overhead = VaultCrypto.VERSIONED_CONTAINER_OVERHEAD_BYTES.toLong()
            require(reportedSize <= available && available - reportedSize >= overhead) {
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
            session.deleteRecursively()
            throw failure
        }
    }

    /**
     * Creates an authenticated in-memory image/video preview from a short-lived
     * private plaintext file. No preview or duration is written to the index.
     * The temporary file is removed even when decoding fails; encrypted records
     * and original bytes are never modified.
     */
    @Synchronized
    fun loadMediaPreview(id: String, maxDimensionPx: Int): VaultMediaPreview? {
        val index = loadIndex()
        val record = index.files.firstOrNull { it.id == id }
            ?: index.trash.firstOrNull { it.file.id == id }?.file
            ?: return null

        val isImage = VaultFormatSupport.isImageMimeType(record.mimeType)
        val isVideo = VaultFormatSupport.isVideoMimeType(record.mimeType)
        if (!isImage && !isVideo) return null

        val target = File(staging, "media-preview-${UUID.randomUUID()}.tmp")
        try {
            // decryptInto returns only after the AES-GCM authentication tag is verified.
            decryptInto(record, target)
            return if (isImage) {
                VaultMediaPreview(decodeImageThumbnail(target, maxDimensionPx))
            } else {
                decodeVideoPreview(target, maxDimensionPx)
            }
        } finally {
            target.delete()
        }
    }

    private fun decodeImageThumbnail(source: File, maxDimensionPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            inScaled = false
        }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = false
            inScaled = false
            inDither = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = VaultThumbnailPolicy.calculateSampleSize(
                bounds.outWidth,
                bounds.outHeight,
                maxDimensionPx.coerceIn(32, 512)
            )
        }
        return BitmapFactory.decodeFile(source.absolutePath, options)
    }

    private fun decodeVideoPreview(source: File, maxDimensionPx: Int): VaultMediaPreview {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(source.absolutePath)
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

            val bitmap = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    retriever.getScaledFrameAtTime(
                        -1L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        dimensions.first,
                        dimensions.second
                    )
                } else {
                    val fullFrame = retriever.getFrameAtTime(
                        -1L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                    ) ?: return@runCatching null
                    val target = VaultThumbnailPolicy.fitWithin(fullFrame.width, fullFrame.height, safeTarget)
                    val scaled = Bitmap.createScaledBitmap(fullFrame, target.first, target.second, true)
                    if (scaled !== fullFrame && !fullFrame.isRecycled) fullFrame.recycle()
                    scaled
                }
            }.getOrNull()

            return VaultMediaPreview(bitmap, durationMs)
        } finally {
            runCatching { retriever.release() }
        }
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
            session.deleteRecursively()
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
            temporary.delete()
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
            temporary.delete()
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
            target.delete()
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
                    VaultCrypto.decrypt(encrypted, clear, fileAad(record.id))
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
    private fun saveIndex(index: VaultIndex) {
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
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_METADATA_BYTES) { "Vault index has reached its supported size limit." }

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
        } finally {
            bytes.fill(0)
        }
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
        Os.chmod(directory.absolutePath, PRIVATE_DIRECTORY_MODE)
        return directory
    }

    private fun restrictFilePermissions(file: File) {
        Os.chmod(file.absolutePath, PRIVATE_FILE_MODE)
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

    private fun removeAbandonedTemporaryFiles(now: Long = System.currentTimeMillis()) {
        // Keep any active/slow import temp file. Interrupted imports are
        // encrypted and reclaimed after a conservative age threshold.
        root.listFiles()?.filter { it.name.endsWith(".psv.tmp") }?.forEach { file ->
            val modified = file.lastModified()
            if (modified > 0L && now >= modified && now - modified > INCOMPLETE_IMPORT_TTL_MS) {
                file.delete()
            }
        }
    }

    private fun deleteExpiredChildren(directory: File, now: Long, ttlMs: Long) {
        directory.listFiles()?.forEach { child ->
            val modified = child.lastModified()
            if (modified > 0L && now >= modified && now - modified > ttlMs) {
                child.deleteRecursively()
            }
        }
    }


}
