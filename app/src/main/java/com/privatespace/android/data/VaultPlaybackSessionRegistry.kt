package com.privatespace.android.data

import java.io.Closeable
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-local registry for explicitly authorized external playback sessions.
 *
 * A session owns one authenticated random-access reader. The provider receives
 * only an opaque random token; it never accepts a vault file ID from an external
 * caller. Revocation closes the underlying encrypted file and wipes its cached
 * plaintext frame.
 */
internal object VaultPlaybackSessionRegistry {
    private val sessions = ConcurrentHashMap<String, VaultPlaybackSession>()

    fun register(session: VaultPlaybackSession): String {
        val token = UUID.randomUUID().toString()
        check(sessions.putIfAbsent(token, session) == null)
        return token
    }

    fun acquire(token: String): VaultPlaybackSession? = sessions[token]

    fun revoke(token: String) {
        sessions.remove(token)?.close()
    }

    fun revokeAll() {
        val active = sessions.entries.toList()
        active.forEach { (token, session) ->
            if (sessions.remove(token, session)) runCatching { session.close() }
        }
    }
}

internal class VaultPlaybackSession(
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    private val reader: AutoCloseable,
    private val read: (Long, ByteArray, Int, Int) -> Int
) : Closeable {
    @Volatile private var closed = false

    @Synchronized
    @Throws(IOException::class)
    fun readAt(position: Long, target: ByteArray, offset: Int, length: Int): Int {
        if (closed) throw IOException("This private playback session has ended.")
        return try {
            read(position, target, offset, length)
        } catch (failure: IOException) {
            throw failure
        } catch (failure: Exception) {
            throw IOException("Could not read the encrypted video.", failure)
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        runCatching { reader.close() }
    }
}
