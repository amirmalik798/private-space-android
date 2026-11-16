package com.privatespace.android.data

import android.content.Context
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
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.UUID

data class VaultSnapshot(
    val topics: List<VaultTopic>,
    val files: List<VaultFile>
)

data class PreparedVaultFile(
    val file: File,
    val mimeType: String
)

private data class VaultIndex(
    val topics: List<VaultTopic> = emptyList(),
    val files: List<VaultFile> = emptyList()
)

class VaultRepository(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val root = File(appContext.filesDir, "vault").apply { mkdirs() }
    private val staging = File(appContext.cacheDir, "private-space-staging").apply { mkdirs() }
    private val outgoing = File(appContext.cacheDir, "outgoing").apply { mkdirs() }
    private val legacyPrefs = appContext.getSharedPreferences("private_space_vault", Context.MODE_PRIVATE)
    private val metadata = AtomicFile(File(root, "vault-index.enc"))
    private var initializationFailure: Throwable? = null

    companion object {
        private const val MAX_FILE_BYTES = 2L * 1024L * 1024L * 1024L
        private const val MAX_METADATA_BYTES = 16 * 1024 * 1024
        private const val OUTGOING_TTL_MS = 30L * 60L * 1000L
        private const val METADATA_AAD = "private-space:metadata:v2"
    }

    init {
        try {
            migrateLegacyMetadataIfNeeded()
            removeAbandonedTemporaryFiles()
            // Export staging is never needed after a process restart.
            staging.listFiles()?.forEach { it.deleteRecursively() }
        } catch (failure: Throwable) {
            // Surface recovery guidance in the UI instead of crashing before
            // the user can read it. Never overwrite the original index.
            initializationFailure = failure
        }
    }

    @Synchronized
    fun snapshot(): VaultSnapshot {
        val index = loadIndex()
        // Count the physical encrypted containers, not the original source sizes.
        // This is the storage occupied by every saved document/photo in the vault.
        val encryptedLengths = index.files.associate { record ->
            val file = encryptedFile(record.id)
            record.id to if (file.isFile) file.length() else 0L
        }
        return VaultSnapshot(
            topics = index.topics,
            files = index.files,
            storageUsage = VaultStorageCalculator.calculate(
                index.topics,
                index.files,
                encryptedLengths
            )
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
        require(reportedSize == null || reportedSize <= MAX_FILE_BYTES) {
            "Files larger than 2 GB cannot be added."
        }
        val minimumSpace = (reportedSize ?: (1L shl 20)) + (1L shl 20)
        require(root.usableSpace > minimumSpace) { "There is not enough free storage for this file." }

        val id = UUID.randomUUID().toString()
        val temporary = File(root, "$id.psv.tmp")
        val target = encryptedFile(id)

        try {
            val source = resolver.openInputStream(uri) ?: error("Android could not open the selected file.")
            var byteCount = 0L
            source.use { raw ->
                val bounded = CountingInputStream(raw, MAX_FILE_BYTES)
                FileOutputStream(temporary).use { encrypted ->
                    VaultCrypto.encrypt(bounded, encrypted, fileAad(id))
                    encrypted.fd.sync()
                    byteCount = bounded.bytesRead
                }
            }

            check(temporary.renameTo(target)) { "Could not finalize the encrypted file." }
            val now = System.currentTimeMillis()
            val record = VaultFile(id, topicId, safeName, mime, byteCount, now)
            val changedTopics = index.topics.map {
                if (it.id == topicId) it.copy(updatedAt = now) else it
            }
            try {
                saveIndex(index.copy(topics = changedTopics, files = index.files + record))
            } catch (failure: Throwable) {
                target.delete()
                throw failure
            }
            return record
        } catch (failure: Throwable) {
            temporary.delete()
            target.delete()
            throw failure
        }
    }

    @Synchronized
    fun deleteFile(id: String) {
        val index = loadIndex()
        val record = index.files.firstOrNull { it.id == id } ?: return
        val now = System.currentTimeMillis()
        val updatedTopics = index.topics.map {
            if (it.id == record.topicId) it.copy(updatedAt = now) else it
        }
        saveIndex(index.copy(topics = updatedTopics, files = index.files.filterNot { it.id == id }))
        encryptedFile(id).delete()
    }

    @Synchronized
    fun deleteTopic(id: String) {
        val index = loadIndex()
        if (index.topics.none { it.id == id }) return
        val removedFiles = index.files.filter { it.topicId == id }
        saveIndex(
            index.copy(
                topics = index.topics.filterNot { it.id == id },
                files = index.files.filterNot { it.topicId == id }
            )
        )
        removedFiles.forEach { encryptedFile(it.id).delete() }
    }

    /**
     * Decrypts to a unique private cache file. The caller must keep it only as
     * long as needed; outgoing copies are removed automatically after 30 minutes.
     */
    fun prepareDecryptedFile(id: String): PreparedVaultFile {
        val record = findFile(id)
        val session = File(outgoing, UUID.randomUUID().toString()).apply {
            check(mkdirs()) { "Could not create a temporary sharing folder." }
        }
        val destination = File(session, VaultInputValidation.displayName(record.displayName))
        try {
            decryptInto(record, destination)
            return PreparedVaultFile(destination, record.mimeType)
        } catch (failure: Throwable) {
            session.deleteRecursively()
            throw failure
        }
    }

    /** Prepares each topic file independently; a failure removes the whole temporary share set. */
    fun prepareTopicShare(topicId: String): List<PreparedVaultFile> {
        val index = synchronized(this) { loadIndex() }
        val records = index.files.filter { it.topicId == topicId }
        require(index.topics.any { it.id == topicId }) { "Topic not found." }
        require(records.isNotEmpty()) { "This topic has no files to share." }

        val session = File(outgoing, UUID.randomUUID().toString()).apply {
            check(mkdirs()) { "Could not create a temporary sharing folder." }
        }
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

    fun exportTopicToFolder(topicId: String, destination: DocumentFile) {
        val index = synchronized(this) { loadIndex() }
        val topic = index.topics.firstOrNull { it.id == topicId } ?: error("Topic not found.")
        val records = index.files.filter { it.topicId == topicId }
        val folder = destination.createDirectory(topic.name)
            ?: error("Could not create the topic export folder. Check whether it already exists.")
        try {
            exportRecords(records, folder)
        } catch (failure: Throwable) {
            folder.delete()
            throw failure
        }
    }

    fun exportAllToFolder(destination: DocumentFile) {
        val index = synchronized(this) { loadIndex() }
        val createdFolders = mutableListOf<DocumentFile>()
        try {
            index.topics.forEach { topic ->
                val folder = destination.createDirectory(topic.name)
                    ?: error("Could not create the export folder for '${topic.name}'. Check whether it already exists.")
                createdFolders += folder
                exportRecords(index.files.filter { it.topicId == topic.id }, folder)
            }
        } catch (failure: Throwable) {
            createdFolders.asReversed().forEach { it.delete() }
            throw failure
        }
    }

    /** Deletes stale decrypted staging files, but keeps recent share payloads available to receiving apps. */
    fun cleanupExpiredOutgoing(now: Long = System.currentTimeMillis()) {
        deleteExpiredChildren(outgoing, now)
        deleteExpiredChildren(staging, now)
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

    @Synchronized
    private fun loadIndex(): VaultIndex {
        initializationFailure?.let {
            throw IllegalStateException(
                it.message ?: "Vault initialization failed. Preserve app data and do not clear storage.",
                it
            )
        }
        val hasBase = metadata.baseFile.exists()
        val hasBackup = File(metadata.baseFile.path + ".bak").exists()
        if (!hasBase && !hasBackup) return VaultIndex()

        val output = ByteArrayOutputStream()
        metadata.openRead().use { encrypted ->
            VaultCrypto.decrypt(encrypted, output, METADATA_AAD.toByteArray(Charsets.UTF_8))
        }
        val clear = output.toByteArray()
        require(clear.size <= MAX_METADATA_BYTES) { "Vault index is larger than the supported limit." }
        val json = JSONObject(String(clear, Charsets.UTF_8))
        require(json.optInt("version") == 2) { "Unsupported vault index version." }

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

        val topicIds = topics.map { it.id }.toSet()
        val fileArray = json.getJSONArray("files")
        val files = (0 until fileArray.length()).map { index ->
            val item = fileArray.getJSONObject(index)
            val id = item.getString("id")
            val topicId = item.getString("topic")
            UUID.fromString(id)
            UUID.fromString(topicId)
            require(topicId in topicIds) { "Vault index contains a file with no topic." }
            val size = item.getLong("size")
            require(size >= 0L) { "Vault index contains an invalid file size." }
            VaultFile(
                id = id,
                topicId = topicId,
                displayName = VaultInputValidation.displayName(item.getString("name")),
                mimeType = VaultInputValidation.mimeType(item.getString("mime")),
                sizeBytes = size,
                addedAt = item.getLong("added")
            )
        }
        return VaultIndex(topics, files)
    }

    @Synchronized
    private fun saveIndex(index: VaultIndex) {
        val topicIds = index.topics.map { it.id }.toSet()
        require(index.files.all { it.topicId in topicIds }) { "Cannot save a file without a valid topic." }

        val json = JSONObject().put("version", 2)
        val topicArray = JSONArray()
        index.topics.forEach { topic ->
            topicArray.put(
                JSONObject()
                    .put("id", topic.id)
                    .put("name", topic.name)
                    .put("created", topic.createdAt)
                    .put("updated", topic.updatedAt)
            )
        }
        val fileArray = JSONArray()
        index.files.forEach { file ->
            fileArray.put(
                JSONObject()
                    .put("id", file.id)
                    .put("topic", file.topicId)
                    .put("name", file.displayName)
                    .put("mime", file.mimeType)
                    .put("size", file.sizeBytes)
                    .put("added", file.addedAt)
            )
        }
        json.put("topics", topicArray).put("files", fileArray)
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

    private fun removeAbandonedTemporaryFiles() {
        root.listFiles()?.filter { it.name.endsWith(".tmp") }?.forEach { file ->
            if (System.currentTimeMillis() - file.lastModified() > OUTGOING_TTL_MS) file.delete()
        }
    }

    private fun deleteExpiredChildren(directory: File, now: Long) {
        directory.listFiles()?.forEach { child ->
            if (now - child.lastModified() > OUTGOING_TTL_MS) child.deleteRecursively()
        }
    }

    private class CountingInputStream(
        input: InputStream,
        private val maxBytes: Long
    ) : FilterInputStream(input) {
        var bytesRead: Long = 0L
            private set

        private fun add(count: Int) {
            if (count <= 0) return
            bytesRead += count.toLong()
            if (bytesRead > maxBytes) throw IOException("Files larger than 2 GB cannot be added.")
        }

        override fun read(): Int {
            val result = super.read()
            if (result >= 0) add(1)
            return result
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = super.read(buffer, offset, length)
            add(count)
            return count
        }
    }
}
