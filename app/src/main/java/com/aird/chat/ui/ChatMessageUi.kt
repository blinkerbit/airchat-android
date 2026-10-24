package com.aird.chat.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.webkit.MimeTypeMap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import androidx.media3.common.MediaItem
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.aird.chat.model.ChatAttachment
import com.aird.chat.model.ChatMessage
import com.aird.session.AirdHttp
import com.aird.ui.LocalAirdReducedMotion
import com.aird.ui.enterRiseModifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

private val Reactions = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")
private val Stickers = setOf("😂", "❤️", "🔥", "👍", "🎉", "😍", "🙏", "😎", "🥳", "💯", "✨", "🚀", "🎂", "🌟", "🤝", "💐", "☕", "🎵", "🌈", "📸", "💬", "💪", "🤩", "🤣")

private object ChatImages {
    @Volatile private var loader: ImageLoader? = null
    @Volatile private var staticLoader: ImageLoader? = null

    fun loader(context: Context): ImageLoader {
        loader?.let { return it }
        return synchronized(this) {
            loader ?: ImageLoader.Builder(context.applicationContext)
                .okHttpClient(AirdHttp.client)
                .components {
                    add(GifDecoder.Factory())
                    if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory())
                }
                .build()
                .also { loader = it }
        }
    }

    fun staticLoader(context: Context): ImageLoader {
        staticLoader?.let { return it }
        return synchronized(this) {
            staticLoader ?: ImageLoader.Builder(context.applicationContext)
                .okHttpClient(AirdHttp.client)
                .build()
                .also { staticLoader = it }
        }
    }
}

private fun ChatMessage.sticker(): Boolean {
    val text = body.trim()
    if (text.isEmpty() || text.length > 8 || attachments.isNotEmpty()) return false
    return text in Stickers || text.none { it.isLetterOrDigit() }
}

