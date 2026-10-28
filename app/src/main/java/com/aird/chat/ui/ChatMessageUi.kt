package com.aird.chat.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.ClickableText
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import com.aird.chat.model.ReadReceipt
import com.aird.chat.model.editAllowed
import java.time.Instant
import com.aird.session.AirdHttp
import com.aird.ui.LocalAirdReducedMotion
import com.aird.ui.enterRiseModifier
import kotlinx.coroutines.launch
import kotlin.math.abs
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageRow(
    message: ChatMessage,
    quoteMessage: ChatMessage?,
    quoteText: String,
    seen: String,
    receipts: List<ReadReceipt>,
    me: String,
    highlighted: Boolean = false,
    onQuoteClick: (String) -> Unit = {},
    onReply: () -> Unit,
    onForward: () -> Unit,
    onEdit: (String) -> Unit,
    onDelete: () -> Unit,
    onReact: (String) -> Unit,
    onPin: () -> Unit,
    onSave: (ChatAttachment) -> Unit,
    onRetry: () -> Unit,
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
    var nowMs by remember(message.id) { mutableStateOf(System.currentTimeMillis()) }
    androidx.compose.runtime.LaunchedEffect(menu) {
        if (!menu) return@LaunchedEffect
        while (true) {
            nowMs = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }
    val canEdit = editAllowed(message, receipts, me, Instant.ofEpochMilli(nowMs))
    var editing by remember(message.id) { mutableStateOf(false) }
    var editText by remember(message.id) { mutableStateOf(message.body) }
    var confirmDelete by remember(message.id) { mutableStateOf(false) }
    var viewer by remember(message.id) { mutableStateOf<ChatAttachment?>(null) }
    val mine = message.mine
    val shown = if (settling) settle.value else dragX
    fun easeSwipeBack() {
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
    Row(
        Modifier.fillMaxWidth().then(enterRiseModifier(message.id, enter)),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
        ) {
            if (!mine) {
                Text(message.sender, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 2.dp))
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .offset { IntOffset(shown.roundToInt(), 0) }
                    .replySwipeGestures(
                        messageId = message.id,
                        reduced = reduced,
                        failed = message.failed,
                        onLongPress = { menu = true },
                        onRetry = onRetry,
                        onDrag = { dragX = it },
                        onRelease = { offset, trigger ->
                            if (trigger) onReply()
                            dragX = offset
                            easeSwipeBack()
                        },
                        onDragStart = {
                            gesture += 1
                            dragX = if (settling) settle.value else dragX
                            settling = false
                            scope.launch { settle.stop() }
                        },
                    ),
                contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
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
                        onOpen = { viewer = it },
                        onFile = { attachment ->
                            scope.launch { downloadChatAttachment(context, message.id, attachment) }
                        },
                    )
                }
                if (menu) {
                    ModalBottomSheet(onDismissRequest = { menu = false }) {
                        Row(Modifier.padding(horizontal = 16.dp)) {
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
                        SheetAction("Reply") { menu = false; onReply() }
                        SheetAction("Copy") {
                            menu = false
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            clipboard.setPrimaryClip(ClipData.newPlainText("message", message.body))
                        }
                        SheetAction("Forward") { menu = false; onForward() }
                        if (canEdit) SheetAction("Edit") { menu = false; editing = true }
                        if (message.mine) SheetAction("Delete") { menu = false; confirmDelete = true }
                        if (message.attachments.isNotEmpty()) {
                            message.attachments.forEach { attachment ->
                                val suffix = if (message.attachments.size > 1) " ${attachment.name}" else ""
                                SheetAction("Download$suffix") {
                                    menu = false
                                    scope.launch { downloadChatAttachment(context, message.id, attachment) }
                                }
                                SheetAction("Save to device$suffix") {
                                    menu = false
                                    scope.launch { saveChatAttachmentToDevice(context, message.id, attachment) }
                                }
                                SheetAction("Open$suffix") {
                                    menu = false
                                    scope.launch { openChatAttachment(context, message.id, attachment) }
                                }
                                if (attachment.shareUrl.isNotBlank()) {
                                    SheetAction("Open in Aird") {
                                        menu = false
                                        openAirdShareInBrowser(context, attachment.shareUrl)
                                    }
                                }
                                SheetAction("Save to Aird$suffix") { menu = false; onSave(attachment) }
                            }
                        }
                        SheetAction(if (pinned) "Unpin" else "Pin") { menu = false; onPin() }
                    }
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
            }
            if (message.pending) {
                Text("Sending", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
            }
            if (message.failed) {
                Text(
                    "Not sent",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.clickable(onClick = onRetry),
                )
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
        MediaViewer(message.id, attachment, onDismiss = { viewer = null })
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
            .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = if (mine) 18.dp else 4.dp, bottomEnd = if (mine) 4.dp else 18.dp))
            .background(animatedBg)
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
                    onTap = {
                        if (attachment.visual() || attachment.video()) onOpen(attachment) else onFile(attachment)
                    },
                )
            }
            Column(Modifier.fillMaxWidth()) {
                if (attachment.visual()) {
                    AttachmentImage(
                        attachment,
                        Modifier.fillMaxWidth().heightIn(max = 240.dp).clip(RoundedCornerShape(8.dp)).then(gestures),
                    )
                } else {
                    Column(modifier = gestures.padding(vertical = 4.dp)) {
                        Text(
                            "📎 ${attachment.name}",
                            color = fg,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Tap to download",
                            color = fg.copy(alpha = 0.7f),
                            fontSize = 12.sp,
                        )
                    }
                }
                AttachmentProgressBar(message.id, attachment.index, fg)
            }
        }
        if (message.body.isNotBlank() && message.body != "Encrypted message" || message.attachments.isEmpty()) {
            if (message.body.isNotBlank()) {
                MessageBodyText(message.body, fg, mine)
            }
        }
    }
}

