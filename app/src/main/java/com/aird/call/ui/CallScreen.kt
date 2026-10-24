package com.aird.call.ui

import android.media.projection.MediaProjectionManager
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.StopScreenShare
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.aird.MainActivity
import com.aird.call.CallController
import com.aird.call.CallDevices
import com.aird.call.CallPhase
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.track.VideoTrack
import com.aird.ui.LocalAirdReducedMotion
import com.aird.ui.PressIconButton
import livekit.org.webrtc.RendererCommon
import kotlin.math.abs
import kotlin.math.roundToInt

private val CallBg = Color(0xFF0B141A)
private val CallGlass = Color(0x66000000)

@Composable
fun CallScreen() {
    val context = LocalContext.current
    val call by CallController.snapshot.collectAsState()
    val remote by CallController.remoteVideo.collectAsState()
    val local by CallController.localVideo.collectAsState()
    val screen by CallController.screenVideo.collectAsState()
    val swapped by CallController.swapped.collectAsState()
    val collapsed by CallController.tileCollapsed.collectAsState()
    val tile by CallController.tile.collectAsState()
    val inPip by MainActivity.inPip.collectAsState()
    var menu by remember { mutableStateOf("") }
    val capture = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == android.app.Activity.RESULT_OK && data != null) CallController.beginShare(context, data)
    }
    val localTrack = if (call.cameraOn) local else null
    val self = if (call.sharing) screen ?: localTrack else localTrack
    val full = if (call.mode == "video") {
        if (swapped) self ?: remote else remote ?: self
    } else {
        null
    }
    val tileFeed = if (call.mode != "video" || full == null) null else if (swapped) remote?.takeIf { it != full } else self?.takeIf { it != full }
    Box(Modifier.fillMaxSize().background(CallBg)) {
        if (full != null) VideoSurface(full, Modifier.fillMaxSize())
        if (!inPip && full == null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(112.dp).clip(CircleShape).background(Color(0xFF1F2C34)), contentAlignment = Alignment.Center) {
                    Text(call.peer.ifEmpty { "A" }.take(1).uppercase(), color = Color.White, fontSize = 42.sp, fontWeight = FontWeight.SemiBold)
                }
                Text(call.peer.ifEmpty { "Aird" }, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 18.dp))
                FadingStatus(call.status, Modifier.padding(top = 6.dp), Color(0xFFB8C4CC))
            }
        }
        if (!inPip && full != null) {
            val overlay = if (call.status.isNotEmpty() && call.status != "Connected") call.status else ""
            FadingStatus(overlay, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 56.dp), Color.White, 18.sp)
        }
        if (inPip) {
            FadingStatus(
                if (call.phase == CallPhase.Idle) call.status else "",
                Modifier.align(Alignment.Center),
                Color.White,
                18.sp,
            )
        }
        if (!inPip && tileFeed != null && !collapsed && call.phase != CallPhase.Idle) {
            Box(
                Modifier.align(Alignment.TopEnd).offset { IntOffset(tile.x.roundToInt(), tile.y.roundToInt()) }.padding(top = 48.dp, end = 16.dp),
            ) {
                VideoSurface(
                    tileFeed,
                    Modifier.size(width = 108.dp, height = 160.dp).clip(RoundedCornerShape(16.dp)).pointerInput(tileFeed) {
                        var moved = 0f
                        detectDragGestures(
                            onDrag = { change, drag ->
                                change.consume()
                                moved += abs(drag.x) + abs(drag.y)
                                CallController.moveTile(tile.x + drag.x, tile.y + drag.y)
                            },
                            onDragEnd = {
                                if (moved < 12f) CallController.swapFeeds()
                            },
                        )
                    },
                )
                Text(
                    "–",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.TopStart).clickable { CallController.collapseTile() }.padding(6.dp),
                    fontSize = 22.sp,
                )
            }
        }
        if (!inPip && collapsed && tileFeed != null) {
            Box(
                Modifier.align(Alignment.CenterEnd).offset { IntOffset(tile.x.roundToInt(), tile.y.roundToInt()) }
                    .size(width = 28.dp, height = 72.dp)
                    .clip(RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
                    .background(CallGlass)
                    .clickable { CallController.restoreTile() },
            )
        }
        if (!inPip && call.phase != CallPhase.Idle) {
            Column(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 12.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (call.phase == CallPhase.Incoming) {
                        CallButton(Color(0xFF25D366), Icons.Default.Call, "Accept") { CallController.accept(context) }
                        CallButton(Color(0xFFE53935), Icons.Default.CallEnd, "Decline") { CallController.decline(context) }
                    } else {
                        CallButton(CallGlass, if (call.muted) Icons.Default.MicOff else Icons.Default.Mic, "Mute", CallController::toggleMute)
                        Box {
                            val audioChoices = remember(call.speaker) { CallDevices.audioOutputs(context, CallController.room()) }
                            val audioIcon = if (call.speaker) Icons.AutoMirrored.Filled.VolumeUp else Icons.Default.Headphones
                            CallButton(CallGlass, audioIcon, if (call.speaker) "Speaker" else "Earpiece") {
                                val hasBluetooth = audioChoices.any { it.label.contains("Bluetooth", true) || it.label.contains("Wired", true) }
                                if (hasBluetooth) {
                                    menu = if (menu == "audio") "" else "audio"
                                } else {
                                    CallController.toggleSpeaker(context)
                                }
                            }
                            AudioMenu(
                                open = menu == "audio",
                                devices = audioChoices,
                                onDismiss = { menu = "" },
                                onSelect = { choice ->
                                    menu = ""
                                    CallDevices.selectAudioOutput(context, CallController.room(), choice)
                                    CallController.updateSpeakerState(choice.label.contains("Speaker", true))
                                },
                            )
                        }
                        if (call.mode == "video") {
                            CallButton(CallGlass, if (call.cameraOn) Icons.Default.Videocam else Icons.Default.VideocamOff, "Camera", CallController::toggleCamera)
                            if (call.cameraOn) {
                                CallButton(CallGlass, Icons.Default.Cameraswitch, "Switch Camera") {
                                    CallController.switchCamera(context)
                                }
                            }
                            CallButton(CallGlass, if (call.sharing) Icons.Default.StopScreenShare else Icons.Default.ScreenShare, "Share") {
                                if (call.sharing) CallController.stopShare(context)
                                else {
                                    val manager = context.getSystemService(MediaProjectionManager::class.java)
                                    capture.launch(manager.createScreenCaptureIntent())
                                }
                            }
                        }
                        CallButton(Color(0xFFE53935), Icons.Default.CallEnd, "End") { CallController.hangUp(context) }
                    }
                }
            }
            if (call.phase == CallPhase.Live || call.phase == CallPhase.Outgoing || call.phase == CallPhase.Joining) {
                IconButton(
                    onClick = { CallController.minimize() },
                    modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 8.dp, top = 12.dp).size(56.dp),
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Minimize", tint = Color.White, modifier = Modifier.size(32.dp))
                }
            }
        }
    }
}