@Composable
fun MessageRow(
    message: ChatMessage,
    quoteMessage: ChatMessage?,
    quoteText: String,
    seen: String,
    highlighted: Boolean = false,
    onQuoteClick: (String) -> Unit = {},
    onReply: () -> Unit,
    onForward: () -> Unit,
    onEdit: (String) -> Unit,
    onDelete: () -> Unit,
    onReact: (String) -> Unit,
    onPin: () -> Unit,
    pinned: Boolean,
    enter: Boolean = false,
) {
    val context = LocalContext.current
    val reduced = LocalAirdReducedMotion.current
    val scope = rememberCoroutineScope()
    var dragX by remember(message.id) { mutableFloatStateOf(0f) }
    var settling by remember(message.id) { mutableStateOf(false) }
    var gesture by remember(message.id) { mutableIntStateOf(0) }
    val settle = remember(message.id) { Animatable(0f) }
    var menu by remember(message.id) { mutableStateOf(false) }
    var editing by remember(message.id) { mutableStateOf(false) }
    var editText by remember(message.id) { mutableStateOf(message.body) }
    var confirmDelete by remember(message.id) { mutableStateOf(false) }
    var viewer by remember(message.id) { mutableStateOf<ChatAttachment?>(null) }
    val mine = message.mine
    val shown = if (settling) settle.value else dragX
    Row(
        Modifier.fillMaxWidth()
            .then(enterRiseModifier(message.id, enter))
            .offset { IntOffset(shown.roundToInt(), 0) }
            .pointerInput(message.id, reduced) {
                fun easeBack() {
                    val from = dragX
                    val token = gesture
                    if (reduced) {
                        dragX = 0f
                        settling = false
                        return
                    }
                    scope.launch {
                        settle.snapTo(from)
                        if (token != gesture) return@launch
                        settling = true
                        try {
                            settle.animateTo(0f, tween(220, easing = FastOutSlowInEasing))
                        } finally {
                            if (token == gesture) {
                                dragX = 0f
                                settling = false
                            }
                        }
                    }
                }
                detectHorizontalDragGestures(
                    onDragStart = {
                        gesture += 1
                        dragX = if (settling) settle.value else dragX
                        settling = false
                        scope.launch { settle.stop() }
                    },
                    onHorizontalDrag = { _, drag -> dragX = (dragX + drag).coerceIn(-180f, 180f) },
                    onDragEnd = {
                        val trigger = kotlin.math.abs(dragX) > 72.dp.toPx()
                        easeBack()
                        if (trigger) onReply()
                    },
                    onDragCancel = { easeBack() },
                )
            },
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
            if (!mine) {
                Text(message.sender, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 2.dp))
            }
            Box {
                if (message.sticker()) {
                    Text(
                        message.body.trim(),
                        fontSize = 52.sp,
                        modifier = Modifier.pointerInput(message.id) { detectTapGestures(onLongPress = { menu = true }) },
                    )
                } else {
                    Bubble(
                        message = message,
                        quoteMessage = quoteMessage,
                        quoteText = quoteText,
                        mine = mine,
                        highlighted = highlighted,
                        onQuoteClick = onQuoteClick,
                        onLongPress = { menu = true },
                        onOpen = { viewer = it },
                        onFile = { attachment ->
                            openAttachment(context, attachment)
                        },
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    Row(Modifier.padding(horizontal = 8.dp)) {
                        Reactions.forEach { emoji ->
                            Text(
                                emoji,
                                fontSize = 22.sp,
                                modifier = Modifier.clickable {
                                    menu = false
                                    onReact(emoji)
                                }.padding(6.dp),
                            )
                        }
                    }
                    DropdownMenuItem(text = { Text("Copy") }, onClick = {
                        menu = false
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard.setPrimaryClip(ClipData.newPlainText("message", message.body))
                    })
                    DropdownMenuItem(text = { Text("Reply") }, onClick = { menu = false; onReply() })
                    DropdownMenuItem(text = { Text("Forward") }, onClick = { menu = false; onForward() })
                    DropdownMenuItem(text = { Text("Edit") }, onClick = {
                        menu = false
                        if (message.mine) editing = true else onEdit(message.body.ifBlank { "x" })
                    })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = {
                        menu = false
                        if (message.mine) confirmDelete = true else onDelete()
                    })
                    DropdownMenuItem(text = { Text(if (pinned) "Unpin" else "Pin") }, onClick = { menu = false; onPin() })
                }
            }
            if (message.reactions.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 2.dp)) {
                    message.reactions.forEach { reaction ->
                        Text(
                            "${reaction.emoji} ${reaction.users.size}",
                            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable { onReact(reaction.emoji) }.padding(horizontal = 6.dp, vertical = 2.dp),
                            fontSize = 12.sp,
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (message.clock().isNotEmpty()) {
                    Text(message.clock(), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                }
                if (message.edited) {
                    Text(" edited", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                }
                if (message.pending) {
                    Text(" · sending", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                }
            }
            if (seen.isNotEmpty()) {
                Text(seen, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
    if (editing) {
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Edit") },
            text = { OutlinedTextField(value = editText, onValueChange = { editText = it }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(onClick = {
                    editing = false
                    onEdit(editText)
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete message") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
    viewer?.let { attachment ->
        MediaViewer(attachment, onDismiss = { viewer = null })
    }
}

@Composable
private fun Bubble(
    message: ChatMessage,
    quoteMessage: ChatMessage?,
    quoteText: String,
    mine: Boolean,
    highlighted: Boolean,
    onQuoteClick: (String) -> Unit,
    onLongPress: () -> Unit,
    onOpen: (ChatAttachment) -> Unit,
    onFile: (ChatAttachment) -> Unit,
) {
    val fg = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val defaultBg = if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val targetBg = if (highlighted) MaterialTheme.colorScheme.tertiaryContainer else defaultBg
    val animatedBg by animateColorAsState(
        targetValue = targetBg,
        animationSpec = tween(500),
        label = "bubbleHighlight",
    )

    Column(
        Modifier.widthIn(max = 300.dp)
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = if (mine) 16.dp else 4.dp, bottomEnd = if (mine) 4.dp else 16.dp))
            .background(animatedBg)
            .pointerInput(message.id) { detectTapGestures(onLongPress = { onLongPress() }) }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        if (message.forwarded) Text("Forwarded", color = fg.copy(alpha = 0.7f), fontSize = 12.sp)

        val replyId = message.replyTargetId()
        if (replyId.isNotEmpty()) {
            val label = when {
                quoteMessage != null -> quoteMessage.previewLabel()
                quoteText.isNotBlank() -> quoteText
                else -> "Original message"
            }
            QuotedMessagePreview(
                sender = quoteMessage?.sender.orEmpty(),
                body = label,
                mine = mine,
                onClick = { onQuoteClick(replyId) },
            )
        }

        message.attachments.forEach { attachment ->
            val gestures = Modifier.pointerInput(message.id, attachment.url) {
                detectTapGestures(
                    onLongPress = { onLongPress() },
                    onTap = {
                        if (attachment.visual() || attachment.video()) onOpen(attachment) else onFile(attachment)
                    },
                )
            }
            if (attachment.visual()) {
                AttachmentImage(
                    attachment,
                    Modifier.fillMaxWidth().heightIn(max = 240.dp).clip(RoundedCornerShape(8.dp)).then(gestures),
                )
            } else {
                Text(
                    attachment.name,
                    color = fg,
                    fontWeight = FontWeight.SemiBold,
                    modifier = gestures.padding(vertical = 4.dp),
                )
            }
        }
        if (message.body.isNotBlank() && message.body != "Encrypted message" || message.attachments.isEmpty()) {
            if (message.body.isNotBlank()) Text(highlight(message.body, fg), color = fg, fontSize = 16.sp)
        }
    }
}

@Composable
private fun QuotedMessagePreview(
    sender: String,
    body: String,
    mine: Boolean,
    onClick: () -> Unit,
) {
    val barColor = if (mine) Color.White.copy(alpha = 0.9f) else MaterialTheme.colorScheme.primary
    val boxBg = if (mine) Color.Black.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
    val textColor = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface

    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(boxBg)
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(30.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(barColor),
        )
        Column(Modifier.padding(start = 8.dp).weight(1f)) {
            if (sender.isNotEmpty()) {
                Text(
                    sender,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = textColor.copy(alpha = 0.9f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                body.ifEmpty { "Original message" },
                fontSize = 12.sp,
                color = textColor.copy(alpha = 0.8f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AttachmentImage(attachment: ChatAttachment, modifier: Modifier) {
    val context = LocalContext.current
    val isGif = attachment.mediaKind == "gif" || attachment.url.endsWith(".gif", true) || attachment.name.endsWith(".gif", true)
    var isPlaying by remember(attachment.url) { mutableStateOf(false) }

    val url = AirdHttp.absolute(context, attachment.url)
    Box(modifier = modifier) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(url)
                .memoryCacheKey("$url#${if (isGif && isPlaying) "play" else "still"}")
                .allowHardware(!(isGif && isPlaying))
                .apply { if (isGif && isPlaying) decoderFactory(GifDecoder.Factory()) }
                .crossfade(!(isGif && isPlaying))
                .build(),
            imageLoader = if (isGif && isPlaying) ChatImages.loader(context) else ChatImages.staticLoader(context),
            contentDescription = attachment.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        if (isGif) {
            val control = Modifier
                .clip(CircleShape)
                .clickable { isPlaying = !isPlaying }
                .background(Color.Black.copy(alpha = 0.65f))
                .padding(horizontal = 14.dp, vertical = 8.dp)
            Box(
                Modifier.align(if (isPlaying) Alignment.TopEnd else Alignment.Center).padding(8.dp).then(control),
            ) {
                Text(
                    if (isPlaying) "Pause" else "Play GIF",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun MediaViewer(attachment: ChatAttachment, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (attachment.video()) {
                VideoPlayer(attachment)
            } else {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(AirdHttp.absolute(context, attachment.url)).build(),
                    imageLoader = ChatImages.loader(context),
                    contentDescription = attachment.name,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = panX
                        translationY = panY
                    }.pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            panX += pan.x
                            panY += pan.y
                        }
                    },
                    contentScale = ContentScale.Fit,
                )
            }
            Row(Modifier.align(Alignment.TopEnd).padding(12.dp)) {
                Text("Open", color = Color.White, modifier = Modifier.clickable { openAttachment(context, attachment) }.padding(12.dp))
                Text("Close", color = Color.White, modifier = Modifier.clickable(onClick = onDismiss).padding(12.dp))
            }
        }
    }
}

@Composable
private fun VideoPlayer(attachment: ChatAttachment) {
    val context = LocalContext.current
    val player = remember(attachment.url) {
        val source = OkHttpDataSource.Factory(AirdHttp.client)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(source))
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(AirdHttp.absolute(context, attachment.url)))
                prepare()
                playWhenReady = true
            }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    AndroidView(
        factory = { viewContext ->
            PlayerView(viewContext).apply {
                this.player = player
                useController = true
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

private fun highlight(text: String, color: Color) = buildAnnotatedString {
    val regex = Regex("@([A-Za-z0-9_.\\-@]+)")
    var index = 0
    regex.findAll(text).forEach { match ->
        append(text.substring(index, match.range.first))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = color)) { append(match.value) }
        index = match.range.last + 1
    }
    if (index < text.length) append(text.substring(index))
}

private fun openAttachment(context: Context, attachment: ChatAttachment) {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val dir = File(context.cacheDir, "chat-files").apply { mkdirs() }
            val dest = File(dir, safeFile(attachment.name))
            val header = AirdHttp.saveTo(context, attachment.url, dest)
            val mime = header.ifBlank { guessMime(dest.name, attachment.mediaKind) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", dest)
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            withContext(Dispatchers.Main) { context.startActivity(view) }
        } catch (_: Exception) {
        }
    }
}

private fun safeFile(name: String): String {
    val cleaned = name.substringAfterLast('/').filter { it.isLetterOrDigit() || it in "._-" }.take(80)
    return cleaned.ifBlank { "file" }
}

private fun guessMime(name: String, kind: String): String {
    if (kind == "gif" || name.endsWith(".gif", true)) return "image/gif"
    if (kind == "image") return "image/*"
    val ext = name.substringAfterLast('.', "")
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase()).orEmpty().ifBlank { "application/octet-stream" }
}
