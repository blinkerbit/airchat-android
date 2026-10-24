package com.aird.chat.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import androidx.compose.ui.Alignment
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aird.call.CallController
import com.aird.call.CallPhase
import com.aird.chat.data.ChatRepository
import com.aird.chat.model.ChatMessage
import com.aird.chat.model.Conversation
import com.aird.chat.model.formatListTime
import com.aird.chat.model.seenLabel
import com.aird.session.Session
import com.aird.theme.AirdPalettes
import com.aird.theme.ThemeStore
import com.aird.ui.LocalAirdReducedMotion
import com.aird.ui.PressIconButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatHome(model: ChatViewModel = viewModel()) {
    val context = LocalContext.current
    val conversations by model.conversations.collectAsState()
    val active by model.activeId.collectAsState()
    val error by model.error.collectAsState()
    val call by CallController.snapshot.collectAsState()
    val pending by ChatRepository.pendingOpen.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var themeMenu by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(false) }
    val themeId by ThemeStore.id.collectAsState()
    val openChat = conversations.firstOrNull { it.id == active }

    LaunchedEffect(Unit) { model.refresh() }
    LaunchedEffect(pending) {
        if (pending.isEmpty()) return@LaunchedEffect
        val id = pending
        ChatRepository.consumePending()
        model.open(id)
    }
    LaunchedEffect(active) {
        sheet = false
        menu = false
        themeMenu = false
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.systemBars,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
                title = {
                    if (active.isEmpty()) {
                        Text("Aird Chat", fontWeight = FontWeight.Bold)
                    } else {
                        ThreadTitle(openChat, model)
                    }
                },
                navigationIcon = {
                    if (active.isNotEmpty()) {
                        IconButton(onClick = model::back) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (active.isEmpty()) {
                        IconButton(onClick = { sheet = true }) { Icon(Icons.Default.Add, contentDescription = "New chat") }
                        IconButton(onClick = { themeMenu = true }) {
                            Icon(Icons.Default.Palette, contentDescription = "Theme")
                        }
                        DropdownMenu(expanded = themeMenu, onDismissRequest = { themeMenu = false }) {
                            AirdPalettes.all.forEach { palette ->
                                DropdownMenuItem(
                                    text = { Text(if (palette.id == themeId) "${palette.label}  •" else palette.label) },
                                    onClick = {
                                        ThemeStore.set(context, palette.id)
                                        themeMenu = false
                                    },
                                )
                            }
                        }
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Menu") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Sign out") },
                                onClick = {
                                    menu = false
                                    Session.signOut(context)
                                },
                            )
                        }
                    } else {
                        CallButtons(active, openChat?.name().orEmpty())
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Menu") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(if (openChat?.muted == true) "Unmute" else "Mute") },
                                onClick = {
                                    menu = false
                                    model.mute(openChat?.muted != true)
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            if (call.minimized && call.phase != CallPhase.Idle && call.mode != "video") {
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary).clickable { CallController.expand() }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Call, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                    Text(
                        "Voice call with ${call.peer}",
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(start = 10.dp).weight(1f),
                    )
                    Text("Return", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                }
            }
            if (error.isNotEmpty()) {
                Text(error, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            val reduced = LocalAirdReducedMotion.current
            val slidePx = with(LocalDensity.current) { 8.dp.roundToPx() }
            AnimatedContent(
                targetState = active.isEmpty(),
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    if (reduced) {
                        ContentTransform(EnterTransition.None, ExitTransition.None, sizeTransform = null)
                    } else if (targetState) {
                        ContentTransform(fadeIn(tween(200)), fadeOut(tween(180)), sizeTransform = null)
                    } else {
                        ContentTransform(
                            fadeIn(tween(240)) + slideInVertically(tween(240)) { slidePx },
                            fadeOut(tween(180)),
                            sizeTransform = null,
                        )
                    }
                },
                label = "conversation",
            ) { showList ->
                if (showList) ConversationList(conversations, model) else Thread(model, openChat)
            }
        }
    }
    if (sheet) NewChatSheet(model, onDismiss = { sheet = false })
}