@Composable
fun CallBubble() {
    val call by CallController.snapshot.collectAsState()
    val remote by CallController.remoteVideo.collectAsState()
    val local by CallController.localVideo.collectAsState()
    val screen by CallController.screenVideo.collectAsState()
    val swapped by CallController.swapped.collectAsState()
    val bubble by CallController.bubble.collectAsState()
    val context = LocalContext.current
    val self = if (call.sharing) screen ?: local else local
    val feed = if (swapped) self ?: remote else remote ?: self
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.align(Alignment.TopEnd)
                .offset { IntOffset(bubble.x.roundToInt(), bubble.y.roundToInt()) }
                .padding(top = 72.dp, end = 12.dp)
                .size(width = 200.dp, height = 356.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color.Black),
        ) {
            if (feed != null) {
                VideoSurface(
                    feed,
                    Modifier.fillMaxSize().pointerInput(feed) {
                        var moved = 0f
                        detectDragGestures(
                            onDragStart = { moved = 0f },
                            onDrag = { change, drag ->
                                change.consume()
                                moved += abs(drag.x) + abs(drag.y)
                                CallController.moveBubble(bubble.x + drag.x, bubble.y + drag.y)
                            },
                            onDragEnd = { if (moved < 12f) CallController.expand() },
                        )
                    },
                )
            } else {
                Box(
                    Modifier.fillMaxSize().pointerInput(Unit) {
                        var moved = 0f
                        detectDragGestures(
                            onDragStart = { moved = 0f },
                            onDrag = { change, drag ->
                                change.consume()
                                moved += abs(drag.x) + abs(drag.y)
                                CallController.moveBubble(bubble.x + drag.x, bubble.y + drag.y)
                            },
                            onDragEnd = { if (moved < 12f) CallController.expand() },
                        )
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(call.peer.ifEmpty { "A" }.take(1).uppercase(), color = Color.White, fontSize = 42.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0x99000000)),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PressIconButton(onClick = CallController::toggleMute, modifier = Modifier.size(44.dp)) {
                    Icon(if (call.muted) Icons.Default.MicOff else Icons.Default.Mic, contentDescription = "Mute", tint = Color.White)
                }
                PressIconButton(onClick = { CallController.hangUp(context) }, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Default.CallEnd, contentDescription = "End", tint = Color(0xFFE53935))
                }
                IconButton(onClick = { CallController.expand() }, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Default.OpenInFull, contentDescription = "Maximize", tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun AudioMenu(
    open: Boolean,
    devices: List<com.aird.call.DeviceChoice>,
    onDismiss: () -> Unit,
    onSelect: (com.aird.call.DeviceChoice) -> Unit,
) {
    DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("Audio Output", fontWeight = FontWeight.Bold) }, onClick = {}, enabled = false)
        devices.forEach { device ->
            val icon = when {
                device.label.contains("Bluetooth", true) -> "🎧 "
                device.label.contains("Speaker", true) -> "🔊 "
                device.label.contains("Earpiece", true) -> "👂 "
                else -> "🎧 "
            }
            DropdownMenuItem(
                text = { Text("$icon${device.label}") },
                onClick = { onSelect(device) },
            )
        }
    }
}

