package com.aird.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

class ProjectionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val notification = notification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            markForeground()
        } catch (_: Exception) {
            failForeground()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (!foreground) failForeground()
        super.onDestroy()
    }

    private fun notification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Screen share", NotificationManager.IMPORTANCE_LOW),
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("Sharing your screen")
            .setContentText("Aird Chat")
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "aird_screen_share"
        private const val NOTIFICATION_ID = 77

        private val lock = Any()
        @Volatile private var foreground = false
        private var ready: CompletableDeferred<Boolean>? = null

        fun start(context: Context) {
            synchronized(lock) {
                foreground = false
                ready = CompletableDeferred()
            }
            val intent = Intent(context, ProjectionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun markForeground() {
            foreground = true
            synchronized(lock) {
                ready?.let { gate -> if (!gate.isCompleted) gate.complete(true) }
            }
        }

        fun failForeground() {
            foreground = false
            synchronized(lock) {
                ready?.let { gate -> if (!gate.isCompleted) gate.complete(false) }
            }
        }

        suspend fun awaitForeground(): Boolean {
            val gate = synchronized(lock) { ready }
            if (gate == null) return foreground
            val signaled = withTimeoutOrNull(4000) { gate.await() } == true
            return signaled && foreground
        }

        fun stop(context: Context) {
            foreground = false
            synchronized(lock) {
                ready?.let { gate -> if (!gate.isCompleted) gate.complete(false) }
                ready = null
            }
            context.stopService(Intent(context, ProjectionService::class.java))
        }
    }
}