@Composable
private fun ThreadTitle(conversation: Conversation?, model: ChatViewModel) {
    val typing by model.typing.collectAsState()
    val presence by model.presence.collectAsState()

    val isPeerOnline = if (conversation != null && conversation.kind == "dm") {
        presence[conversation.peer] ?: presence[conversation.peer.lowercase()] ?: conversation.online
    } else false

    val onlineCount = if (conversation?.kind == "group") {
        conversation.members.count { member ->
            presence[member.username] ?: presence[member.username.lowercase()] ?: member.online
        }
    } else 0

    val subtitle = when {
        typing.isNotEmpty() -> "$typing is typing…"
        conversation == null -> "end-to-end encrypted"
        conversation.kind == "group" -> {
            val muted = if (conversation.muted) " · muted" else ""
            "${conversation.members.size} members · $onlineCount online$muted"
        }
        else -> {
            val muted = if (conversation.muted) " · muted" else ""
            if (isPeerOnline) "Online$muted" else "Offline$muted"
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box {
            Avatar(conversation?.name().orEmpty(), 38)
            if (isPeerOnline) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(1.5.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF4CAF50))
                )
            }
        }
        Column(Modifier.padding(start = 10.dp)) {
            Text(conversation?.name() ?: "Chat", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                fontSize = 12.sp,
                fontWeight = if (isPeerOnline) FontWeight.Medium else FontWeight.Normal,
                color = if (isPeerOnline) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1
            )
        }
    }
}

private val AvatarColors = listOf(
    Color(0xFF1E88E5), // Blue
    Color(0xFF43A047), // Green
    Color(0xFFE53935), // Red
    Color(0xFF8E24AA), // Purple
    Color(0xFFFB8C00), // Orange
    Color(0xFF00ACC1), // Cyan
    Color(0xFFD81B60), // Pink
    Color(0xFF3949AB)  // Indigo
)

@Composable
private fun Avatar(name: String, size: Int) {
    val colorIndex = kotlin.math.abs(name.hashCode()) % AvatarColors.size
    val bgColor = AvatarColors[colorIndex]
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(bgColor),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.ifEmpty { "?" }.take(1).uppercase(),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = (size * 0.42).sp
        )
    }
}

@Composable
private fun CallButtons(conversationId: String, peer: String) {
    val context = LocalContext.current
    var pendingMode by remember { mutableStateOf("audio") }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) CallController.start(context, conversationId, peer, pendingMode)
    }
    fun place(mode: String) {
        pendingMode = mode
        val needs = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (mode == "video") add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
        }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (needs.isEmpty()) CallController.start(context, conversationId, peer, mode)
        else launcher.launch(needs.toTypedArray())
    }
    PressIconButton(onClick = { place("audio") }) { Icon(Icons.Default.Call, contentDescription = "Voice call") }
    PressIconButton(onClick = { place("video") }) { Icon(Icons.Default.Videocam, contentDescription = "Video call") }
}

