package com.privatespace.android.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Visible execution indicator for ongoing encrypted imports/exports.
 *
 * Work is owned by VaultOperationRuntime in the application process. This
 * service raises process importance while the operation is active; Android
 * may still terminate the process, so repository transactions and startup
 * recovery remain the source of truth after process death.
 */
class VaultOperationForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE)
            ?.takeIf { it.isNotBlank() }
            ?: "Private Space is working"

        val notification = buildNotification(title)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Private file operations",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when Private Space is importing or exporting encrypted files."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(title: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText("Keep Private Space available until this operation finishes.")
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()

    companion object {
        const val ACTION_START = "com.privatespace.android.action.START_FILE_OPERATION"
        const val EXTRA_TITLE = "operation_title"
        private const val CHANNEL_ID = "private_space_file_operations"
        private const val NOTIFICATION_ID = 4107
    }
}
