package com.aird.chat.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.inputmethod.EditorInfo
import android.graphics.Rect
import android.widget.EditText
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.widget.doAfterTextChanged
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.aird.chat.data.ChatRepository
import com.aird.chat.data.CollabRepository
import com.aird.session.Session
import com.aird.ui.LocalAirdReducedMotion
import com.aird.ui.PressIconButton
import java.io.File
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Composer(
    draft: String,
    onDraft: (String) -> Unit,
    panel: String,
    onPanel: (String) -> Unit,
    reply: String,
    onClearReply: () -> Unit,
    uploading: Boolean,
    onPhotos: () -> Unit,
    onTakePhoto: () -> Unit,
    onRecordVideo: () -> Unit,
    onPickDevice: () -> Unit,
    onPickAird: () -> Unit,
    onSend: () -> Unit,
    onContent: (Uri, String, Boolean) -> Unit,
    members: List<String> = emptyList(),
) {
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val hintColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f).toArgb()
    val reduced = LocalAirdReducedMotion.current
    val panelEnter = if (reduced) EnterTransition.None else fadeIn(tween(200)) + expandVertically(tween(220))
    val panelExit = if (reduced) ExitTransition.None else fadeOut(tween(180)) + shrinkVertically(tween(200))
    var shownReply by remember { mutableStateOf(reply) }
    if (reply.isNotEmpty()) shownReply = reply

    // Enhancement #5: pending image state
    var pendingImage by remember { mutableStateOf<Pair<Uri, String>?>(null) }
    var sendingName by remember { mutableStateOf("") }
    var uploadSeen by remember { mutableStateOf(false) }

    // Enhancement #11: voice recording state
    val context = LocalContext.current
    val recorderState = remember { mutableStateOf<MediaRecorder?>(null) }
    var recorder by recorderState
    val audioFileState = remember { mutableStateOf<File?>(null) }
    var audioFile by audioFileState
    var recording by remember { mutableStateOf(false) }
    var elapsed by remember { mutableIntStateOf(0) }
    var recordStartedAt by remember { mutableLongStateOf(0L) }
    var pendingVoice by remember { mutableStateOf<File?>(null) }
    var voiceMs by remember { mutableIntStateOf(0) }
    var playMs by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    val playerState = remember { mutableStateOf<MediaPlayer?>(null) }
    var player by playerState
    var cursor by remember { mutableIntStateOf(draft.length) }
    var caretRequest by remember { mutableStateOf<Pair<String, Int>?>(null) }
    val draftSink = rememberUpdatedState(onDraft)
    val draftState = rememberUpdatedState(draft)
    val sendSink = rememberUpdatedState(onSend)
    val conversations by ChatRepository.conversations.collectAsState()
    val activeId by ChatRepository.activeId.collectAsState()
    val me by Session.username.collectAsState()

    fun beginRecording() {
        if (recording) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        audioFile = file
        var created: MediaRecorder? = null
        try {
            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION") MediaRecorder()
            }
            created = mr
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setOutputFile(file.absolutePath)
            mr.prepare()
            mr.start()
            recorder = mr
            recordStartedAt = SystemClock.elapsedRealtime()
            elapsed = 0
            recording = true
            if (panel.isNotEmpty()) onPanel("")
        } catch (_: Exception) {
            runCatching { created?.release() }
            recorder = null
            recording = false
            audioFile = null
            runCatching { if (file.exists()) file.delete() }
        }
    }

    fun stopPlayback() {
        val mp = playerState.value
        playerState.value = null
        playing = false
        playMs = 0
        if (mp != null) {
            runCatching { mp.stop() }
            runCatching { mp.release() }
        }
    }

    fun discardVoice() {
        stopPlayback()
        val file = pendingVoice ?: audioFile
        pendingVoice = null
        audioFile = null
        voiceMs = 0
        elapsed = 0
        runCatching { file?.delete() }
    }

    fun cancelRecording() {
        val mr = recorder
        recorder = null
        recording = false
        if (mr != null) {
            runCatching { mr.stop() }
            runCatching { mr.release() }
        }
        discardVoice()
    }

    fun finishRecording() {
        val mr = recorder
        val file = audioFile
        val taken = elapsed
        recorder = null
        recording = false
        if (mr != null) {
            runCatching { mr.stop() }
            runCatching { mr.release() }
        }
        if (file != null && file.exists() && file.length() > 0L) {
            pendingVoice = file
            voiceMs = (taken.coerceAtLeast(1) * 1000)
            playMs = 0
        } else {
            discardVoice()
        }
    }

    fun toggleVoicePlayback() {
        val file = pendingVoice ?: return
        val existing = playerState.value
        if (existing != null) {
            if (playing) {
                existing.pause()
                playing = false
            } else {
                existing.start()
                playing = true
            }
            return
        }
        val mp = MediaPlayer()
        try {
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener {
                playing = false
                playMs = 0
                runCatching { it.seekTo(0) }
            }
            mp.setOnPreparedListener {
                val duration = it.duration
                if (duration > 0) voiceMs = duration
                it.start()
                playing = true
            }
            player = mp
            mp.prepareAsync()
        } catch (_: Exception) {
            runCatching { mp.release() }
            playing = false
        }
    }

    fun sendVoice() {
        val file = pendingVoice ?: return
        stopPlayback()
        pendingVoice = null
        audioFile = null
        sendingName = file.name
        onContent(Uri.fromFile(file), "audio/mp4", true)
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) beginRecording()
    }

    LaunchedEffect(uploading) {
        if (uploading) uploadSeen = true
        else if (uploadSeen) {
            sendingName = ""
            uploadSeen = false
        }
    }
    LaunchedEffect(recording, recordStartedAt) {
        if (!recording) return@LaunchedEffect
        while (true) {
            elapsed = ((SystemClock.elapsedRealtime() - recordStartedAt) / 1000L).toInt().coerceAtLeast(0)
            delay(250)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            val mr = recorderState.value
            val file = audioFileState.value
            val mp = playerState.value
            recorderState.value = null
            audioFileState.value = null
            playerState.value = null
            if (mr != null) {
                runCatching { mr.stop() }
                runCatching { mr.release() }
            }
            if (mp != null) {
                runCatching { mp.stop() }
                runCatching { mp.release() }
            }
            if (file != null) runCatching { file.delete() }
        }
    }
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        while (true) {
            playMs = playerState.value?.currentPosition ?: playMs
            delay(200)
        }
    }
    var voiceChat by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(activeId) {
        if (voiceChat == null) {
            voiceChat = activeId
            return@LaunchedEffect
        }
        if (voiceChat != activeId) {
            voiceChat = activeId
            cancelRecording()
        }
    }

    val active = conversations.firstOrNull { it.id == activeId }
    val memberPool = when {
        active?.kind == "group" -> if (members.isNotEmpty()) members else active.members.map { it.username }
        active == null && members.isNotEmpty() -> members
        else -> emptyList()
    }
    val mention = mentionAtCursor(draft, cursor)
    val suggestions = if (mention == null) {
        emptyList()
    } else {
        val token = mention.second
        memberPool.map { it.trim() }
            .filter { name ->
                name.isNotEmpty() && !name.equals(me, true) &&
                    (token.isEmpty() || name.startsWith(token, ignoreCase = true))
            }
            .distinct()
            .take(6)
    }
    fun insertMention(username: String) {
        val clean = username.trim()
        if (clean.isEmpty() || clean.any { it.isWhitespace() }) return
        val found = mentionAtCursor(draft, cursor) ?: return
        val at = found.first
        val end = cursor.coerceIn(at, draft.length)
        val insert = "@$clean "
        val next = draft.substring(0, at) + insert + draft.substring(end)
        caretRequest = next to (at + insert.length)
        onDraft(next)
    }

    val keyboardController = LocalSoftwareKeyboardController.current
    val panelState = rememberUpdatedState(panel)
    val panelSink = rememberUpdatedState(onPanel)
    val keyboardShow = rememberUpdatedState(keyboardController)
    val handingOff = remember { mutableStateOf(false) }
    val fieldSlot = remember { FieldSlot() }
    fun openChrome(name: String) {
        val field = fieldSlot.field
        handingOff.value = field?.hasFocus() == true
        field?.clearFocus()
        if (field?.hasFocus() != true) handingOff.value = false
        keyboardController?.hide()
        onPanel(name)
    }

    BackHandler(enabled = panel == "emoji" || panel == "plus") {
        onPanel("")
    }

    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        AnimatedVisibility(visible = reply.isNotEmpty(), enter = panelEnter, exit = panelExit) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(shownReply, modifier = Modifier.weight(1f), maxLines = 1, color = MaterialTheme.colorScheme.primary)
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Dismiss reply",
                    modifier = Modifier
                        .clickable(enabled = reply.isNotEmpty(), onClick = onClearReply)
                        .padding(8.dp)
                        .size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
        }
        if (uploading) {
            val sendingLabel = if (sendingName.isNotBlank()) "Sending $sendingName…" else "Sending file…"
            Text(
                sendingLabel,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
        }

        // Enhancement #5: image paste preview row
        val pending = pendingImage
        if (pending != null) {
            val imageName = attachmentName(context, pending.first)
            val imageLabel = if (imageName.isNotBlank()) "Image attachment · $imageName" else "Image attachment"
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(pending.first)
                        .build(),
                    contentDescription = "Image preview",
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
                Text(
                    imageLabel,
                    modifier = Modifier
                        .padding(start = 10.dp)
                        .weight(1f),
                    fontSize = 13.sp,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                TextButton(onClick = {
                    val name = attachmentName(context, pending.first)
                    if (name.isNotBlank()) sendingName = name
                    onContent(pending.first, pending.second, false)
                    pendingImage = null
                }) {
                    Text("Send")
                }
                Text(
                    "✕",
                    modifier = Modifier
                        .clickable { pendingImage = null }
                        .padding(8.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }

        if (suggestions.isNotEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)),
            ) {
                suggestions.forEach { name ->
                    Text(
                        "@$name",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { insertMention(name) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 14.sp,
                        maxLines = 1,
                    )
                }
            }
        }

        val refs by CollabRepository.refs.collectAsState()
        val shelfFor by CollabRepository.shelfFor.collectAsState()
        val openShelf = refs.filter {
            (it.kind == "list" || it.kind == "poll") && it.active && shelfFor == ChatRepository.activeId.value
        }
        AnimatedVisibility(
            visible = openShelf.isNotEmpty(),
            enter = if (reduced) fadeIn(tween(80)) else fadeIn(tween(180)) + expandVertically(tween(200)),
            exit = if (reduced) fadeOut(tween(80)) else fadeOut(tween(140)) + shrinkVertically(tween(180)),
        ) {
            CollabShelf(openShelf)
        }

        val canSend = draft.isNotBlank() && !uploading
        val voiceMode = when {
            recording -> 1
            pendingVoice != null -> 2
            else -> 0
        }
        val pill = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(26.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(26.dp))
            .padding(horizontal = 4.dp, vertical = 3.dp)
        AnimatedContent(
            targetState = voiceMode,
            modifier = Modifier.fillMaxWidth(),
            transitionSpec = {
                if (reduced) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    (fadeIn(tween(180)) + slideInHorizontally { it / 6 }) togetherWith
                        (fadeOut(tween(140)) + slideOutHorizontally { -it / 8 })
                }
            },
            label = "voice-composer",
        ) { mode ->
        if (mode == 1) {
            Row(pill, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { cancelRecording() }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Discard recording", tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                }
                Box(
                    modifier = Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error)
                )
                Text(
                    formatElapsed(elapsed),
                    modifier = Modifier.padding(start = 8.dp).weight(1f),
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text("Recording", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f), fontSize = 13.sp)
                IconButton(onClick = { finishRecording() }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop recording", tint = MaterialTheme.colorScheme.error)
                }
            }
        } else if (mode == 2) {
            val fraction = if (voiceMs <= 0) 0f else (playMs.toFloat() / voiceMs.toFloat()).coerceIn(0f, 1f)
            Row(pill, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { discardVoice() }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Discard voice message", tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                }
                IconButton(onClick = { toggleVoicePlayback() }, modifier = Modifier.size(40.dp)) {
                    Icon(
                        if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (playing) "Pause" else "Play voice message",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Column(Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.Center) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                    )
                    Text(
                        "${formatElapsed(playMs / 1000)} / ${formatElapsed(voiceMs / 1000)}",
                        modifier = Modifier.padding(top = 4.dp),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    )
                }
                Box(
                    modifier = Modifier.padding(end = 2.dp).size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    PressIconButton(onClick = { sendVoice() }, modifier = Modifier.size(36.dp), enabled = !uploading) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send voice message", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
                    }
                }
            }
        } else Row(
            pill,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    if (panel == "plus") onPanel("") else openChrome("plus")
                },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    if (panel == "plus") Icons.Default.Close else Icons.Default.Add,
                    contentDescription = if (panel == "plus") "Close" else "More",
                )
            }
            IconButton(
                onClick = {
                    if (panel == "emoji") {
                        onPanel("")
                        fieldSlot.field?.requestFocus()
                        keyboardController?.show()
                    } else {
                        openChrome("emoji")
                    }
                },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    if (panel == "emoji") Icons.Default.Keyboard else Icons.Default.EmojiEmotions,
                    contentDescription = if (panel == "emoji") "Show keyboard" else "Show emoji"
                )
            }
            AndroidView(
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                factory = { ctx ->
                    ComposerField(ctx).apply {
                        fieldSlot.field = this
                        background = null
                        hint = "Message"
                        textSize = 16f
                        minLines = 1
                        maxLines = 5
                        imeOptions = EditorInfo.IME_ACTION_SEND
                        fitsSystemWindows = false
                        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets -> insets }
                        setOnEditorActionListener { _, actionId, _ ->
                            if (actionId == EditorInfo.IME_ACTION_SEND) {
                                sendSink.value()
                                true
                            } else {
                                false
                            }
                        }
                        setOnClickListener {
                            if (panelState.value == "emoji" || panelState.value == "plus") {
                                panelSink.value("")
                                keyboardShow.value?.show()
                            }
                        }
                        setOnFocusChangeListener { _, hasFocus ->
                            if (handingOff.value) {
                                handingOff.value = false
                                return@setOnFocusChangeListener
                            }
                            if (hasFocus && (panelState.value == "emoji" || panelState.value == "plus")) {
                                panelSink.value("")
                            }
                        }
                        onCursor = { pos -> post { cursor = pos } }
                        doAfterTextChanged { editable ->
                            val value = editable?.toString().orEmpty()
                            if (value != draftState.value) {
                                cursor = this@apply.selectionStart.coerceIn(0, value.length)
                                draftSink.value(value)
                            }
                        }
                        // Enhancement #5: set pendingImage instead of calling onContent directly
                        ViewCompat.setOnReceiveContentListener(this, arrayOf("image/*", "image/gif")) { _, payload ->
                            val clip = payload.clip
                            for (i in 0 until clip.itemCount) {
                                val uri = clip.getItemAt(i).uri ?: continue
                                val mime = runCatching { clip.description.getMimeType(i) }.getOrNull()
                                    ?: ctx.contentResolver.getType(uri)
                                    ?: "image/*"
                                if (mime.startsWith("image/")) pendingImage = Pair(uri, mime)
                            }
                            null
                        }
                    }
                },
                update = { edit ->
                    edit.setTextColor(textColor)
                    edit.setHintTextColor(hintColor)
                    val request = caretRequest
                    val desired = when {
                        request != null && request.first == draft -> request.second.coerceIn(0, draft.length)
                        edit.text.toString() != draft -> draft.length
                        else -> null
                    }
                    if (edit.text.toString() != draft) edit.setText(draft)
                    if (desired != null && (edit.selectionStart != desired || edit.selectionEnd != desired)) {
                        edit.setSelection(desired)
                    }
                    if (request != null && request.first == draft) {
                        edit.post { if (caretRequest == request) caretRequest = null }
                    }
                },
            )
            if (canSend) {
                Box(
                    modifier = Modifier
                        .padding(end = 2.dp)
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    PressIconButton(onClick = onSend, modifier = Modifier.size(36.dp), enabled = canSend) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            } else {
                IconButton(
                    onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            beginRecording()
                        }
                    },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        Icons.Default.Mic,
                        contentDescription = "Voice message",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
        }
        }

        val density = LocalDensity.current
        val imePx = WindowInsets.ime.getBottom(density)
        var keyboardPx by remember { mutableIntStateOf(0) }
        if (imePx > 0) keyboardPx = imePx
        val emojiHeight = with(density) {
            val fallback = 320.dp.roundToPx()
            val chosen = if (keyboardPx > 0) keyboardPx else fallback
            chosen.coerceIn(280.dp.roundToPx(), 400.dp.roundToPx()).toDp()
        }
        val chromeEnter = if (reduced) EnterTransition.None else expandVertically(tween(160, easing = FastOutSlowInEasing)) + fadeIn(tween(140))
        val chromeExit = if (reduced) ExitTransition.None else shrinkVertically(tween(160, easing = FastOutSlowInEasing)) + fadeOut(tween(120))
        Column(Modifier.fillMaxWidth()) {
        AnimatedVisibility(
            visible = panel == "plus" && voiceMode == 0,
            enter = chromeEnter,
            exit = chromeExit,
        ) {
            PlusPanel(
                onClose = { onPanel("") },
                onPhotos = onPhotos,
                onTakePhoto = onTakePhoto,
                onRecordVideo = onRecordVideo,
                onPickDevice = onPickDevice,
                onPickAird = onPickAird,
                onToken = { token ->
                    val base = draftState.value.trimEnd()
                    val next = if (base.isEmpty()) "$token " else "$base $token "
                    caretRequest = next to next.length
                    draftSink.value(next)
                },
            )
        }
        AnimatedVisibility(
            visible = panel == "emoji" && voiceMode == 0 && imePx == 0,
            enter = chromeEnter,
            exit = chromeExit,
        ) {
            EmojiPanel(
                modifier = Modifier.fillMaxWidth().height(emojiHeight),
                showBackspace = true,
                onBackspace = {
                    val (next, caret) = deleteBeforeCursor(draftState.value, cursor)
                    caretRequest = next to caret
                    draftSink.value(next)
                },
                onPick = { emoji ->
                    val (next, caret) = insertAtCursor(draftState.value, cursor, emoji)
                    caretRequest = next to caret
                    draftSink.value(next)
                },
            )
        }
        }
    }
}

