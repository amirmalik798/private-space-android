package com.privatespace.android.data

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.StorageManager
import android.os.ProxyFileDescriptorCallback
import android.provider.OpenableColumns
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Read-only random-access bridge for external media players.
 *
 * The URI contains an unguessable, process-local session token. The provider
 * does not accept arbitrary file IDs or paths. Access is granted only for an
 * explicitly created session and only to callers holding the temporary URI
 * permission. Proxy descriptors are available on API 26+.
 */
class VaultPlaybackProvider : ContentProvider() {
    private val ioExecutor: ExecutorService by lazy {
        Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "VaultPlaybackRead").apply { isDaemon = true }
        }
    }

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? =
        sessionFor(uri)?.mimeType

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val session = sessionFor(uri) ?: throw FileNotFoundException("Playback session expired.")
        val requested = projection?.toList()
            ?: listOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val columns = requested.filter {
            it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE
        }.ifEmpty { listOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE) }
        val cursor = MatrixCursor(columns.toTypedArray(), 1)
        cursor.addRow(columns.map { column ->
            when (column) {
                OpenableColumns.DISPLAY_NAME -> session.displayName
                OpenableColumns.SIZE -> session.sizeBytes
                else -> null
            }
        })
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Playback URI is read-only.")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            throw FileNotFoundException("Random-access playback is not supported on this Android version.")
        }
        val token = tokenFor(uri) ?: throw FileNotFoundException("Invalid playback URI.")
        val session = sessionFor(uri) ?: throw FileNotFoundException("Playback session expired.")
        val storage = context?.getSystemService(StorageManager::class.java)
            ?: throw FileNotFoundException("Storage manager is unavailable.")

        val callback = object : ProxyFileDescriptorCallback() {
            override fun onGetSize(): Long {
                ensureActive(token, session)
                return session.sizeBytes
            }

            override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                ensureActive(token, session)
                if (offset < 0L || size < 0 || size > data.size) {
                    throw IOException("Invalid playback read range.")
                }
                if (offset >= session.sizeBytes) return 0
                val requested = minOf(size.toLong(), session.sizeBytes - offset).toInt()
                val count = session.readAt(offset, data, 0, requested)
                return if (count < 0) 0 else count
            }

            override fun onWrite(offset: Long, size: Int, data: ByteArray): Int =
                throw IOException("Private playback content is read-only.")

            override fun onFsync() {
                ensureActive(token, session)
            }

            override fun onRelease() {
                // A media player may close and reopen descriptors while seeking.
                // The URI token owns the session; closing one descriptor must not
                // prematurely invalidate other descriptors for the same session.
            }
        }

        return storage.openProxyFileDescriptor(
            ParcelFileDescriptor.MODE_READ_ONLY,
            callback,
            ioExecutor
        )
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Private playback content is read-only.")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Private playback content is read-only.")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = throw UnsupportedOperationException("Private playback content is read-only.")

    override fun shutdown() {
        VaultPlaybackSessionRegistry.revokeAll()
        ioExecutor.shutdownNow()
        super.shutdown()
    }

    private fun sessionFor(uri: Uri): VaultPlaybackSession? =
        tokenFor(uri)?.let(VaultPlaybackSessionRegistry::acquire)

    private fun tokenFor(uri: Uri): String? {
        if (uri.scheme != "content" ||
            uri.authority != AUTHORITY ||
            uri.pathSegments.size != 1
        ) return null
        return uri.lastPathSegment?.takeIf { it.length == 36 }
    }

    private fun ensureActive(token: String, expected: VaultPlaybackSession) {
        if (VaultPlaybackSessionRegistry.acquire(token) !== expected) {
            throw IOException("Private playback session has been revoked.")
        }
    }

    companion object {
        const val AUTHORITY = "com.privatespace.android.playback"
    }
}
