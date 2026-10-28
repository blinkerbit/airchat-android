package com.aird.call

import android.app.ActivityOptions
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
import com.aird.AirdConnectionService
import com.aird.AppForeground
import com.aird.MainActivity
import com.aird.R
import com.aird.session.AirdHttp
import com.aird.session.AirdSocket
import com.aird.session.Session
import io.livekit.android.LiveKit
import io.livekit.android.audio.AudioSwitchHandler
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
    val output: String = "",
    val routeHint: String = "",
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
    private val _backgroundOnly = MutableStateFlow(false)
    val backgroundOnly: StateFlow<Boolean> = _backgroundOnly

    private const val RING_WINDOW_MS = 47_000L
    private val silenced = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private var room: Room? = null
    private var events: Job? = null
    private var ringer: Ringtone? = null
    private var generation = 0
    private var routeHintGeneration = 0
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
                    if (current.conversationId != conv) shade(context, missedTitle(data.optString("mode")), data.optString("from", "Aird"), conv)
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
                    if (mode == "video") "Incoming video call" else "Incoming voice call",
                )
                ring(context)
                val inApp = AppForeground.resumed()
                launchIncomingCallActivity(context)
                try {
                    AirdTelecom.reportIncoming(context, peerName, mode == "video")
                } catch (_: Exception) {
                }
                try {
                    AirdConnectionService.showRinging(
                        context,
                        conversationId = conv,
                        callId = callId,
                        peer = peerName,
                        mode = mode,
                        alert = !inApp,
                    )
                } catch (_: Exception) {
                }
                watchInvite(context, conv, callId)
            }
            "call_mode" -> {
                if (data.optString("from").equals(Session.username.value, true)) return
                val current = _snapshot.value
                if (!same(current, conv)) return
                if (callId.isNotEmpty() && current.callId.isNotEmpty() && callId != current.callId) return
                applyMode(context, video = data.optString("mode") == "video", enableCamera = false, notifyPeer = false)
            }
            "call_accept" -> {
                val current = _snapshot.value
                if (same(current, conv) && current.phase == CallPhase.Outgoing) markConnected(context)
            }
            "call_decline" -> {
                val current = _snapshot.value
                if (callId.isNotEmpty() && !same(current, conv)) silenced.add(callId)
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
                // Remember it so a late or replayed invite for this call never rings.
                if (callId.isNotEmpty()) silenced.add(callId)
                if (!same(current, conv)) return
                when (current.phase) {
                    CallPhase.Incoming -> finish(context, "Call ended", signal = false, shade = missedTitle(current.mode), telecomCause = DisconnectCause.MISSED)
                    CallPhase.Idle -> Unit
                    else -> finish(context, "Call ended", signal = false, shade = null, telecomCause = DisconnectCause.REMOTE)
                }
            }
        }
    }

    private fun dropIfDead(context: Context, conv: String, callId: String): Boolean {
        val current = _snapshot.value
        if (current.phase != CallPhase.Incoming || current.callId != callId) return true
        if (callId.isNotEmpty()) silenced.add(callId)
        finish(context, "", signal = false, shade = missedTitle(current.mode), telecomCause = DisconnectCause.MISSED)
        return true
    }

    /**
     * Confirm the call is still live on the server, then stop ringing if the
     * caller gave up before this invite reached us. Also stop after the ring
     * window so a lost end event can never leave a call ringing.
     */
    private fun watchInvite(context: Context, conv: String, callId: String) {
        scope.launch {
            try {
                val response = AirdHttp.get(context, "/api/chat/call-ring")
                if (response.ok) {
                    val ring = response.json().optJSONObject("ring")
                    val ringId = ring?.optString("call_id").orEmpty()
                    val live = ring != null &&
                        ring.optString("conversation_id") == conv &&
                        (callId.isEmpty() || ringId.isEmpty() || ringId == callId)
                    if (!live) {
                        dropIfDead(context, conv, callId)
                        return@launch
                    }
                }
            } catch (err: Exception) {
                if (err is kotlinx.coroutines.CancellationException) throw err
            }
            delay(RING_WINDOW_MS)
            dropIfDead(context, conv, callId)
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
            status = if (mode == "video") "Incoming video call" else "Incoming voice call",
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
            output = if (mode == "video") "speaker" else "earpiece",
            playbackId = if (mode == "video") "speaker" else "earpiece",
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

    fun accept(context: Context, withCamera: Boolean = false) {
        val current = _snapshot.value
        if (current.phase != CallPhase.Incoming) return
        if (current.callId.isNotEmpty()) silenced.add(current.callId)
        stopRing()
        dismiss(context, current.conversationId)
        val camera = withCamera && current.mode == "video"
        _snapshot.value = current.copy(phase = CallPhase.Joining, status = "Joining…", minimized = false, cameraOn = camera)
        AirdTelecom.reportConnected(context, current.peer, camera)
        AirdConnectionService.showJoining(context)
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
                connect(context, response.json().optString("url"), response.json().optString("token"), current.mode == "video", camera)
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

    fun toggleSpeaker(context: Context) {
        val next = if (_snapshot.value.output == "speaker") "earpiece" else "speaker"
        val choice = CallDevices.audioOutputs(context, room).firstOrNull { CallDevices.outputKey(it) == next }
            ?: DeviceChoice(next, next.replaceFirstChar { it.uppercase() })
        chooseOutput(context, choice)
    }

    fun chooseOutput(context: Context, choice: DeviceChoice) {
        CallDevices.selectAudioOutput(context, room, choice)
        val key = CallDevices.outputKey(choice)
        val gen = ++routeHintGeneration
        _snapshot.value = _snapshot.value.copy(
            speaker = key == "speaker",
            output = key,
            playbackId = choice.id,
            routeHint = CallDevices.hint(choice),
        )
        updateProximity(context)
        scope.launch {
            delay(1500)
            if (routeHintGeneration == gen) _snapshot.value = _snapshot.value.copy(routeHint = "")
        }
    }

    fun setCallMode(context: Context, video: Boolean) {
        val current = _snapshot.value
        if (current.phase == CallPhase.Idle || current.phase == CallPhase.Incoming) return
        applyMode(context, video, enableCamera = video, notifyPeer = true)
    }

    private fun applyMode(context: Context, video: Boolean, enableCamera: Boolean, notifyPeer: Boolean) {
        val current = _snapshot.value
        if (current.phase == CallPhase.Idle) return
        val mode = if (video) "video" else "audio"
        val cameraOn = when {
            !video -> false
            enableCamera -> true
            else -> current.cameraOn
        }
        if (current.mode == mode && current.cameraOn == cameraOn) return
        var next = current.copy(mode = mode, cameraOn = cameraOn)
        if (video) {
            next = next.copy(speaker = true, output = "speaker", playbackId = "speaker")
            CallDevices.applyOutput(context, room, "speaker")
        }
        if (!video) next = next.copy(sharing = false)
        val hint = if (video) "Video call" else "Voice call"
        val gen = ++routeHintGeneration
        _snapshot.value = next.copy(routeHint = hint)
        if (!video && current.sharing) stopShare(context)
        updateProximity(context)
        scope.launch {
            try {
                if (!cameraOn) _localVideo.value = null
                room?.localParticipant?.setCameraEnabled(cameraOn)
                if (cameraOn) {
                    val track = room?.localParticipant?.getTrackPublication(Track.Source.CAMERA)?.track as? VideoTrack
                    if (track != null) _localVideo.value = track
                }
            } catch (_: Exception) {
            }
        }
        if (notifyPeer) signal("call_mode", current.conversationId, current.callId, mode)
        AirdConnectionService.showInCall(context, next.peer.ifEmpty { "Aird Chat" }, video, cameraOn)
        scope.launch {
            delay(1500)
            if (routeHintGeneration == gen) _snapshot.value = _snapshot.value.copy(routeHint = "")
        }
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
        val choice = CallDevices.audioOutputs(context, room).firstOrNull { it.id == id }
            ?: CallDevices.audioOutputs(context, room).firstOrNull { CallDevices.outputKey(it) == id }
            ?: DeviceChoice(id, id)
        chooseOutput(context, choice)
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
        _backgroundOnly.value = false
        _snapshot.value = _snapshot.value.copy(minimized = true)
    }

    fun hideBackground() {
        _backgroundOnly.value = true
        _snapshot.value = _snapshot.value.copy(minimized = true)
    }

    fun expand() {
        _backgroundOnly.value = false
        _snapshot.value = _snapshot.value.copy(minimized = false)
    }

    private suspend fun connect(context: Context, url: String, token: String, video: Boolean, camera: Boolean = video) {
        if (url.isEmpty() || token.isEmpty()) {
            finish(context, "Call is not configured", signal = false, shade = null)
            return
        }
        val audio = context.getSystemService(AudioManager::class.java)
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        val next = LiveKit.create(context.applicationContext)
        (next.audioSwitchHandler as? AudioSwitchHandler)?.forceHandleAudioRouting = true
        room = next
        events?.cancel()
        val gen = generation
        events = scope.launch {
            next.events.collect { event -> if (gen == generation) onRoomEvent(context, event) }
        }
        next.connect(url, token)
        next.localParticipant.setMicrophoneEnabled(true)
        if (camera) next.localParticipant.setCameraEnabled(true)
        val output = _snapshot.value.output.ifEmpty { if (video) "speaker" else "earpiece" }
        val playback = _snapshot.value.playbackId.ifEmpty { output }
        _snapshot.value = _snapshot.value.copy(speaker = output == "speaker", cameraOn = camera, output = output, playbackId = playback)
        CallDevices.applyOutput(context, next, playback)
        AirdConnectionService.showInCall(context, _snapshot.value.peer.ifEmpty { "Aird Chat" }, video, camera)
        if (next.remoteParticipants.isNotEmpty()) markConnected(context)
    }

    private fun onRoomEvent(context: Context, event: RoomEvent) {
        when (event) {
            is RoomEvent.TrackSubscribed -> {
                val track = event.track
                if (track is VideoTrack && event.publication.source != Track.Source.SCREEN_SHARE) _remoteVideo.value = track
                if (track is VideoTrack && event.publication.source == Track.Source.SCREEN_SHARE) _remoteVideo.value = track
                if (event.publication.kind == Track.Kind.AUDIO) {
                    val playback = _snapshot.value.playbackId.ifEmpty { _snapshot.value.output }
                    if (playback.isNotEmpty()) CallDevices.applyOutput(context, room, playback)
                }
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
                if (event.publication.kind == Track.Kind.AUDIO) {
                    val playback = _snapshot.value.playbackId.ifEmpty { _snapshot.value.output }
                    if (playback.isNotEmpty()) CallDevices.applyOutput(context, room, playback)
                }
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
        _backgroundOnly.value = false
        CallDevices.cancelRoutes()
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

    private fun signal(type: String, conversationId: String, callId: String, mode: String? = null) {
        if (conversationId.isEmpty()) return
        val payload = JSONObject().put("type", type).put("conversation_id", conversationId).put("call_id", callId)
        if (!mode.isNullOrEmpty()) payload.put("mode", mode)
        AirdSocket.send(payload.toString())
    }

    private fun missedTitle(mode: String): String = if (mode == "video") "Missed video call" else "Missed voice call"

    private var ringWakeLock: android.os.PowerManager.WakeLock? = null

    fun launchIncomingCallActivity(context: Context) {
        val current = _snapshot.value
        if (current.phase != CallPhase.Incoming) return
        acquireRingWakeLock(context)
        try {
            val callIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                putExtra("action", "incoming_call")
                putExtra("conversation_id", current.conversationId)
                putExtra("call_id", current.callId)
                putExtra("call_mode", current.mode)
                putExtra("call_from", current.peer)
            }
            context.startActivity(callIntent)
        } catch (_: Exception) {
        }
    }

    private fun ring(context: Context) {
        acquireRingWakeLock(context)
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

    private fun acquireRingWakeLock(context: Context) {
        try {
            if (ringWakeLock?.isHeld != true) {
                val power = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                @Suppress("DEPRECATION")
                ringWakeLock = power?.newWakeLock(
                    android.os.PowerManager.FULL_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or android.os.PowerManager.ON_AFTER_RELEASE,
                    "aird:IncomingCallRingWakeLock"
                )
                ringWakeLock?.acquire(30 * 1000L)
            }
        } catch (_: Exception) {
        }
    }

    private fun stopRing() {
        try {
            ringer?.stop()
        } catch (_: Exception) {
        }
        ringer = null
        releaseRingWakeLock()
    }

    private fun releaseRingWakeLock() {
        try {
            if (ringWakeLock?.isHeld == true) {
                ringWakeLock?.release()
            }
        } catch (_: Exception) {
        }
        ringWakeLock = null
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
        val notification = NotificationCompat.Builder(context, AirdConnectionService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle(title)
            .setContentText(peer.ifEmpty { "Aird" })
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(("missed:$conversationId").hashCode(), notification)
    }

    private fun dismiss(context: Context, conversationId: String) {
        AirdConnectionService.dismissIncoming(context)
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
        val earpiece = current.output == "earpiece" || (current.output.isEmpty() && !current.speaker)
        val needsProximity = current.phase == CallPhase.Live && current.mode == "audio" && earpiece
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