@Composable
private fun DeviceMenu(open: Boolean, devices: List<com.aird.call.DeviceChoice>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
        if (devices.isEmpty()) {
            DropdownMenuItem(text = { Text("None") }, onClick = { onPick("") })
        }
        devices.forEach { device ->
            DropdownMenuItem(text = { Text(device.label) }, onClick = { onPick(device.id) })
        }
    }
}

@Composable
private fun CallButton(color: Color, icon: ImageVector, label: String, onClick: () -> Unit) {
    PressIconButton(onClick = onClick, modifier = Modifier.size(48.dp).clip(CircleShape).background(color)) {
        Icon(icon, contentDescription = label, tint = Color.White)
    }
}

@Composable
private fun FadingStatus(text: String, modifier: Modifier = Modifier, color: Color, fontSize: TextUnit = TextUnit.Unspecified) {
    val reduced = LocalAirdReducedMotion.current
    val outcome = text == "Connected" || text == "Call declined" || text == "Call ended"
    var shown by remember { mutableStateOf(if (reduced || !outcome) text else "") }
    LaunchedEffect(text) { shown = text }
    AnimatedContent(
        targetState = shown,
        modifier = modifier,
        transitionSpec = {
            if (reduced) {
                ContentTransform(EnterTransition.None, ExitTransition.None, sizeTransform = null)
            } else {
                ContentTransform(fadeIn(tween(200)), fadeOut(tween(180)), sizeTransform = null)
            }
        },
        label = "callStatus",
    ) { value ->
        if (value.isNotEmpty()) Text(value, color = color, fontSize = fontSize)
    }
}

@Composable
private fun VideoSurface(track: VideoTrack, modifier: Modifier) {
    val room = CallController.room()
    val bound = remember { arrayOfNulls<VideoTrack>(1) }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            TextureViewRenderer(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                room?.initVideoRenderer(this)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            }
        },
        update = { view ->
            val previous = bound[0]
            if (previous != track) {
                previous?.removeRenderer(view)
                track.addRenderer(view)
                bound[0] = track
            }
        },
        onRelease = { view ->
            bound[0]?.removeRenderer(view)
            bound[0] = null
            try {
                view.clearImage()
                view.release()
            } catch (_: Exception) {
            }
        },
    )
}