@Composable
private fun ConversationList(items: List<Conversation>, model: ChatViewModel) {
    var query by remember { mutableStateOf("") }
    val results by model.searchResults.collectAsState()
    val presence by model.presence.collectAsState()
    LaunchedEffect(query) {
        delay(250)
        model.search(query)
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("Search conversations…") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear")
                    }
                }
            },
            shape = RoundedCornerShape(24.dp),
            singleLine = true,
        )
        if (query.trim().length >= 2) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(results, key = { it.id }) { hit ->
                    Surface(
                        onClick = { model.open(hit.conversationId) },
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text("${hit.sender} · ${formatListTime(hit.createdAt)}", fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Spacer(Modifier.height(2.dp))
                            Text(hit.snippet, maxLines = 2, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                        }
                    }
                }
            }
            return
        }
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No conversations yet", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            }
            return
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(items, key = { it.id }) { conversation ->
                val online = if (conversation.kind == "dm") presence[conversation.peer] ?: conversation.online else false
                val isUnread = conversation.unread > 0
                val rowBg = if (isUnread) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                }

                Surface(
                    onClick = { model.open(conversation.id) },
                    shape = RoundedCornerShape(16.dp),
                    color = rowBg,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box {
                            Avatar(conversation.name(), 50)
                            if (online) {
                                Box(
                                    Modifier
                                        .align(Alignment.BottomEnd)
                                        .size(14.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surface)
                                        .padding(2.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF4CAF50))
                                )
                            }
                        }
                        Column(Modifier.padding(start = 14.dp).weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    conversation.name(),
                                    fontWeight = if (isUnread) FontWeight.Bold else FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    conversation.whenLabel(),
                                    fontSize = 12.sp,
                                    fontWeight = if (isUnread) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isUnread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (conversation.kind == "group") {
                                    Icon(
                                        Icons.Default.Group,
                                        contentDescription = "Group",
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                        modifier = Modifier.size(15.dp).padding(end = 3.dp)
                                    )
                                }
                                Text(
                                    conversation.preview.ifEmpty { "Encrypted message" },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = if (isUnread) FontWeight.Medium else FontWeight.Normal,
                                    color = if (isUnread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                                    modifier = Modifier.weight(1f),
                                )
                                if (conversation.muted) {
                                    Icon(
                                        Icons.Default.VolumeOff,
                                        contentDescription = "Muted",
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                                        modifier = Modifier.size(15.dp).padding(start = 4.dp)
                                    )
                                }
                                if (conversation.unread > 0) {
                                    Box(
                                        Modifier
                                            .padding(start = 8.dp)
                                            .height(22.dp)
                                            .padding(horizontal = 7.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            conversation.unread.coerceAtMost(99).toString(),
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Thread(model: ChatViewModel, conversation: Conversation?) {
    val messages by model.messages.collectAsState()
    val receipts by model.receipts.collectAsState()
    val pins by model.pins.collectAsState()
    val uploading by model.uploading.collectAsState()
    val me by Session.username.collectAsState()
    val listState = rememberLazyListState()
    var draft by remember { mutableStateOf("") }
    var panel by remember { mutableStateOf("") }
    var reply by remember { mutableStateOf<ChatMessage?>(null) }
    var highlightId by remember { mutableStateOf("") }
    var forward by remember { mutableStateOf<ChatMessage?>(null) }
    val density = LocalDensity.current
    val ime = WindowInsets.ime.getBottom(density)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val caption = draft.trim()
        draft = ""
        panel = ""
        model.attach(uris.take(10), caption, reply?.id)
        reply = null
    }
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val showScrollToBottom by remember {
        derivedStateOf {
            val total = listState.layoutInfo.totalItemsCount
            if (total == 0) false
            else {
                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                lastVisible < total - 3
            }
        }
    }
    val loadingMore by model.loadingMore.collectAsState()
    val hasMore by model.hasMore.collectAsState()
    var initialScrollDone by remember(conversation?.id) { mutableStateOf(false) }

    val entering = enteringMessageIds(conversation?.id.orEmpty(), messages)

    LaunchedEffect(listState.firstVisibleItemIndex) {
        if (listState.firstVisibleItemIndex <= 2 && hasMore && !loadingMore && messages.isNotEmpty()) {
            val prevIndex = listState.firstVisibleItemIndex
            val prevOffset = listState.firstVisibleItemScrollOffset
            model.loadMore { addedCount ->
                if (addedCount > 0) {
                    coroutineScope.launch {
                        listState.scrollToItem(prevIndex + addedCount, prevOffset)
                    }
                }
            }
        }
    }

    LaunchedEffect(highlightId) {
        if (highlightId.isEmpty()) return@LaunchedEffect
        delay(1500)
        highlightId = ""
    }

    fun focusMessage(id: String) {
        coroutineScope.launch {
            var guard = 0
            while (model.messages.value.none { it.id == id } && model.hasMore.value && guard < 40) {
                guard += 1
                val added = suspendCancellableCoroutine { cont ->
                    model.loadMore { count -> if (cont.isActive) cont.resume(count) }
                }
                if (added == 0) break
            }
            val index = model.messages.value.indexOfFirst { it.id == id }
            if (index >= 0) {
                listState.animateScrollToItem(index)
                highlightId = id
            }
        }
    }

    LaunchedEffect(messages.size, ime) {
        if (messages.isNotEmpty() && (!initialScrollDone || listState.firstVisibleItemIndex >= messages.size - 5)) {
            initialScrollDone = true
            listState.scrollToItem(messages.lastIndex)
        }
    }
    Column(Modifier.fillMaxSize().imePadding().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))) {
        if (pins.isNotEmpty()) {
            Text(
                "Pinned · ${pins.size}",
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.primary,
                fontSize = 13.sp,
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (loadingMore) {
                    item(key = "loading_more_progress") {
                        Box(
                            Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                items(messages, key = { it.id }) { message ->
                    val quoteMsg = messages.firstOrNull { it.id == message.replyTargetId() }
                    val quoteText = quoteMsg?.body.orEmpty()
                    MessageRow(
                        message = message,
                        quoteMessage = quoteMsg,
                        quoteText = quoteText,
                        seen = seenLabel(message, messages, receipts, me, conversation?.kind ?: "dm"),
                        highlighted = message.id == highlightId,
                        onQuoteClick = { focusMessage(it) },
                        onReply = { reply = message },
                        onForward = { forward = message },
                        onEdit = { model.edit(message, it) },
                        onDelete = { model.delete(message) },
                        onReact = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            model.react(message, it)
                        },
                        onPin = { model.pin(message) },
                        pinned = pins.any { it.messageId == message.id },
                        enter = message.id in entering,
                    )
                }
            }
            if (showScrollToBottom) {
                SmallFloatingActionButton(
                    onClick = {
                        coroutineScope.launch {
                            if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
                        }
                    },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Scroll to bottom")
                }
            }
        }
        Composer(
            draft = draft,
            onDraft = {
                draft = it
                model.typing()
            },
            panel = panel,
            onPanel = { panel = if (panel == it) "" else it },
            reply = reply?.let { "Reply to ${it.sender}: ${it.previewLabel()}" }.orEmpty(),
            onClearReply = { reply = null },
            uploading = uploading,
            onAttach = { picker.launch(arrayOf("*/*")) },
            onSend = {
                val text = draft.trim()
                if (text.isEmpty()) return@Composer
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                val replyId = reply?.id
                draft = ""
                panel = ""
                reply = null
                model.send(text, replyId)
            },
            onContent = { uri, _ ->
                val caption = draft.trim()
                draft = ""
                model.attach(listOf(uri), caption, reply?.id)
                reply = null
            },
        )
    }
    forward?.let { message ->
        ForwardSheet(
            conversations = model.conversations.collectAsState().value,
            onDismiss = { forward = null },
            onSend = { ids ->
                model.forward(message, ids)
                forward = null
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewChatSheet(model: ChatViewModel, onDismiss: () -> Unit) {
    var mode by remember { mutableStateOf("dm") }
    var query by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(listOf<String>()) }
    val people by model.people.collectAsState()
    LaunchedEffect(query) {
        delay(200)
        model.lookup(query)
    }
    val reduced = LocalAirdReducedMotion.current
    val panelEnter = if (reduced) EnterTransition.None else fadeIn(tween(220)) + expandVertically(tween(240))
    val panelExit = if (reduced) ExitTransition.None else fadeOut(tween(180)) + shrinkVertically(tween(200))
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                TextButton(onClick = { mode = "dm" }) {
                    Text(if (mode == "dm") "• Direct Message" else "Direct Message", fontWeight = if (mode == "dm") FontWeight.Bold else FontWeight.Normal)
                }
                Spacer(Modifier.size(12.dp))
                TextButton(onClick = { mode = "group" }) {
                    Text(if (mode == "group") "• Group Chat" else "Group Chat", fontWeight = if (mode == "group") FontWeight.Bold else FontWeight.Normal)
                }
            }
            AnimatedVisibility(visible = mode == "group", enter = panelEnter, exit = panelExit) {
                Column {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        placeholder = { Text("Group name") },
                        singleLine = true,
                    )
                    AnimatedVisibility(visible = picked.isNotEmpty(), enter = panelEnter, exit = panelExit) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Members:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            picked.forEach { member ->
                                Box(
                                    Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer).clickable { picked = picked - member }.padding(horizontal = 10.dp, vertical = 4.dp),
                                ) {
                                    Text("$member ✕", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(if (mode == "dm") "Search username" else "Add member username") },
                    singleLine = true,
                )
                if (query.isNotBlank()) {
                    TextButton(
                        onClick = {
                            val user = query.trim()
                            if (mode == "dm") {
                                model.createDm(user)
                            } else {
                                if (user !in picked) picked = picked + user
                                query = ""
                            }
                        },
                        modifier = Modifier.padding(start = 8.dp),
                    ) {
                        Text(if (mode == "dm") "Start" else "Add")
                    }
                }
            }
            LazyColumn(Modifier.fillMaxWidth().height(200.dp).padding(top = 8.dp)) {
                items(people, key = { it }) { username ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            if (mode == "dm") {
                                model.createDm(username)
                            } else if (username !in picked) {
                                picked = picked + username
                                query = ""
                            }
                        }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(username, 32)
                        Text(username, modifier = Modifier.padding(start = 12.dp), fontWeight = FontWeight.Medium)
                    }
                }
            }
            AnimatedVisibility(visible = mode == "group", enter = panelEnter, exit = panelExit) {
                TextButton(
                    onClick = { model.createGroup(title, picked) },
                    enabled = mode == "group" && title.isNotBlank() && picked.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Create Group Chat", fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ForwardSheet(conversations: List<Conversation>, onDismiss: () -> Unit, onSend: (List<String>) -> Unit) {
    var picked by remember { mutableStateOf(setOf<String>()) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Forward", fontWeight = FontWeight.Bold)
            LazyColumn(Modifier.fillMaxWidth().height(320.dp)) {
                items(conversations, key = { it.id }) { conversation ->
                    val on = conversation.id in picked
                    Text(
                        if (on) "✓ ${conversation.name()}" else conversation.name(),
                        modifier = Modifier.fillMaxWidth().clickable {
                            picked = if (on) picked - conversation.id else picked + conversation.id
                        }.padding(vertical = 12.dp),
                    )
                }
            }
            TextButton(onClick = { onSend(picked.toList()) }, enabled = picked.isNotEmpty()) { Text("Send") }
        }
    }
}

private class EnterGate {
    var primed = false
    var conversationId = ""
    val issued = HashSet<String>()
    var previous = emptyList<ChatMessage>()
}

@Composable
private fun enteringMessageIds(conversationId: String, messages: List<ChatMessage>): Set<String> {
    val gate = remember { EnterGate() }
    val reduced = LocalAirdReducedMotion.current
    val visible = if (conversationId.isEmpty()) messages else messages.filter { it.conversationId == conversationId }
    val switched = gate.primed && conversationId.isNotEmpty() && conversationId != gate.conversationId
    val entering = if (reduced || !gate.primed || switched) {
        emptySet()
    } else {
        val prevIds = gate.previous.map { it.id }.toSet()
        val currentIds = visible.map { it.id }.toSet()
        val removedPending = gate.previous.filter { it.pending && it.id !in currentIds }
        val fresh = visible.filter { msg ->
            msg.id !in prevIds && msg.id !in gate.issued &&
                removedPending.none { old -> old.mine == msg.mine && old.body == msg.body && old.sender == msg.sender }
        }
        val historyFill = gate.previous.isEmpty() && fresh.none { it.pending }
        if (fresh.isEmpty() || fresh.size > 3 || historyFill) emptySet() else fresh.map { it.id }.toSet()
    }
    SideEffect {
        if (switched) {
            gate.issued.clear()
            gate.issued.addAll(visible.map { it.id })
            gate.previous = visible
            gate.conversationId = conversationId
            gate.primed = true
        } else {
            gate.issued.addAll(visible.map { it.id })
            gate.previous = visible
            if (conversationId.isNotEmpty()) gate.conversationId = conversationId
            gate.primed = true
        }
    }
    return entering
}
