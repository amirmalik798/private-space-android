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

data class PreparedVaultPlayback(
    val uri: Uri,
    val mimeType: String,
    val sessionToken: String
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