@Composable
private fun SheetAction(label: String, onClick: () -> Unit) {
    Text(
        label,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        fontSize = 16.sp,
    )
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
private fun AttachmentProgressBar(messageId: String, index: Int, color: Color) {
    val progress by ChatDownloads.progress.collectAsState()
    val item = progress[ChatDownloads.key(messageId, index)] ?: return
    val modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
    if (item.total > 0) {
        LinearProgressIndicator(
            progress = { (item.bytes.toFloat() / item.total.toFloat()).coerceIn(0f, 1f) },
            modifier = modifier,
            color = color,
        )
    } else {
        LinearProgressIndicator(modifier = modifier, color = color)
    }
}

@Composable
private fun MediaViewer(messageId: String, attachment: ChatAttachment, onDismiss: () -> Unit) {
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
            val scope = rememberCoroutineScope()
            Column(Modifier.align(Alignment.TopEnd).padding(12.dp)) {
                Row {
                    Text(
                        "Download",
                        color = Color.White,
                        modifier = Modifier.clickable {
                            scope.launch { downloadChatAttachment(context, messageId, attachment) }
                        }.padding(12.dp),
                    )
                    Text(
                        "Save",
                        color = Color.White,
                        modifier = Modifier.clickable {
                            scope.launch { saveChatAttachmentToDevice(context, messageId, attachment) }
                        }.padding(12.dp),
                    )
                    Text(
                        "Open",
                        color = Color.White,
                        modifier = Modifier.clickable {
                            scope.launch { openChatAttachment(context, messageId, attachment) }
                        }.padding(12.dp),
                    )
                    Text("Close", color = Color.White, modifier = Modifier.clickable(onClick = onDismiss).padding(12.dp))
                }
                AttachmentProgressBar(messageId, attachment.index, Color.White)
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

@Composable
private fun MessageBodyText(text: String, fg: Color, mine: Boolean) {
    val context = LocalContext.current
    val linkColor = if (mine) fg.copy(alpha = 0.92f) else MaterialTheme.colorScheme.primary
    val annotated = remember(text, fg, linkColor) { buildMessageText(text, fg, linkColor) }
    ClickableText(
        text = annotated,
        style = TextStyle(color = fg, fontSize = 16.sp),
        onClick = { offset ->
            annotated.getStringAnnotations("URL", offset, offset).firstOrNull()?.let { span ->
                openHttpUrl(context, span.item)
            }
        },
    )
}

private fun buildMessageText(text: String, fg: Color, linkColor: Color): AnnotatedString = buildAnnotatedString {
    val mentionRegex = Regex("@([A-Za-z0-9_.\\-@]+)")
    val urlRegex = Regex("""https?://[^\s<>"']+""")
    val hits = mutableListOf<Pair<Int, String>>()
    mentionRegex.findAll(text).forEach { hits.add(it.range.first to "m:${it.range.first}:${it.range.last + 1}") }
    urlRegex.findAll(text).forEach { m ->
        val start = m.range.first
        val end = m.range.last + 1
        val insideMention = mentionRegex.findAll(text).any { start >= it.range.first && end <= it.range.last + 1 }
        if (!insideMention) hits.add(start to "u:$start:$end")
    }
    hits.sortBy { it.first }
    var index = 0
    for ((start, token) in hits) {
        if (start < index) continue
        if (start > index) append(text.substring(index, start))
        when {
            token.startsWith("m:") -> {
                val parts = token.split(':')
                val from = parts[1].toInt()
                val to = parts[2].toInt()
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = fg)) { append(text.substring(from, to)) }
                index = to
            }
            token.startsWith("u:") -> {
                val parts = token.split(':')
                val from = parts[1].toInt()
                val to = parts[2].toInt()
                val raw = text.substring(from, to)
                val url = raw.trimEnd('.', ',', ';', ')')
                pushStringAnnotation("URL", url)
                withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) { append(raw) }
                pop()
                index = to
            }
        }
    }
    if (index < text.length) append(text.substring(index))
}