private class FieldSlot {
    var field: ComposerField? = null
}

private class ComposerField(context: Context) : EditText(context) {
    var onCursor: ((Int) -> Unit)? = null

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        if (selStart >= 0) onCursor?.invoke(selStart)
    }

    override fun bringPointIntoView(offset: Int): Boolean = true

    override fun requestRectangleOnScreen(rectangle: Rect?, immediate: Boolean): Boolean = true
}

private fun mentionAtCursor(text: String, cursor: Int): Pair<Int, String>? {
    if (cursor <= 0 || text.isEmpty()) return null
    val end = cursor.coerceAtMost(text.length)
    var index = end - 1
    while (index >= 0) {
        val char = text[index]
        if (char == '@') {
            if (index > 0 && !text[index - 1].isWhitespace()) return null
            val token = text.substring(index + 1, end)
            val valid = token.all { it.isLetterOrDigit() || it == '_' || it == '.' || it == '-' || it == '@' }
            if (!valid) return null
            return index to token
        }
        if (char.isWhitespace()) return null
        index -= 1
    }
    return null
}

private fun attachmentName(context: Context, uri: Uri): String {
    val queried = if (uri.scheme.equals("file", true)) {
        ""
    } else {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) "" else cursor.getString(0).orEmpty()
            }
        }.getOrNull().orEmpty()
    }
    val raw = queried.ifBlank { uri.lastPathSegment.orEmpty() }
    return raw.substringAfterLast('/').substringAfterLast('\\').replace("\r", " ").replace("\n", " ").trim()
}

private fun formatElapsed(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    return "%d:%02d".format(safe / 60, safe % 60)
}
