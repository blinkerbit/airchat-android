package com.aird.call

import android.Manifest
import android.app.ActivityOptions
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.aird.AirdConnectionService
import com.aird.AppForeground
import com.aird.MainActivity
import com.aird.R
import com.aird.session.AirdHttp
import com.aird.session.AirdSocket
import com.aird.session.Session
import io.livekit.android.LiveKit
import io.livekit.android.RoomOptions
import io.livekit.android.events.DisconnectReason
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import android.telecom.DisconnectCause
import io.livekit.android.room.participant.BackupVideoCodec
import io.livekit.android.room.participant.VideoTrackPublishDefaults
import io.livekit.android.room.participant.VideoTrackPublishOptions
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoCaptureParameter
import io.livekit.android.room.track.VideoCodec
import io.livekit.android.room.track.VideoEncoding
import io.livekit.android.room.track.VideoTrack
import livekit.org.webrtc.RtpParameters
import io.livekit.android.util.flow
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
    val meet: Boolean = false,
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
    // The server calls it missed at 47s. This only covers a lost server event.
    private const val OUTGOING_WINDOW_MS = 52_000L
    private val _micNeeded = MutableStateFlow(false)
    /** True while an answer waits for the microphone permission prompt. */
    val micNeeded: StateFlow<Boolean> = _micNeeded
    private var answerWithCamera = false
    @Volatile private var app: Context? = null
    private val silenced = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
    private var room: Room? = null
    private var events: Job? = null
    private var localWatch: Job? = null
    private var remoteDrop: Job? = null
    private var ringer: Ringtone? = null
    private var generation = 0
    private var mediaUrl = ""
    private var mediaToken = ""
    private var mediaVideo = false
    private var mediaCamera = false
    private var mediaTries = 0
    private var mediaRecovering = false
    private const val REMOTE_GRACE_MS = 5_000L
    private const val MEDIA_TRIES = 3
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

    fun hasMicrophone(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Result of the permission prompt. Without a microphone we still join, muted. */
    fun microphoneAnswered(context: Context, granted: Boolean) {
        if (!_micNeeded.value) return
        _micNeeded.value = false
        val camera = answerWithCamera
        answerWithCamera = false
        accept(context, camera, micChecked = true)
    }

    fun onEvent(context: Context, raw: String) {
        app = context.applicationContext
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
                    if (current.conversationId == conv) return
                    // Already on a call. Tell the other caller now instead of leaving them ringing.
                    if (conv.isNotEmpty()) signal("call_decline", conv, callId)
                    silence(callId)
                    shade(context, missedTitle(data.optString("mode")), data.optString("from", "Aird"), conv)
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
                if (!inApp) launchIncomingCallActivity(context)
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
                silence(callId)
                if (!same(current, conv)) return
                when (current.phase) {
                    CallPhase.Outgoing -> finish(context, "Call declined", signal = false, shade = "Call declined", telecomCause = DisconnectCause.REJECTED)
                    CallPhase.Incoming -> finish(context, "", signal = false, shade = null, telecomCause = DisconnectCause.REJECTED)
                    else -> finish(context, "Call ended", signal = false, shade = null, telecomCause = DisconnectCause.REJECTED)
                }
            }
            "call_missed" -> {
                silence(callId)
                val current = _snapshot.value
                if (same(current, conv)) {
                    if (callId.isNotEmpty() && current.callId.isNotEmpty() && callId != current.callId) return
                    when (current.phase) {
                        CallPhase.Outgoing -> finish(context, "No answer", signal = false, shade = null, telecomCause = DisconnectCause.MISSED)
                        CallPhase.Incoming -> finish(context, "", signal = false, shade = missedTitle(current.mode), telecomCause = DisconnectCause.MISSED)
                        else -> Unit
                    }
                    return
                }
                if (current.phase == CallPhase.Idle && conv.isNotEmpty() && !seenMissed(context, callId)) {
                    markMissed(context, callId)
                    shade(context, missedTitle(data.optString("mode")), data.optString("from", "Aird"), conv)
                }
            }
            "call_answered" -> {
                // Another device of ours picked up. This one stops ringing quietly.
                silence(callId)
                val current = _snapshot.value
                if (current.phase != CallPhase.Incoming || !same(current, conv)) return
                if (callId.isNotEmpty() && current.callId.isNotEmpty() && callId != current.callId) return
                finish(context, "", signal = false, shade = null, telecomCause = DisconnectCause.ANSWERED_ELSEWHERE)
            }
            "call_end", "call_ended" -> {
                val current = _snapshot.value
                // Remember it so a late or replayed invite for this call never rings.
                silence(callId)
                if (!same(current, conv)) return
                when (current.phase) {
                    CallPhase.Incoming -> finish(context, "", signal = false, shade = missedTitle(current.mode), telecomCause = DisconnectCause.MISSED)
                    CallPhase.Idle -> Unit
                    else -> finish(context, "Call ended", signal = false, shade = null, telecomCause = DisconnectCause.REMOTE)
                }
            }
        }
    }

    private fun dropIfDead(context: Context, conv: String, callId: String): Boolean {
        val current = _snapshot.value
        if (current.phase != CallPhase.Incoming || current.callId != callId) return true
        silence(callId)
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

    fun restoreIncoming(context: Context, conversationId: String, callId: String, mode: String, from: String) {
        if (conversationId.isEmpty()) return
        val current = _snapshot.value
        if (current.phase == CallPhase.Incoming && current.conversationId == conversationId) {
            ring(context)
            return
        }
        if (current.phase != CallPhase.Idle) return
        _snapshot.value = CallSnapshot(
            phase = CallPhase.Incoming,
            conversationId = conversationId,
            callId = callId,
            peer = from.ifEmpty { "Incoming call" },
            mode = if (mode == "video") "video" else "audio",
            status = if (mode == "video") "Incoming video call" else "Incoming voice call",
        )
        ring(context)
    }

    /** After the socket comes back: ring if the call is still live, else show a missed notice. */
    fun recover(context: Context) {
        app = context.applicationContext
        scope.launch {
            try {
                val response = AirdHttp.get(context, "/api/chat/call-ring")
                if (!response.ok) return@launch
                val body = response.json()
                val ring = body.optJSONObject("ring")
                if (ring != null && ring.optString("conversation_id").isNotEmpty()) {
                    if (!ring.has("type")) ring.put("type", "call_invite")
                    onEvent(context, ring.toString())
                    return@launch
                }
                val missed = body.optJSONObject("missed")
                if (missed != null && missed.optString("conversation_id").isNotEmpty()) {
                    if (!missed.has("type")) missed.put("type", "call_missed")
                    onEvent(context, missed.toString())
                }
            } catch (err: Exception) {
                if (err is kotlinx.coroutines.CancellationException) throw err
            }
        }
    }

    fun joinMeet(context: Context, meetId: String, title: String): Boolean {
        if (meetId.isBlank() || _snapshot.value.phase != CallPhase.Idle) return false
        app = context.applicationContext
        val started = generation
        val route = CallDevices.defaultOutput(context, video = true)
        val routeKey = CallDevices.outputKey(route)
        _snapshot.value = CallSnapshot(
            phase = CallPhase.Joining,
            peer = title.ifBlank { "Meeting" },
            mode = "video",
            status = "Joining…",
            speaker = routeKey == "speaker",
            cameraOn = true,
            output = routeKey,
            playbackId = route.id,
            meet = true,
        )
        scope.launch {
            try {
                val response = AirdHttp.send(context, "/api/meet/$meetId/token", "POST", JSONObject())
                if (generation != started) return@launch
                if (!response.ok) {
                    val raw = response.json().optString("error").trim()
                    val message = if (raw.isBlank() || raw.length > 160 || '<' in raw) "Could not join" else raw
                    finish(context, message, signal = false, shade = null)
                    return@launch
                }
                connect(
                    context,
                    response.json().optString("url"),
                    response.json().optString("token"),
                    video = true,
                    camera = true,
                )
                if (generation == started && _snapshot.value.meet && _snapshot.value.phase == CallPhase.Joining) {
                    markConnected(context)
                }
            } catch (err: Exception) {
                if (err is kotlinx.coroutines.CancellationException) throw err
                if (generation != started) return@launch
                finish(context, "Could not join", signal = false, shade = null)
            }
        }
        return true
    }

    fun start(context: Context, conversationId: String, peer: String, mode: String) {
        app = context.applicationContext
        if (_snapshot.value.phase != CallPhase.Idle) return
        val startedIn = generation
        val route = CallDevices.defaultOutput(context, video = mode == "video")
        val routeKey = CallDevices.outputKey(route)
        _snapshot.value = CallSnapshot(
            phase = CallPhase.Outgoing,
            conversationId = conversationId,
            peer = peer,
            mode = mode,
            status = "Calling…",
            speaker = routeKey == "speaker",
            cameraOn = mode == "video",
            output = routeKey,
            playbackId = route.id,
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
                if (generation != startedIn) return@launch
                val callId = response.json().optString("call_id")
                _snapshot.value = _snapshot.value.copy(callId = callId)
                watchOutgoing(context, startedIn, callId)
                connect(context, response.json().optString("url"), response.json().optString("token"), mode == "video")
            } catch (err: Exception) {
                if (err is kotlinx.coroutines.CancellationException) throw err
                if (generation != startedIn) return@launch
                finish(context, err.message ?: "Call failed", signal = false, shade = null)
            }
        }
    }

    /** Nobody picked up. Hang up for the caller and stop the other ring. */
    private fun watchOutgoing(context: Context, startedIn: Int, callId: String) {
        scope.launch {
            delay(OUTGOING_WINDOW_MS)
            val current = _snapshot.value
            if (generation == startedIn && current.phase == CallPhase.Outgoing && current.callId == callId) {
                finish(context, "No answer", signal = true, shade = null, telecomCause = DisconnectCause.MISSED)
            }
        }
    }

    fun accept(context: Context, withCamera: Boolean = false, micChecked: Boolean = false) {
        app = context.applicationContext
        val current = _snapshot.value
        if (current.phase != CallPhase.Incoming) return
        if (!micChecked && !hasMicrophone(context)) {
            // Ask first. Joining without the permission used to fail the whole call.
            answerWithCamera = withCamera
            _micNeeded.value = true
            launchIncomingCallActivity(context)
            return
        }
        silence(current.callId)
        stopRing()
        dismiss(context, current.conversationId)
        val camera = withCamera && current.mode == "video"
        val startedIn = generation
        _snapshot.value = current.copy(phase = CallPhase.Joining, status = "Joining…", minimized = false, cameraOn = camera)
        AirdTelecom.reportConnected(context, current.peer, camera)
        AirdConnectionService.showJoining(context)
        scope.launch {
            try {
                val live = AirdHttp.get(context, "/api/chat/call-ring")
                if (generation != startedIn) return@launch
                if (live.ok) {
                    val ring = live.json().optJSONObject("ring")
                    val ringId = ring?.optString("call_id").orEmpty()
                    val still = ring != null &&
                        ring.optString("conversation_id") == current.conversationId &&
                        (current.callId.isEmpty() || ringId.isEmpty() || ringId == current.callId)
                    if (!still) {
                        finish(context, "", signal = false, shade = missedTitle(current.mode), telecomCause = DisconnectCause.MISSED)
                        return@launch
                    }
                }
                val response = AirdHttp.send(
                    context,
                    "/api/chat/conversations/${current.conversationId}/call",
                    "POST",
                    JSONObject().put("mode", current.mode).put("invite", false).put("call_id", current.callId),
                )
                if (generation != startedIn) return@launch
                if (!response.ok) {
                    val gone = response.code == 409
                    finish(
                        context,
                        if (gone) "" else "Could not join",
                        signal = false,
                        shade = if (gone) missedTitle(current.mode) else null,
                        telecomCause = if (gone) DisconnectCause.MISSED else DisconnectCause.ERROR,
                    )
                    return@launch
                }
                signal("call_accept", current.conversationId, current.callId)
                connect(context, response.json().optString("url"), response.json().optString("token"), current.mode == "video", camera)
            } catch (err: Exception) {
                if (err is kotlinx.coroutines.CancellationException) throw err
                if (generation != startedIn) return@launch
                finish(context, err.message ?: "Could not join", signal = false, shade = null)
            }
        }
    }

    fun decline(context: Context) {
        val current = _snapshot.value
        if (current.phase != CallPhase.Incoming) return
        silence(current.callId)
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
        val outputs = CallDevices.audioOutputs(context, room)
        val onSpeaker = _snapshot.value.speaker || _snapshot.value.output == "speaker"
        val next = if (onSpeaker) {
            outputs.firstOrNull { CallDevices.outputKey(it) == "earpiece" }
                ?: outputs.firstOrNull { CallDevices.outputKey(it) == "headset" }
        } else {
            outputs.firstOrNull { CallDevices.outputKey(it) == "speaker" }
        } ?: return
        chooseOutput(context, next)
    }

    fun onTelecomAudioRouteChanged(context: Context, isSpeaker: Boolean) {
        val currentIsSpeaker = _snapshot.value.speaker || _snapshot.value.output == "speaker"
        if (currentIsSpeaker != isSpeaker) {
            val targetKey = if (isSpeaker) "speaker" else "earpiece"
            val choice = CallDevices.audioOutputs(context, room).firstOrNull { CallDevices.outputKey(it) == targetKey }
                ?: DeviceChoice(targetKey, targetKey.replaceFirstChar { it.uppercase() })
            chooseOutput(context, choice)
        }
    }

    private fun showRoute(context: Context, choice: DeviceChoice) {
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

    fun chooseOutput(context: Context, choice: DeviceChoice) {
        CallDevices.selectAudioOutput(context, room, choice)
        showRoute(context, choice)
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
        CallDevices.noteCallVideo(video)
        if (video && current.output != "headset") {
            val choice = CallDevices.defaultOutput(context, video = true)
            CallDevices.play(context, room, choice, lock = false)
            val key = CallDevices.outputKey(choice)
            next = next.copy(speaker = key == "speaker", output = key, playbackId = choice.id)
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
                syncLocalVideo(cameraOn = cameraOn)
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
                syncLocalVideo(cameraOn = next)
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

    /** Cellular and other metered links. Wi-Fi and ethernet stay on the sharper HD profile. */
    private fun meteredNetwork(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        ) {
            return false
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return true
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /**
     * One hardware H.264 stream. Simulcast VP8 was uploading several layers at once and
     * the default encoder kept 30 fps by shrinking the picture, which is why the same
     * link looked soft next to a single-stream call.
     */
    private fun callRoomOptions(context: Context): RoomOptions {
        val metered = meteredNetwork(context)
        val capture = if (metered) VideoCaptureParameter(960, 540, 15) else VideoCaptureParameter(1280, 720, 24)
        val encoding = if (metered) VideoEncoding(900_000, 15) else VideoEncoding(2_000_000, 24)
        return RoomOptions(
            adaptiveStream = false,
            dynacast = true,
            videoTrackCaptureDefaults = LocalVideoTrackOptions(captureParams = capture),
            videoTrackPublishDefaults = VideoTrackPublishDefaults(
                videoEncoding = encoding,
                simulcast = false,
                videoCodec = VideoCodec.H264.codecName,
                backupCodec = BackupVideoCodec(
                    codec = VideoCodec.VP8.codecName,
                    encoding = encoding,
                    simulcast = false,
                ),
                degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION,
            ),
        )
    }

    private suspend fun connect(context: Context, url: String, token: String, video: Boolean, camera: Boolean = video) {
        if (url.isEmpty() || token.isEmpty()) {
            finish(context, "Call is not configured", signal = false, shade = null)
            return
        }
        mediaUrl = url
        mediaToken = token
        mediaVideo = video
        mediaCamera = camera
        val gen = generation
        val audio = context.getSystemService(AudioManager::class.java)
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        val next = LiveKit.create(context.applicationContext, callRoomOptions(context))
        CallDevices.prepareRouter(next, speakerDefault = video)
        room = next
        CallDevices.onRouteChanged = { choice -> showRoute(context, choice) }
        val routed = CallDevices.begin(context, next, video)
        val routedKey = CallDevices.outputKey(routed)
        _snapshot.value = _snapshot.value.copy(
            speaker = routedKey == "speaker",
            output = routedKey,
            playbackId = routed.id,
        )
        events?.cancel()
        events = scope.launch {
            next.events.collect { event -> if (gen == generation) onRoomEvent(context, event) }
        }
        next.connect(url, token)
        if (generation != gen) {
            try {
                next.disconnect()
            } catch (_: Exception) {
            }
            return
        }
        // A blocked microphone or camera must not fail the call. Join listen-only instead.
        var hint = ""
        val micOn = hasMicrophone(context) && try {
            next.localParticipant.setMicrophoneEnabled(true)
            true
        } catch (err: Exception) {
            if (err is kotlinx.coroutines.CancellationException) throw err
            false
        }
        if (!micOn) {
            _snapshot.value = _snapshot.value.copy(muted = true)
            hint = "Microphone is off. Allow it in Settings to talk."
        }
        var cameraOn = camera
        if (camera) {
            cameraOn = try {
                next.localParticipant.setCameraEnabled(true)
                true
            } catch (err: Exception) {
                if (err is kotlinx.coroutines.CancellationException) throw err
                false
            }
            if (!cameraOn && hint.isEmpty()) hint = "Camera is off. Allow it in Settings to share video."
        }
        if (generation != gen || _snapshot.value.phase == CallPhase.Idle) return
        val output = _snapshot.value.output.ifEmpty { if (video) "speaker" else "earpiece" }
        val playback = _snapshot.value.playbackId.ifEmpty { output }
        _snapshot.value = _snapshot.value.copy(
            speaker = output == "speaker",
            cameraOn = cameraOn,
            output = output,
            playbackId = playback,
            routeHint = hint.ifEmpty { _snapshot.value.routeHint },
        )
        if (hint.isNotEmpty()) {
            scope.launch {
                delay(6000)
                if (_snapshot.value.routeHint == hint) _snapshot.value = _snapshot.value.copy(routeHint = "")
            }
        }
        CallDevices.reapply(context, next)
        if (generation != gen || _snapshot.value.phase == CallPhase.Idle) return
        AirdConnectionService.showInCall(context, _snapshot.value.peer.ifEmpty { "Aird Chat" }, video, cameraOn)
        syncLocalVideo(next, cameraOn)
        watchLocalVideo(next, gen)
        if (next.remoteParticipants.isNotEmpty()) markConnected(context)
    }

    private fun onRoomEvent(context: Context, event: RoomEvent) {
        when (event) {
            is RoomEvent.TrackSubscribed -> {
                val track = event.track
                if (track is VideoTrack && event.publication.source != Track.Source.SCREEN_SHARE) _remoteVideo.value = track
                if (track is VideoTrack && event.publication.source == Track.Source.SCREEN_SHARE) _remoteVideo.value = track
                if (event.publication.kind == Track.Kind.AUDIO) CallDevices.reapply(context, room)
                markConnected(context)
            }
            is RoomEvent.ParticipantConnected -> {
                remoteDrop?.cancel()
                markConnected(context)
            }
            is RoomEvent.ParticipantDisconnected -> watchRemoteDrop(context)
            is RoomEvent.Reconnecting -> {
                remoteDrop?.cancel()
                val phase = _snapshot.value.phase
                if (phase == CallPhase.Live || phase == CallPhase.Joining || phase == CallPhase.Outgoing) {
                    _snapshot.value = _snapshot.value.copy(status = "Reconnecting…")
                }
            }
            is RoomEvent.Reconnected -> {
                remoteDrop?.cancel()
                mediaTries = 0
                mediaRecovering = false
                reapplyMedia(context)
                markConnected(context)
            }
            is RoomEvent.TrackPublished -> {
                if (event.publication.kind == Track.Kind.AUDIO) CallDevices.reapply(context, room)
                if (event.participant != room?.localParticipant) return
                val track = event.publication.track as? VideoTrack ?: return
                if (event.publication.source == Track.Source.SCREEN_SHARE) _screenVideo.value = track
                else _localVideo.value = track
            }
            is RoomEvent.TrackMuted -> {
                if (event.publication.source == Track.Source.CAMERA) {
                    if (event.participant == room?.localParticipant) {
                        if (!_snapshot.value.cameraOn) _localVideo.value = null
                    } else {
                        _remoteVideo.value = null
                    }
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
                if (event.reason == DisconnectReason.CLIENT_INITIATED) return
                val phase = _snapshot.value.phase
                if (phase == CallPhase.Idle) return
                if (event.room !== room && room != null) return
                when (event.reason) {
                    DisconnectReason.ROOM_DELETED,
                    DisconnectReason.ROOM_CLOSED,
                    DisconnectReason.PARTICIPANT_REMOVED,
                    DisconnectReason.DUPLICATE_IDENTITY,
                    DisconnectReason.USER_REJECTED,
                    DisconnectReason.SERVER_SHUTDOWN,
                    -> finish(context, "Call ended", signal = false, shade = null, telecomCause = DisconnectCause.REMOTE)
                    else -> recoverMedia(context)
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
        mediaTries = 0
        mediaRecovering = false
        if (current.phase != CallPhase.Live || current.status != "Connected") {
            _snapshot.value = current.copy(phase = CallPhase.Live, status = "Connected")
        }
        updateProximity(context)
        AirdTelecom.activate()
    }

    private fun watchRemoteDrop(context: Context) {
        if (_snapshot.value.meet) return
        val phase = _snapshot.value.phase
        if (phase != CallPhase.Live && phase != CallPhase.Joining) return
        val active = room ?: return
        if (active.remoteParticipants.isNotEmpty()) return
        remoteDrop?.cancel()
        val gen = generation
        remoteDrop = scope.launch {
            delay(REMOTE_GRACE_MS)
            if (generation != gen) return@launch
            val still = _snapshot.value.phase
            if (still != CallPhase.Live && still != CallPhase.Joining) return@launch
            if (room?.remoteParticipants?.isNotEmpty() == true) return@launch
            finish(context, "Call ended", signal = false, shade = null, telecomCause = DisconnectCause.REMOTE)
        }
    }

    private fun recoverMedia(context: Context) {
        val phase = _snapshot.value.phase
        if (phase != CallPhase.Live && phase != CallPhase.Joining && phase != CallPhase.Outgoing) return
        if (mediaRecovering) return
        if (mediaTries >= MEDIA_TRIES || mediaUrl.isEmpty()) {
            finish(context, "Call dropped", signal = true, shade = null)
            return
        }
        mediaRecovering = true
        mediaTries += 1
        val gen = generation
        val conv = _snapshot.value.conversationId
        val callId = _snapshot.value.callId
        val video = mediaVideo
        val camera = mediaCamera
        _snapshot.value = _snapshot.value.copy(status = "Reconnecting…")
        scope.launch {
            delay(1_000L * mediaTries)
            if (generation != gen) return@launch
            try {
                var url = mediaUrl
                var token = mediaToken
                if (conv.isNotEmpty()) {
                    val remint = AirdHttp.send(
                        context,
                        "/api/chat/conversations/$conv/call",
                        "POST",
                        JSONObject().put("mode", if (video) "video" else "audio").put("invite", false).put("call_id", callId),
                    )
                    if (generation != gen) return@launch
                    if (remint.ok) {
                        url = remint.json().optString("url").ifEmpty { url }
                        token = remint.json().optString("token").ifEmpty { token }
                    } else if (remint.code == 409) {
                        finish(context, "Call ended", signal = false, shade = null, telecomCause = DisconnectCause.REMOTE)
                        return@launch
                    }
                }
                connect(context, url, token, video, camera)
                mediaRecovering = false
            } catch (err: Exception) {
                if (err is kotlinx.coroutines.CancellationException) throw err
                mediaRecovering = false
                if (generation == gen) recoverMedia(context)
            }
        }
    }

    private fun reapplyMedia(context: Context) {
        val active = room ?: return
        val current = _snapshot.value
        scope.launch {
            try {
                if (!current.muted && hasMicrophone(context)) active.localParticipant.setMicrophoneEnabled(true)
                if (current.cameraOn) {
                    active.localParticipant.setCameraEnabled(true)
                    syncLocalVideo(active, true)
                }
                CallDevices.reapply(context, active)
            } catch (err: Exception) {
                if (err is kotlinx.coroutines.CancellationException) throw err
            }
        }
    }

    private fun syncLocalVideo(active: Room? = room, cameraOn: Boolean = _snapshot.value.cameraOn) {
        val live = active ?: return
        if (!cameraOn) {
            _localVideo.value = null
            return
        }
        val pubs = live.localParticipant.videoTrackPublications
        val camera = pubs.firstNotNullOfOrNull { (pub, track) ->
            if (pub.source == Track.Source.CAMERA) track as? VideoTrack else null
        } ?: pubs.firstNotNullOfOrNull { (pub, track) ->
            if (pub.source != Track.Source.SCREEN_SHARE) track as? VideoTrack else null
        }
        if (camera != null) _localVideo.value = camera
    }

    private fun watchLocalVideo(live: Room, gen: Int) {
        localWatch?.cancel()
        localWatch = scope.launch {
            live.localParticipant::videoTrackPublications.flow.collect {
                if (generation != gen || room !== live) return@collect
                syncLocalVideo(live)
            }
        }
    }

    private fun finish(context: Context, message: String, signal: Boolean, shade: String?, telecomCause: Int = DisconnectCause.LOCAL) {
        app = context.applicationContext
        val current = _snapshot.value
        if (current.phase == CallPhase.Idle && current.status.isEmpty() && message.isEmpty()) return
        _micNeeded.value = false
        answerWithCamera = false
        remoteDrop?.cancel()
        remoteDrop = null
        mediaRecovering = false
        mediaTries = 0
        mediaUrl = ""
        mediaToken = ""
        _pipClose.value = _pipClose.value + 1
        _backgroundOnly.value = false
        CallDevices.onRouteChanged = null
        CallDevices.release(context)
        AirdTelecom.end(telecomCause)
        releaseProximity()
        generation += 1
        val gen = generation
        if (signal && current.conversationId.isNotEmpty() && current.phase != CallPhase.Idle) {
            signal("call_end", current.conversationId, current.callId)
        }
        silence(current.callId)
        events?.cancel()
        localWatch?.cancel()
        localWatch = null
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
        if (shade != null) shade(context, shade, current.peer, current.conversationId)
        if (message.isEmpty()) {
            _snapshot.value = CallSnapshot()
            return
        }
        _snapshot.value = CallSnapshot(status = message, peer = current.peer, mode = current.mode)
        scope.launch {
            delay(1800)
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
        if (AirdSocket.send(payload.toString())) return
        // The socket is down. A lost decline or end leaves the other side ringing, so go over HTTP.
        val context = app ?: return
        scope.launch {
            repeat(4) { attempt ->
                try {
                    val response = AirdHttp.send(
                        context,
                        "/api/chat/conversations/$conversationId/call/signal",
                        "POST",
                        payload,
                    )
                    if (response.ok || response.code in 400..499) return@launch
                } catch (err: Exception) {
                    if (err is kotlinx.coroutines.CancellationException) throw err
                }
                delay(1000L shl attempt)
            }
        }
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
            com.aird.IncomingCallReceiver.openFromBackground(context, callIntent)
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
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(callNotificationId(conversationId))
        manager.cancel(("missed:$conversationId").hashCode())
    }

    private fun silence(callId: String) {
        if (callId.isEmpty()) return
        synchronized(silenced) {
            silenced.add(callId)
            val extra = silenced.size - 40
            if (extra > 0) {
                val drop = silenced.take(extra)
                silenced.removeAll(drop.toSet())
            }
        }
    }

    private fun seenMissed(context: Context, callId: String): Boolean {
        if (callId.isEmpty()) return false
        val ids = context.getSharedPreferences("aird_missed_calls", Context.MODE_PRIVATE)
            .getStringSet("ids", emptySet()) ?: emptySet()
        return callId in ids
    }

    private fun markMissed(context: Context, callId: String) {
        if (callId.isEmpty()) return
        val prefs = context.getSharedPreferences("aird_missed_calls", Context.MODE_PRIVATE)
        val ids = (prefs.getStringSet("ids", emptySet()) ?: emptySet()).toMutableList()
        if (callId in ids) return
        ids.add(callId)
        prefs.edit().putStringSet("ids", ids.takeLast(20).toSet()).apply()
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
