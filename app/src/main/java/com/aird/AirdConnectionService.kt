package com.aird

import android.app.AlarmManager
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
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.aird.AppForeground
import com.aird.call.CallController
import com.aird.chat.data.ChatRepository
import com.aird.session.AirdSocket

class AirdConnectionService : Service() {
    override fun onCreate() {
        super.onCreate()
        AppForeground.install()
        createChannels()
        startAs(listening(), remoteOnly = true)
        if (!hooked) {
            hooked = true
            AirdSocket.addListener { raw ->
                CallController.onEvent(this, raw)
                ChatRepository.onEvent(this, raw)
            }
        }
        AirdSocket.start(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.getStringExtra(EXTRA_MODE)) {
            "video", "audio" -> {
                val video = intent.getStringExtra(EXTRA_MODE) == "video"
                val peer = intent.getStringExtra(EXTRA_PEER).orEmpty().ifEmpty { "Aird Chat" }
                startAs(ongoingCall(peer, video), remoteOnly = false, video = video)
            }
            "idle" -> startAs(listening(), remoteOnly = true)
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val restart = PendingIntent.getService(
            this,
            1,
            Intent(this, AirdConnectionService::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val alarm = getSystemService(AlarmManager::class.java)
        alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 1500, restart)
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        AirdSocket.stop()
        super.onDestroy()
    }

    private fun startAs(notification: Notification, remoteOnly: Boolean, video: Boolean = false) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
            if (!remoteOnly) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                if (video) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                }
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        // Clean up legacy channels if present
        try {
            manager.deleteNotificationChannel("aird_chat_messages")
            manager.deleteNotificationChannel("aird_chat_calls")
        } catch (_: Exception) {}

        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Chat Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Chat message alerts"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 250, 250)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CALL_CHANNEL_ID, "Incoming Calls", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Voice and video call alerts"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 500, 500)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(SERVICE_CHANNEL_ID, "Aird Chat Connection", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps chat and call connection active"
                setSound(null, null)
            },
        )
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun listening(): Notification {
        return NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Aird Chat")
            .setContentText("Connected")
            .setContentIntent(openApp())
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun ongoingCall(peer: String, video: Boolean): Notification {
        val hangup = PendingIntent.getBroadcast(
            this,
            7,
            Intent(this, IncomingCallReceiver::class.java).apply { action = IncomingCallReceiver.ACTION_HANGUP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val person = Person.Builder().setName(peer).setImportant(true).build()
        val title = if (video) "Video call" else "Voice call"
        val builder = NotificationCompat.Builder(this, CALL_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle(title)
            .setContentText(peer)
            .setContentIntent(openApp())
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setUsesChronometer(true)
            .setWhen(System.currentTimeMillis())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setStyle(NotificationCompat.CallStyle.forOngoingCall(person, hangup))
        } else {
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "End", hangup)
        }
        return builder.build()
    }

    companion object {
        const val CHANNEL_ID = "aird_chat_messages_v2"
        const val CALL_CHANNEL_ID = "aird_chat_calls_v2"
        const val SERVICE_CHANNEL_ID = "aird_chat_connection"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_PEER = "peer"
        private const val NOTIFICATION_ID = 42
        private var hooked = false

        fun start(context: Context) {
            val intent = Intent(context, AirdConnectionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun showInCall(context: Context, peer: String, video: Boolean) {
            val intent = Intent(context, AirdConnectionService::class.java).apply {
                putExtra(EXTRA_MODE, if (video) "video" else "audio")
                putExtra(EXTRA_PEER, peer)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun showIdle(context: Context) {
            val intent = Intent(context, AirdConnectionService::class.java).apply {
                putExtra(EXTRA_MODE, "idle")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }
    }
}
