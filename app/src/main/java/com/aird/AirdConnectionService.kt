package com.aird

import android.app.ActivityOptions
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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.aird.AppForeground
import com.aird.call.CallController
import com.aird.call.CallPhase
import com.aird.call.CallSnapshot
import com.aird.chat.data.ChatRepository
import com.aird.session.AirdSocket

class AirdConnectionService : Service() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        AppForeground.install()
        createChannels()
        startAs(listening(), remoteOnly = true)
        if (!hooked) {
            hooked = true
            val app = applicationContext
            AirdSocket.addListener { raw ->
                CallController.onEvent(app, raw)
                ChatRepository.onEvent(app, raw)
            }
            // Every (re)connect: catch up on anything sent while the link was down.
            AirdSocket.addOpenListener { ChatRepository.resyncAsync(app) }
        }
        AirdSocket.start(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.getStringExtra(EXTRA_MODE)) {
            "video", "audio" -> {
                val video = intent.getStringExtra(EXTRA_MODE) == "video"
                val camera = intent.getBooleanExtra(EXTRA_CAMERA, video)
                val peer = intent.getStringExtra(EXTRA_PEER).orEmpty().ifEmpty { "Aird Chat" }
                startAs(ongoingCall(peer, video), remoteOnly = false, video = camera)
            }
            "ringing" -> {
                val conv = intent.getStringExtra(EXTRA_CONVERSATION).orEmpty()
                val callId = intent.getStringExtra(EXTRA_CALL_ID).orEmpty()
                val peer = intent.getStringExtra(EXTRA_PEER).orEmpty()
                val mode = intent.getStringExtra(EXTRA_MODE_KIND).orEmpty().ifEmpty { "audio" }
                if (conv.isNotEmpty()) {
                    CallController.restoreIncoming(conv, callId, mode, peer)
                }
                presentIncoming(intent.getBooleanExtra(EXTRA_ALERT, true))
            }
            "joining" -> presentJoining()
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
        if (instance === this) instance = null
        AirdSocket.stop()
        super.onDestroy()
    }

    private fun startAs(
        notification: Notification,
        remoteOnly: Boolean,
        video: Boolean = false,
        includeCall: Boolean = !remoteOnly,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
            if (includeCall) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            if (!remoteOnly) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                if (video) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun tryStart(
        notification: Notification,
        remoteOnly: Boolean,
        video: Boolean = false,
        includeCall: Boolean = !remoteOnly,
    ): Boolean {
        return try {
            startAs(notification, remoteOnly, video, includeCall)
            true
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalStateException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun presentIncoming(alert: Boolean) {
        val call = CallController.snapshot.value
        if (call.phase != CallPhase.Incoming) return
        getSystemService(NotificationManager::class.java).cancel(INCOMING_FALLBACK_ID)
        val styled = incomingNotification(call, callStyle = alert && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S, alert = alert)
        if (tryStart(styled, remoteOnly = true, includeCall = true)) return
        val plain = incomingNotification(call, callStyle = false, alert = alert)
        if (tryStart(plain, remoteOnly = true, includeCall = true)) return
        if (tryStart(plain, remoteOnly = true, includeCall = false)) return
        try {
            getSystemService(NotificationManager::class.java).notify(INCOMING_FALLBACK_ID, plain)
        } catch (_: RuntimeException) {
        }
    }

    private fun presentJoining() {
        val call = CallController.snapshot.value
        if (call.phase != CallPhase.Joining && call.phase != CallPhase.Outgoing) return
        val peer = call.peer.ifEmpty { "Aird Chat" }
        val styled = ongoingCall(peer, call.mode == "video", "Joining…")
        if (tryStart(styled, remoteOnly = true, includeCall = true)) return
        tryStart(plainOngoing(peer, "Joining…"), remoteOnly = true, includeCall = false)
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        // Clean up legacy channels if present
        try {
            manager.deleteNotificationChannel("aird_chat_messages")
            manager.deleteNotificationChannel("aird_chat_calls")
            manager.deleteNotificationChannel("aird_chat_calls_v2")
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
                description = "Incoming voice and video calls"
                setSound(null, null)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400)
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

    private fun openCall(): PendingIntent = PendingIntent.getActivity(
        this,
        3,
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("action", "expand_call")
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

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

    private fun ongoingCall(peer: String, video: Boolean, status: String = ""): Notification {
        val hangup = hangupIntent()
        val kind = if (video) "Video call" else "Voice call"
        val person = Person.Builder().setName("$peer · $kind").setImportant(true).build()
        val builder = NotificationCompat.Builder(this, CALL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle(kind)
            .setContentText(if (video) "$peer · Video" else "$peer · Voice only")
            .setContentIntent(openCall())
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setUsesChronometer(status.isEmpty())
            .setWhen(System.currentTimeMillis())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setStyle(NotificationCompat.CallStyle.forOngoingCall(person, hangup))
        } else {
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "End", hangup)
        }
        return builder.build()
    }

    private fun plainOngoing(peer: String, status: String): Notification {
        return NotificationCompat.Builder(this, CALL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle(status)
            .setContentText(peer)
            .setContentIntent(openCall())
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "End", hangupIntent())
            .build()
    }

    private fun incomingNotification(call: CallSnapshot, callStyle: Boolean, alert: Boolean): Notification {
        val accept = callAction(
            IncomingCallReceiver.ACTION_ACCEPT,
            (call.conversationId + "accept").hashCode(),
            call,
        )
        val decline = callAction(
            IncomingCallReceiver.ACTION_DECLINE,
            (call.conversationId + "decline").hashCode(),
            call,
        )
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("action", "incoming_call")
            putExtra("conversation_id", call.conversationId)
            putExtra("call_id", call.callId)
            putExtra("call_mode", call.mode)
            putExtra("call_from", call.peer)
        }
        val openFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, ("call-open:" + call.conversationId).hashCode(), openIntent, openFlags)
        val video = call.mode == "video"
        val caller = call.peer.ifEmpty { "Incoming call" }
        val title = if (video) "Incoming video call" else "Incoming voice call"
        val detail = if (video) "Camera stays off until you turn it on" else "Voice call"
        val person = Person.Builder().setName(caller).setImportant(true).build()
        val builder = NotificationCompat.Builder(this, if (alert) CALL_CHANNEL_ID else SERVICE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle(title)
            .setContentText(caller)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(if (alert) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(open)
            .addPerson(person)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (alert) builder.setFullScreenIntent(open, true) else builder.setSilent(true)
        if (alert && callStyle && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setStyle(
                NotificationCompat.CallStyle.forIncomingCall(person, decline, accept)
                    .setVerificationText(detail),
            )
        } else {
            if (alert) builder.setVibrate(longArrayOf(0, 400, 200, 400))
            builder.addAction(android.R.drawable.ic_menu_call, "Accept", accept)
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Decline", decline)
        }
        return builder.build()
    }

    private fun callAction(action: String, requestCode: Int, call: CallSnapshot): PendingIntent {
        return PendingIntent.getBroadcast(
            this,
            requestCode,
            Intent(this, IncomingCallReceiver::class.java).apply {
                this.action = action
                putExtra(IncomingCallReceiver.KEY_CONVERSATION, call.conversationId)
                putExtra(IncomingCallReceiver.KEY_CALL, call.callId)
                putExtra(IncomingCallReceiver.KEY_MODE, call.mode)
                putExtra(IncomingCallReceiver.KEY_FROM, call.peer)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun hangupIntent(): PendingIntent {
        return PendingIntent.getBroadcast(
            this,
            7,
            Intent(this, IncomingCallReceiver::class.java).apply { action = IncomingCallReceiver.ACTION_HANGUP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val CHANNEL_ID = "aird_chat_messages_v2"
        const val CALL_CHANNEL_ID = "aird_chat_calls_v3"
        const val SERVICE_CHANNEL_ID = "aird_chat_connection"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_PEER = "peer"
        private const val EXTRA_CAMERA = "camera"
        private const val EXTRA_ALERT = "alert"
        private const val EXTRA_CONVERSATION = "conversation_id"
        private const val EXTRA_CALL_ID = "call_id"
        private const val EXTRA_MODE_KIND = "mode_kind"
        private const val NOTIFICATION_ID = 42
        private const val INCOMING_FALLBACK_ID = 43
        private var hooked = false

        @Volatile
        private var instance: AirdConnectionService? = null
        private val main = Handler(Looper.getMainLooper())

        fun stop(context: Context) {
            context.stopService(Intent(context, AirdConnectionService::class.java))
        }

        fun start(context: Context) {
            launch(context, null)
        }

        fun showRinging(
            context: Context,
            conversationId: String = "",
            callId: String = "",
            peer: String = "",
            mode: String = "audio",
            alert: Boolean = true,
        ) {
            onMain {
                if (conversationId.isNotEmpty()) {
                    CallController.restoreIncoming(conversationId, callId, mode, peer)
                }
                val service = instance
                if (service != null) {
                    service.presentIncoming(alert)
                } else {
                    launch(
                        context,
                        "ringing",
                        peer = peer,
                        camera = mode == "video",
                        alert = alert,
                        conversationId = conversationId,
                        callId = callId,
                        modeKind = mode,
                    )
                }
            }
        }

        fun showJoining(context: Context) {
            onMain {
                val service = instance
                if (service != null) service.presentJoining() else launch(context, "joining")
            }
        }

        fun dismissIncoming(context: Context) {
            context.applicationContext.getSystemService(NotificationManager::class.java).cancel(INCOMING_FALLBACK_ID)
        }

        fun showInCall(context: Context, peer: String, video: Boolean, camera: Boolean = video) {
            launch(context, if (video) "video" else "audio", peer, camera)
        }

        fun showIdle(context: Context) {
            launch(context, "idle")
        }

        private fun launch(
            context: Context,
            mode: String?,
            peer: String = "",
            camera: Boolean = false,
            alert: Boolean = true,
            conversationId: String = "",
            callId: String = "",
            modeKind: String = "",
        ) {
            val intent = Intent(context, AirdConnectionService::class.java).apply {
                if (mode != null) putExtra(EXTRA_MODE, mode)
                if (peer.isNotEmpty()) putExtra(EXTRA_PEER, peer)
                if (conversationId.isNotEmpty()) putExtra(EXTRA_CONVERSATION, conversationId)
                if (callId.isNotEmpty()) putExtra(EXTRA_CALL_ID, callId)
                if (modeKind.isNotEmpty()) putExtra(EXTRA_MODE_KIND, modeKind)
                putExtra(EXTRA_CAMERA, camera)
                putExtra(EXTRA_ALERT, alert)
            }
            val app = context.applicationContext
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(intent)
            else app.startService(intent)
        }

        private fun onMain(block: () -> Unit) {
            if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
        }
    }
}
