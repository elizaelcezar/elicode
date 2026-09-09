package com.elicode.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.elicode.app.MainActivity
import com.elicode.app.R
import java.util.concurrent.ConcurrentHashMap

/**
 * Foreground service that keeps long work alive while the app is
 * minimized or the screen is locked: runtime install, Gradle builds,
 * OpenCode runs and dev servers.
 *
 * Each long task calls [EliCodeService.taskStarted]/[taskFinished];
 * the service stays in the foreground while at least one task runs.
 */
class EliCodeService : Service() {

    private val tasks = ConcurrentHashMap<String, String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TASK_STARTED -> {
                val id = intent.getStringExtra(EXTRA_ID).orEmpty()
                val label = intent.getStringExtra(EXTRA_LABEL).orEmpty()
                if (id.isNotBlank()) tasks[id] = label.ifBlank { id }
                promote()
            }
            ACTION_TASK_FINISHED -> {
                val id = intent.getStringExtra(EXTRA_ID).orEmpty()
                tasks.remove(id)
                if (tasks.isEmpty()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else promote()
            }
            ACTION_STOP_ALL -> {
                tasks.clear()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> promote()
        }
        return START_NOT_STICKY
    }

    private fun promote() {
        val notification = buildNotification(
            if (tasks.isEmpty()) "EliCode is ready"
            else tasks.values.joinToString(limit = 3)
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIF_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                @Suppress("DEPRECATION")
                startForeground(NOTIF_ID, notification)
            }
        } catch (_: Throwable) {
            // Foreground start can fail without the runtime notification
            // permission on some OEM ROMs; tasks continue in background.
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("EliCode")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL, "EliCode tasks",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply { description = "Runtime install, builds, agent runs, servers" }
                )
            }
        }
    }

    companion object {
        const val CHANNEL = "elicode_tasks"
        const val NOTIF_ID = 41
        const val ACTION_TASK_STARTED = "com.elicode.app.TASK_STARTED"
        const val ACTION_TASK_FINISHED = "com.elicode.app.TASK_FINISHED"
        const val ACTION_STOP_ALL = "com.elicode.app.STOP_ALL"
        const val EXTRA_ID = "id"
        const val EXTRA_LABEL = "label"

        fun taskStarted(context: Context, id: String, label: String) {
            start(context, ACTION_TASK_STARTED, id, label)
        }

        fun taskFinished(context: Context, id: String) {
            start(context, ACTION_TASK_FINISHED, id, "")
        }

        private fun start(context: Context, action: String, id: String, label: String) {
            val intent = Intent(context, EliCodeService::class.java).apply {
                this.action = action
                putExtra(EXTRA_ID, id)
                putExtra(EXTRA_LABEL, label)
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }
    }
}