private fun Modifier.replySwipeGestures(
    messageId: String,
    reduced: Boolean,
    failed: Boolean,
    onLongPress: () -> Unit,
    onRetry: () -> Unit,
    onDrag: (Float) -> Unit,
    onRelease: (offset: Float, triggerReply: Boolean) -> Unit,
    onDragStart: () -> Unit,
): Modifier = pointerInput(messageId, reduced, failed) {
    if (reduced) return@pointerInput
    val replyThreshold = 72.dp.toPx()
    val maxDrag = 180.dp.toPx()
    val touchSlop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val pointerId = down.id
        var offsetX = 0f
        var offsetY = 0f
        var horizontal = false
        var longPressFired = false
        val startedAt = System.currentTimeMillis()
        onDragStart()
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == pointerId } ?: break
            if (!change.pressed) break
            val delta = change.positionChange()
            offsetX += delta.x
            offsetY += delta.y
            if (!horizontal && !longPressFired &&
                System.currentTimeMillis() - startedAt >= viewConfiguration.longPressTimeoutMillis &&
                abs(offsetX) < touchSlop && abs(offsetY) < touchSlop
            ) {
                longPressFired = true
                onLongPress()
            }
            if (!horizontal) {
                if (abs(offsetX) > touchSlop && abs(offsetX) > abs(offsetY) * 1.05f) {
                    horizontal = true
                }
            }
            if (horizontal) {
                change.consume()
                onDrag(offsetX.coerceIn(-maxDrag, maxDrag))
            }
        }
        if (horizontal) {
            val trigger = abs(offsetX) > replyThreshold
            onRelease(offsetX, trigger)
        } else if (!longPressFired && abs(offsetX) < touchSlop && abs(offsetY) < touchSlop) {
            if (failed) onRetry()
        }
    }
}
