package com.privatespace.android.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Reclaims outgoing plaintext after process death as well as ordinary app use.
 * Pending sessions still active in this process are protected by the registry;
 * abandoned pending sessions and handed-off sessions past the existing TTL
 * are removed without opening or unlocking the vault.
 */
internal class OutgoingCacheCleanupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val outgoing = File(applicationContext.cacheDir, "outgoing")
            val sessions = if (!outgoing.exists()) {
                emptyArray()
            } else {
                outgoing.listFiles()
                    ?: throw IOException("Could not enumerate the outgoing cache for cleanup.")
            }
            val now = System.currentTimeMillis()

            sessions.forEach { session ->
                val active = VaultOutgoingSessionRegistry.isActive(session)
                if (!VaultOutgoingSessionPolicy.shouldDeleteInBackground(
                        session = session,
                        now = now,
                        ttlMs = OUTGOING_TTL_MS,
                        activeInCurrentProcess = active
                    )
                ) {
                    return@forEach
                }

                if (session.exists() && !session.deleteRecursively() && session.exists()) {
                    throw IOException("Could not remove an expired or abandoned outgoing session.")
                }
                VaultOutgoingSessionRegistry.markInactive(session)
            }

            // This cache contains only decrypted viewer, preview and export
            // staging files. Unleased entries are abandoned once the owning
            // operation finishes or the process that owned them is killed.
            val staging = File(applicationContext.cacheDir, "private-space-staging")
            val stagedItems = if (!staging.exists()) {
                emptyArray()
            } else {
                staging.listFiles()
                    ?: throw IOException("Could not enumerate the private staging cache for cleanup.")
            }
            stagedItems.forEach { staged ->
                if (VaultOutgoingSessionRegistry.isActive(staged)) return@forEach
                if (staged.exists() && !staged.deleteRecursively() && staged.exists()) {
                    throw IOException("Could not remove an abandoned private staging item.")
                }
                VaultOutgoingSessionRegistry.markInactive(staged)
            }

            // Interrupted imports leave only an incomplete, encrypted .psv.tmp
            // file in the main vault directory. Never scan/delete committed .psv
            // records or restore journals here; startup recovery owns those.
            val vaultRoot = File(applicationContext.filesDir, "vault")
            val importTemps = if (!vaultRoot.exists()) {
                emptyList()
            } else {
                vaultRoot.listFiles()
                    ?.filter { it.isFile && it.name.endsWith(".psv.tmp") }
                    ?: throw IOException("Could not enumerate interrupted encrypted imports.")
            }
            importTemps.forEach { temporary ->
                if (VaultOutgoingSessionRegistry.isActive(temporary)) return@forEach
                if (temporary.exists() && !temporary.delete() && temporary.exists()) {
                    throw IOException("Could not remove an interrupted encrypted import.")
                }
                VaultOutgoingSessionRegistry.markInactive(temporary)
            }
            Result.success()
        } catch (_: IOException) {
            Result.retry()
        } catch (_: SecurityException) {
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "private-space-outgoing-cache-cleanup"
        private const val OUTGOING_TTL_MS = 30L * 60L * 1_000L

        fun ensureScheduled(context: Context) {
            val request = PeriodicWorkRequestBuilder<OutgoingCacheCleanupWorker>(
                15,
                TimeUnit.MINUTES
            ).build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
