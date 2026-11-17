package com.privatespace.android.data

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Owns user-visible file operations independently of an Activity/Compose
 * lifecycle. The foreground service keeps the process at the appropriate
 * importance while work is active; it does not persist keys or promise that
 * Android can never terminate the process.
 *
 * Each operation must still be cancellable and must re-check vault/session
 * authorization before releasing plaintext.
 */
object VaultOperationRuntime {
    private val lock = Any()
    private var activeOperations = 0
    private val exceptionHandler = CoroutineExceptionHandler { _, failure ->
        Log.e(TAG, "An uncaught vault operation failed.", failure)
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + exceptionHandler)

    fun launch(context: Context, title: String, block: suspend CoroutineScope.() -> Unit): Job {
        begin(context, title)
        return try {
            scope.launch {
                try {
                    block()
                } finally {
                    end(context.applicationContext)
                }
            }
        } catch (failure: Throwable) {
            end(context.applicationContext)
            throw failure
        }
    }

    private fun begin(context: Context, title: String) {
        synchronized(lock) {
            activeOperations++
            val intent = Intent(context.applicationContext, VaultOperationForegroundService::class.java)
                .setAction(VaultOperationForegroundService.ACTION_START)
                .putExtra(VaultOperationForegroundService.EXTRA_TITLE, title)
            try {
                ContextCompat.startForegroundService(context.applicationContext, intent)
            } catch (failure: RuntimeException) {
                activeOperations--
                throw failure
            }
        }
    }

    private fun end(context: Context) {
        synchronized(lock) {
            activeOperations = (activeOperations - 1).coerceAtLeast(0)
            if (activeOperations == 0) {
                context.stopService(Intent(context, VaultOperationForegroundService::class.java))
            }
        }
    }

    private const val TAG = "VaultOperationRuntime"
}
