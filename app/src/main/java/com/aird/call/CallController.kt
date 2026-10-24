package com.aird.call

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.aird.AirdConnectionService
import com.aird.AppForeground
import com.aird.IncomingCallReceiver
import com.aird.MainActivity
import com.aird.session.AirdHttp
import com.aird.session.AirdSocket
import com.aird.session.Session
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import android.telecom.DisconnectCause
import io.livekit.android.room.participant.VideoTrackPublishOptions
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import livekit.org.webrtc.Camera2Enumerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

enum class CallPhase { Idle, Outgoing, Incoming, Joining, Live }

data class CallSnapshot(
    val phase: CallPhase = CallPhase.Idle,
    val conversationId: String = "",
    val callId: String = "",
    val peer: String = "",
    val mode: String = "audio",
    val status: String = "",
    val muted: Boolean = false,
    val speaker: Boolean = false,
    val cameraOn: Boolean = false,
    val minimized: Boolean = false,
    val sharing: Boolean = false,
    val cameraId: String = "",
    val playbackId: String = "",
    val microphoneId: String = "",
)

data class Drag(val x: Float = 0f, val y: Float = 0f)

object CallController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _snapshot = MutableStateFlow(CallSnapshot())
    val snapshot: StateFlow<CallSnapshot> = _snapshot
    private val _remoteVideo = MutableStateFlow<VideoTrack?>(null)
    val remoteVideo: StateFlow<VideoTrack?> = _remoteVideo
    private val _localVideo = MutableStateFlow<VideoTrack?>(null)
    val localVideo: StateFlow<VideoTrack?> = _localVideo
    private val _screenVideo = MutableStateFlow<VideoTrack?>(null)
    val screenVideo: StateFlow<VideoTrack?> = _screenVideo
    private val _swapped = MutableStateFlow(false)
    val swapped: StateFlow<Boolean> = _swapped
    private val _tileCollapsed = MutableStateFlow(false)
    val tileCollapsed: StateFlow<Boolean> = _tileCollapsed
    private val _tile = MutableStateFlow(Drag())
    val tile: StateFlow<Drag> = _tile
    private val _bubble = MutableStateFlow(Drag())
    val bubble: StateFlow<Drag> = _bubble
    private val _pipClose = MutableStateFlow(0)
    val pipClose: StateFlow<Int> = _pipClose

    private val silenced = mutableSetOf<String>()
    private var room: Room? = null
    private var events: Job? = null
    private var ringer: Ringtone? = null
    private var generation = 0
    private var shareTrack: LocalVideoTrack? = null

    fun room(): Room? = room

    fun moveTile(x: Float, y: Float) {
        _tile.value = Drag(x, y)
    }

    fun collapseTile() {
        _tileCollapsed.value = true
    }

    fun restoreTile() {
        _tileCollapsed.value = false
    }

    fun swapFeeds() {
        _swapped.value = !_swapped.value
    }

    fun moveBubble(x: Float, y: Float) {
        _bubble.value = Drag(x, y)
    }

    fun onEvent(context: Context, raw: String) {
        val data = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return
        }
        val type = data.optString("type")
        val conv = data.optString("conversation_id")
        val callId = data.optString("call_id")
        when (type) {
            "call_invite" -> {
                if (data.optString("from").equals(Session.username.value, true)) return
                if (callId.isNotEmpty() && callId in silenced) return
                val current = _snapshot.value
                if (current.phase != CallPhase.Idle) {
                    if (current.conversationId != conv) shade(context, "Missed call", data.optString("from", "Aird"), conv)
                    return
                }
                val mode = if (data.optString("mode") == "video") "video" else "audio"
                val peerName = data.optString("from", "Incoming call")
                _snapshot.value = CallSnapshot(
                    CallPhase.Incoming,
                    conv,
                    callId,
                    peerName,
                    mode,
                    "Incoming",
                )
                ring(context)
                showNotification(context)
                AirdTelecom.reportIncoming(context, peerName, mode == "video")
                try {
                    val callIntent = Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        putExtra("action", "incoming_call")
                        putExtra("conversation_id", conv)
                        putExtra("call_id", callId)
                        putExtra("call_mode", mode)
                        putExtra("call_from", peerName)
                    }
                    context.startActivity(callIntent)
                } catch (_: Exception) {}
            }
            "call_accept" -> {
                val current = _snapshot.value
                if (same(current, conv) && current.phase == CallPhase.Outgoing) markConnected(context)
            }
            "call_decline" -> {
                val current = _snapshot.value
                if (!same(current, conv)) return
                val outgoing = current.phase == CallPhase.Outgoing
                finish(
                    context,
                    if (outgoing) "Call declined" else "Call ended",
                    signal = false,
                    shade = if (outgoing) "Call declined" else null,
                    telecomCause = DisconnectCause.REJECTED,
                )
            }
            "call_end", "call_ended" -> {
                val current = _snapshot.value
                if (!same(current, conv)) return
                when (current.phase) {
                    CallPhase.Incoming -> finish(context, "Call ended", signal = false, shade = "Missed call", telecomCause = DisconnectCause.MISSED)
                    CallPhase.Idle -> Unit
                    else -> finish(context, "Call ended", signal = false, shade = null, telecomCause = DisconnectCause.REMOTE)
                }
            }
        }
    }

    fun restoreIncoming(conversationId: String, callId: String, mode: String, from: String) {
        val current = _snapshot.value
        if (current.phase != CallPhase.Idle && current.conversationId == conversationId) return
        if (conversationId.isEmpty()) return
        _snapshot.value = CallSnapshot(
            phase = CallPhase.Incoming,
            conversationId = conversationId,
            callId = callId,
            peer = from.ifEmpty { "Incoming call" },
            mode = if (mode == "video") "video" else "audio",
            status = "Incoming",
        )
    }

    fun start(context: Context, conversationId: String, peer: String, mode: String) {
        if (_snapshot.value.phase != CallPhase.Idle) return
        _snapshot.value = CallSnapshot(
            phase = CallPhase.Outgoing,
            conversationId = conversationId,
            peer = peer,
            mode = mode,
            status = "Calling…",
            speaker = mode == "video",
            cameraOn = mode == "video",
        )
        AirdTelecom.reportOutgoing(context, peer, mode == "video")
        scope.launch {
            try {
                val response = AirdHttp.send(
                    context,
                    "/api/chat/conversations/$conversationId/call",
                    "POST",
                    JSONObject().put("mode", mode).put("invite", true),
                )
                if (!response.ok) {
                    finish(context, response.json().optString("error", "Call failed"), signal = false, shade = null)
                    return@launch
                }
                val callId = response.json().optString("call_id")
                _snapshot.value = _snapshot.value.copy(callId = callId)
                connect(context, response.json().optString("url"), response.json().optString("token"), mode == "video")
            } catch (err: Exception) {
                finish(context, err.message ?: "Call failed", signal = false, shade = null)
            }
        }
    }

    fun accept(context: Context) {
        val current = _snapshot.value
        if (current.phase != CallPhase.Incoming) return
        if (current.callId.isNotEmpty()) silenced.add(current.callId)
        stopRing()
        dismiss(context, current.conversationId)
        _snapshot.value = current.copy(phase = CallPhase.Joining, status = "Joining…", minimized = false)
        scope.launch {
            try {
                val response = AirdHttp.send(
                    context,
                    "/api/chat/conversations/${current.conversationId}/call",
                    "POST",
                    JSONObject().put("mode", current.mode).put("invite", false).put("call_id", current.callId),
                )
                if (!response.ok) {
                    finish(context, "Could not join", signal = false, shade = null)
                    return@launch
                }
                signal("call_accept", current.conversationId, current.callId)
                connect(context, response.json().optString("url"), response.json().optString("token"), current.mode == "video")
            } catch (err: Exception) {
                finish(context, err.message ?: "Could not join", signal = false, shade = null)
            }
        }
    }

    fun decline(context: Context) {
        val current = _snapshot.value
        if (current.phase != CallPhase.Incoming && current.phase != CallPhase.Joining) return
        if (current.callId.isNotEmpty()) silenced.add(current.callId)
        signal("call_decline", current.conversationId, current.callId)
        finish(context, "", signal = false, shade = null, telecomCause = DisconnectCause.REJECTED)
    }

    fun hangUp(context: Context) {
        val current = _snapshot.value
        if (current.phase == CallPhase.Idle) return
        if (current.phase == CallPhase.Incoming) {
            decline(context)
            return
        }
        val cause = if (current.phase == CallPhase.Outgoing) DisconnectCause.CANCELED else DisconnectCause.LOCAL
        finish(context, "", signal = true, shade = null, telecomCause = cause)
    }

    fun toggleMute() {
        val current = _snapshot.value
        val enabled = current.muted
        _snapshot.value = current.copy(muted = !enabled)
        scope.launch {
            try {
                room?.localParticipant?.setMicrophoneEnabled(enabled)
            } catch (_: Exception) {
            }
        }
    }

    fun updateSpeakerState(isSpeaker: Boolean) {
        _snapshot.value = _snapshot.value.copy(speaker = isSpeaker)
    }

    fun toggleSpeaker(context: Context) {
        val next = !_snapshot.value.speaker
        val choice = if (next) "speaker" else "earpiece"
        CallDevices.selectPlayback(context, room, choice)
        _snapshot.value = _snapshot.value.copy(speaker = next, playbackId = choice)
        updateProximity(context)
    }

    fun toggleCamera() {
        val next = !_snapshot.value.cameraOn
        _snapshot.value = _snapshot.value.copy(cameraOn = next)
        if (!next) {
            _localVideo.value = null
        }
        scope.launch {
            try {
                room?.localParticipant?.setCameraEnabled(next)
                if (next) {
                    val pub = room?.localParticipant?.getTrackPublication(Track.Source.CAMERA)
                    val track = pub?.track as? VideoTrack
                    if (track != null) _localVideo.value = track
                }
            } catch (_: Exception) {
            }
        }
    }

    fun switchCamera(context: Context) {
        val active = room ?: return
        val current = _snapshot.value
        if (!current.cameraOn) return
        scope.launch {
            try {
                val participant = active.localParticipant
                val pub = participant.getTrackPublication(Track.Source.CAMERA)
                val track = pub?.track as? LocalVideoTrack

                val enumerator = Camera2Enumerator(context)
                val deviceNames = enumerator.deviceNames?.toList().orEmpty()
                if (deviceNames.isEmpty()) return@launch

                val currentId = current.cameraId
                val isCurrentlyFront = if (currentId.isNotEmpty()) enumerator.isFrontFacing(currentId) else true

                val nextId = deviceNames.firstOrNull { id ->
                    if (isCurrentlyFront) enumerator.isBackFacing(id) else enumerator.isFrontFacing(id)
                } ?: deviceNames.firstOrNull { it != currentId }

                if (track != null) {
                    if (nextId != null) {
                        track.switchCamera(nextId)
                        _snapshot.value = _snapshot.value.copy(cameraId = nextId)
                    } else {
                        track.switchCamera()
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    fun selectPlayback(context: Context, id: String) {
        CallDevices.selectPlayback(context, room, id)
        val speaker = id == "speaker" || CallDevices.playback(context, room).firstOrNull { it.id == id }?.label.equals("Speaker", true)
        _snapshot.value = _snapshot.value.copy(playbackId = id, speaker = speaker)
        updateProximity(context)
    }

    fun selectMicrophone(context: Context, id: String) {
        CallDevices.selectMicrophone(context, room, id)
        _snapshot.value = _snapshot.value.copy(microphoneId = id)
    }

    fun beginShare(context: Context, data: Intent) {
        val active = room ?: return
        ProjectionService.start(context)
        scope.launch {
            if (!ProjectionService.awaitForeground()) {
                _snapshot.value = _snapshot.value.copy(sharing = false, status = "Screen share failed")
                ProjectionService.stop(context)
                return@launch
            }
            try {
                val track = active.localParticipant.createScreencastTrack(
                    mediaProjectionPermissionResultData = data,
                    onStop = {
                        if (shareTrack != null) stopShare(context)
                    },
                )
                shareTrack = track
                track.startCapture()
                if (shareTrack != track) return@launch
                val published = active.localParticipant.publishVideoTrack(
                    track,
                    VideoTrackPublishOptions(source = Track.Source.SCREEN_SHARE),
                )
                if (!published) {
                    shareTrack = null
                    try {
                        track.stop()
                    } catch (_: Exception) {
                    }
                    _snapshot.value = _snapshot.value.copy(sharing = false, status = "Screen share failed")
                    ProjectionService.stop(context)
                    return@launch
                }
                shareTrack = track
                _screenVideo.value = track
                _snapshot.value = _snapshot.value.copy(sharing = true)
            } catch (_: Exception) {
                shareTrack = null
                _snapshot.value = _snapshot.value.copy(sharing = false, status = "Screen share failed")
                ProjectionService.stop(context)
            }
        }
    }

    fun stopShare(context: Context) {
        val track = shareTrack
        shareTrack = null
        _screenVideo.value = null
        _snapshot.value = _snapshot.value.copy(sharing = false)
        scope.launch {
            if (track != null) {
                try {
                    room?.localParticipant?.unpublishTrack(track)
                } catch (_: Exception) {
                }
            }
            ProjectionService.stop(context)
        }
    }

    fun minimize() {
        _snapshot.value = _snapshot.value.copy(minimized = true)
    }

    fun expand() {
        _snapshot.value = _snapshot.value.copy(minimized = false)
    }

    private suspend fun connect(context: Context, url: String, token: String, video: Boolean) {
        if (url.isEmpty() || token.isEmpty()) {
            finish(context, "Call is not configured", signal = false, shade = null)
            return
        }
        val audio = context.getSystemService(AudioManager::class.java)
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        audio.isSpeakerphoneOn = video
        val next = LiveKit.create(context.applicationContext)
        room = next
        events?.cancel()
        val gen = generation
        events = scope.launch {
            next.events.collect { event -> if (gen == generation) onRoomEvent(context, event) }
        }
        next.connect(url, token)
        next.localParticipant.setMicrophoneEnabled(true)
        if (video) next.localParticipant.setCameraEnabled(true)
        _snapshot.value = _snapshot.value.copy(speaker = video, cameraOn = video)
        AirdConnectionService.showInCall(context, _snapshot.value.peer.ifEmpty { "Aird Chat" }, video)
        if (next.remoteParticipants.isNotEmpty()) markConnected(context)
    }

    private fun onRoomEvent(context: Context, event: RoomEvent) {
        when (event) {
            is RoomEvent.TrackSubscribed -> {
                val track = event.track
                if (track is VideoTrack && event.publication.source != Track.Source.SCREEN_SHARE) _remoteVideo.value = track
                if (track is VideoTrack && event.publication.source == Track.Source.SCREEN_SHARE) _remoteVideo.value = track
                markConnected(context)
            }
            is RoomEvent.ParticipantConnected -> markConnected(context)
            is RoomEvent.ParticipantDisconnected -> {
                val active = room
                if (active != null && active.remoteParticipants.isEmpty()) {
                    val phase = _snapshot.value.phase
                    if (phase == CallPhase.Live || phase == CallPhase.Joining || phase == CallPhase.Outgoing) {
                        finish(context, "Call ended", signal = false, shade = null, telecomCause = DisconnectCause.REMOTE)
                    }
                }
            }
            is RoomEvent.TrackPublished -> {
                if (event.participant != room?.localParticipant) return
                val track = event.publication.track as? VideoTrack ?: return
                if (event.publication.source == Track.Source.SCREEN_SHARE) _screenVideo.value = track
                else if (event.publication.source == Track.Source.CAMERA) _localVideo.value = track
            }
            is RoomEvent.TrackMuted -> {
                if (event.publication.source == Track.Source.CAMERA) {
                    if (event.participant == room?.localParticipant) _localVideo.value = null
                    else _remoteVideo.value = null
                }
            }
            is RoomEvent.TrackUnmuted -> {
                val track = event.publication.track
                if (track is VideoTrack && event.publication.source == Track.Source.CAMERA) {
                    if (event.participant == room?.localParticipant) _localVideo.value = track
                    else _remoteVideo.value = track
                }
            }
            is RoomEvent.TrackUnpublished -> {
                if (event.publication.source == Track.Source.CAMERA) {
                    if (event.participant == room?.localParticipant) _localVideo.value = null
                    else _remoteVideo.value = null
                }
                if (event.publication.source == Track.Source.SCREEN_SHARE && event.participant == room?.localParticipant) {
                    _screenVideo.value = null
                    _snapshot.value = _snapshot.value.copy(sharing = false)
                }
            }
            is RoomEvent.Disconnected -> {
                if (_snapshot.value.phase != CallPhase.Idle) {
                    finish(context, "Call ended", signal = false, shade = null, telecomCause = DisconnectCause.REMOTE)
                }
            }
            else -> Unit
        }
    }

    private fun markConnected(context: Context) {
        val current = _snapshot.value
        if (current.phase != CallPhase.Outgoing && current.phase != CallPhase.Joining && current.phase != CallPhase.Live) return
        stopRing()
        dismiss(context, current.conversationId)
        if (current.phase != CallPhase.Live || current.status != "Connected") {
            _snapshot.value = current.copy(phase = CallPhase.Live, status = "Connected")
        }
        updateProximity(context)
        AirdTelecom.activate()
    }

    private fun finish(context: Context, message: String, signal: Boolean, shade: String?, telecomCause: Int = DisconnectCause.LOCAL) {
        val current = _snapshot.value
        if (current.phase == CallPhase.Idle && current.status.isEmpty() && message.isEmpty()) return
        _pipClose.value = _pipClose.value + 1
        AirdTelecom.end(telecomCause)
        releaseProximity()
        generation += 1
        val gen = generation
        if (signal && current.conversationId.isNotEmpty() && current.phase != CallPhase.Idle) {
            signal("call_end", current.conversationId, current.callId)
        }
        if (current.callId.isNotEmpty()) silenced.add(current.callId)
        events?.cancel()
        val active = room
        room = null
        _remoteVideo.value = null
        _localVideo.value = null
        _localVideo.value = null
        _screenVideo.value = null
        shareTrack = null
        scope.launch {
            try {
                active?.disconnect()
            } catch (_: Exception) {
            }
        }
        stopRing()
        dismiss(context, current.conversationId)
        ProjectionService.stop(context)
        AirdConnectionService.showIdle(context)
        val audio = context.getSystemService(AudioManager::class.java)
        audio.mode = AudioManager.MODE_NORMAL
        @Suppress("DEPRECATION")
        audio.isSpeakerphoneOn = false
        if (shade != null) shade(context, shade, current.peer, current.conversationId)
        if (message.isEmpty()) {
            _snapshot.value = CallSnapshot()
            return
        }
        _snapshot.value = CallSnapshot(status = message, peer = current.peer, mode = current.mode)
        scope.launch {
            delay(3000)
            if (generation == gen && _snapshot.value.phase == CallPhase.Idle && _snapshot.value.status == message) {
                _snapshot.value = CallSnapshot()
            }
        }
    }

    private fun same(current: CallSnapshot, conversationId: String): Boolean {
        return current.phase != CallPhase.Idle && current.conversationId == conversationId
    }

    private fun signal(type: String, conversationId: String, callId: String) {
        if (conversationId.isEmpty()) return
        AirdSocket.send(JSONObject().put("type", type).put("conversation_id", conversationId).put("call_id", callId).toString())
    }

    private fun ring(context: Context) {
        if (ringer?.isPlaying == true) return
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE) ?: return
        ringer = RingtoneManager.getRingtone(context.applicationContext, uri)?.apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
            audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            play()
        }
    }

    private fun stopRing() {
        try {
            ringer?.stop()
        } catch (_: Exception) {
        }
        ringer = null
    }

    private fun showNotification(context: Context) {
        val current = _snapshot.value
        val accept = PendingIntent.getBroadcast(
            context,
            (current.conversationId + "accept").hashCode(),
            Intent(context, IncomingCallReceiver::class.java).apply {
                action = IncomingCallReceiver.ACTION_ACCEPT
                putExtra(IncomingCallReceiver.KEY_CONVERSATION, current.conversationId)
                putExtra(IncomingCallReceiver.KEY_CALL, current.callId)
                putExtra(IncomingCallReceiver.KEY_MODE, current.mode)
                putExtra(IncomingCallReceiver.KEY_FROM, current.peer)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val decline = PendingIntent.getBroadcast(
            context,
            (current.conversationId + "decline").hashCode(),
            Intent(context, IncomingCallReceiver::class.java).apply {
                action = IncomingCallReceiver.ACTION_DECLINE
                putExtra(IncomingCallReceiver.KEY_CONVERSATION, current.conversationId)
                putExtra(IncomingCallReceiver.KEY_CALL, current.callId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val open = PendingIntent.getActivity(
            context,
            ("call-open:" + current.conversationId).hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("action", "incoming_call")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = if (current.mode == "video") "Incoming video call" else "Incoming voice call"
        val builder = NotificationCompat.Builder(context, AirdConnectionService.CALL_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle(title)
            .setContentText(current.peer)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVibrate(longArrayOf(0, 500, 500, 500))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setFullScreenIntent(open, true)
            .setContentIntent(open)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setStyle(NotificationCompat.CallStyle.forIncomingCall(Person.Builder().setName(current.peer).build(), decline, accept))
        } else {
            builder.addAction(android.R.drawable.ic_menu_call, "Accept", accept)
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Decline", decline)
        }
        context.getSystemService(NotificationManager::class.java).notify(callNotificationId(current.conversationId), builder.build())
    }

    private fun shade(context: Context, title: String, peer: String, conversationId: String) {
        if (conversationId.isEmpty()) return
        val open = PendingIntent.getActivity(
            context,
            ("shade:$conversationId").hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("action", "open_chat")
                putExtra("conversation_id", conversationId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, AirdConnectionService.CALL_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle(title)
            .setContentText(peer.ifEmpty { "Aird" })
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(("missed:$conversationId").hashCode(), notification)
    }

    private fun dismiss(context: Context, conversationId: String) {
        if (conversationId.isEmpty()) return
        context.getSystemService(NotificationManager::class.java).cancel(callNotificationId(conversationId))
    }

    private fun callNotificationId(conversationId: String): Int = ("call:$conversationId").hashCode()

    private fun forgetDefaultCamera(participant: io.livekit.android.room.participant.LocalParticipant): Boolean {
        try {
            val field = participant.javaClass.getDeclaredField("defaultVideoTrack")
            field.isAccessible = true
            val cached = field.get(participant) as? LocalVideoTrack
            if (cached != null) {
                try {
                    cached.stopCapture()
                } catch (_: Exception) {
                }
                try {
                    cached.stop()
                } catch (_: Exception) {
                }
                try {
                    cached.dispose()
                } catch (_: Exception) {
                }
            }
            field.set(participant, null)
            return true
        } catch (_: Exception) {
            return false
        }
    }

    private var proximityWakeLock: android.os.PowerManager.WakeLock? = null

    private fun updateProximity(context: Context) {
        val current = _snapshot.value
        val needsProximity = current.phase == CallPhase.Live && current.mode == "audio" && !current.speaker
        if (needsProximity) {
            if (proximityWakeLock?.isHeld != true) {
                val power = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                if (power?.isWakeLockLevelSupported(android.os.PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) == true) {
                    proximityWakeLock = power.newWakeLock(android.os.PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "aird:ProximityWakeLock")
                    proximityWakeLock?.acquire(2 * 60 * 60 * 1000L)
                }
            }
        } else {
            releaseProximity()
        }
    }

    private fun releaseProximity() {
        try {
            if (proximityWakeLock?.isHeld == true) {
                proximityWakeLock?.release()
            }
        } catch (_: Exception) {
        }
        proximityWakeLock = null
    }
}
