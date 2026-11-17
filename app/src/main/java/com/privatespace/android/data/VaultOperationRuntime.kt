package com.privatespace.android.data

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Owns user-visible file operations independently of an Activity/Compose
 * lifecycle. Explicit vault locking cancels operations across Activity
 * recreation through [cancelAll].
 *
 * The foreground service raises process importance while work is active; it
 * cannot prevent Android from terminating the process. No vault key or
 * plaintext is persisted by this coordinator.
 */
object VaultOperationRuntime {
    private data class ActiveOperation(
        val job: Job,
        val onCancel: () -> Boolean
    )

    private val lock = Any()
    private val operations = linkedMapOf<String, ActiveOperation>()
    private val exceptionHandler = CoroutineExceptionHandler { _, failure ->
        if (failure !is CancellationException) {
            Log.e(TAG, "An uncaught vault operation failed.", failure)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + exceptionHandler)

    /**
     * [onCancel] returns true when the operation can be cancelled immediately.
     * Return false when it has crossed a commit boundary and must finish its
     * current atomic transaction before stopping.
     */
    fun launch(
        context: Context,
        title: String,
        onCancel: () -> Boolean = { true },
        block: suspend CoroutineScope.() -> Unit
    ): Job {
        val appContext = context.applicationContext
        val operationId = UUID.randomUUID().toString()
        startForegroundService(appContext, title)

        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } finally {
                finish(appContext, operationId)
            }
        }
        synchronized(lock) {
            operations[operationId] = ActiveOperation(job, onCancel)
        }
        job.start()
        return job
    }

    /**
     * Called before the vault session is invalidated. It reaches operations
     * started by an older Activity instance as well as the current screen.
     */
    fun cancelAll() {
        val snapshot = synchronized(lock) { operations.values.toList() }
        snapshot.forEach { operation ->
            val cancelImmediately = runCatching { operation.onCancel() }
                .onFailure { Log.e(TAG, "Could not request cancellation of a vault operation.", it) }
                .getOrDefault(true)
            if (cancelImmediately) operation.job.cancel(
                CancellationException("The vault was locked.")
            )
        }
    }

    private fun startForegroundService(context: Context, title: String) {
        val intent = Intent(context, VaultOperationForegroundService::class.java)
            .setAction(VaultOperationForegroundService.ACTION_START)
            .putExtra(VaultOperationForegroundService.EXTRA_TITLE, title)
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (failure: RuntimeException) {
            Log.e(TAG, "Could not start the foreground service for a vault operation.", failure)
            throw IllegalStateException(
                "Android could not start the protected background operation. Keep Private Space open and try again.",
                failure
            )
        }
    }

    private fun finish(context: Context, operationId: String) {
        val shouldStop = synchronized(lock) {
            operations.remove(operationId)
            operations.isEmpty()
        }
        if (shouldStop) {
            context.stopService(Intent(context, VaultOperationForegroundService::class.java))
        }
    }

    private const val TAG = "VaultOperationRuntime"
}
