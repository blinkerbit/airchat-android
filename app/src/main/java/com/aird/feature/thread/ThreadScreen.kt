package com.aird.feature.thread

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.MusicOff
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aird.app.LocalNavigator
import com.aird.app.SharedKeys
import com.aird.app.shared
import com.aird.chat.data.ConversationPrefs
import com.aird.chat.model.Conversation
import com.aird.chat.ui.ChatViewModel
import com.aird.chat.ui.EnhancedSecureHub
import com.aird.chat.ui.Thread
import com.aird.chat.data.ChatRepository
import com.aird.designsystem.components.Avatar
import com.aird.designsystem.components.BackButton
import com.aird.designsystem.components.BounceIconButton
import com.aird.designsystem.components.SearchField
import com.aird.designsystem.haptics.LocalAirdHaptics
import com.aird.designsystem.motion.AirdMotion
import com.aird.designsystem.motion.LocalReducedMotion
import com.aird.designsystem.theme.LocalAirdColors
import com.aird.designsystem.theme.Spacing

@Composable
fun ThreadScreen(id: String, pane: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val vm: ThreadViewModel = viewModel(key = "thread:$id") { ThreadViewModel(app, id) }
    val model: ChatViewModel = viewModel()
    val header by vm.header.collectAsStateWithLifecycle()
    val ready by vm.ready.collectAsStateWithLifecycle()
    val conversations by ChatRepository.conversations.collectAsStateWithLifecycle()
    val reduced = LocalReducedMotion.current
    var searching by rememberSaveable(id) { mutableStateOf(false) }
    var query by rememberSaveable(id) { mutableStateOf("") }

    LaunchedEffect(id) { vm.ensureOpen() }
    BackHandler(enabled = searching) {
        searching = false
        query = ""
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AnimatedContent(
            targetState = searching,
            transitionSpec = { AirdMotion.through(reduced) },
            label = "threadBar",
        ) { search ->
            if (search) {
                Row(
                    Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = Spacing.sm, vertical = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BackButton(onClick = {
                        searching = false
                        query = ""
                    })
                    SearchField(
                        query = query,
                        onQueryChange = { query = it },
                        placeholder = "Search in this chat",
                        autofocus = true,
                        height = 48.dp,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                ThreadTopBar(
                    header = header,
                    pane = pane,
                    onBack = onBack,
                    onSearch = { searching = true },
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (ready) {
                val conversation = conversations.firstOrNull { it.id == id } ?: Conversation(id, header.name, header.peer, "", 0, "")
                Thread(model, conversation, query)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThreadTopBar(header: ThreadHeader, pane: Boolean, onBack: () -> Unit, onSearch: () -> Unit) {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val haptics = LocalAirdHaptics.current
    val reduced = LocalReducedMotion.current
    val colors = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var sound by remember(header.id) { mutableStateOf(ConversationPrefs.isSoundEnabled(context, header.id)) }
    val model: ChatViewModel = viewModel()

    TopAppBar(
        navigationIcon = { if (!pane) BackButton(onClick = onBack) },
        title = {
            Row(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { navigator.openChatInfo(header.id) }
                    .padding(vertical = Spacing.xs, horizontal = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(
                    header.name,
                    modifier = if (pane) Modifier else Modifier.shared(SharedKeys.avatar(header.id)),
                    size = 40.dp,
                    online = header.online,
                    group = header.group,
                )
                Spacer(Modifier.width(Spacing.md))
                Column {
                    Text(header.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    AnimatedContent(
                        targetState = header.status,
                        transitionSpec = { AirdMotion.through(reduced) },
                        label = "threadStatus",
                    ) { status ->
                        val live = header.typing.isNotEmpty() || (!header.group && header.online)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!live && !header.group) {
                                Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(11.dp).padding(end = 2.dp), tint = colors.onSurfaceVariant)
                            }
                            Text(
                                status,
                                style = MaterialTheme.typography.bodySmall,
                                color = when {
                                    header.typing.isNotEmpty() -> colors.primary
                                    live -> LocalAirdColors.current.online
                                    else -> colors.onSurfaceVariant
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        actions = {
            if (header.request.isEmpty()) {
                val call = rememberCallLauncher(header.id, header.name)
                BounceIconButton(onClick = {
                    haptics.tick()
                    call(true)
                }) { Icon(Icons.Rounded.Videocam, contentDescription = "Video call") }
                BounceIconButton(onClick = {
                    haptics.tick()
                    call(false)
                }) { Icon(Icons.Rounded.Call, contentDescription = "Voice call") }
            }
            Box {
                BounceIconButton(onClick = {
                    haptics.tick()
                    menu = true
                }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More options") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = RoundedCornerShape(16.dp)) {
                    DropdownMenuItem(
                        text = { Text("Search") },
                        leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        onClick = {
                            menu = false
                            onSearch()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (header.group) "Group info" else "Contact info") },
                        leadingIcon = { Icon(Icons.Rounded.Info, null) },
                        onClick = {
                            menu = false
                            navigator.openChatInfo(header.id)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (header.muted) "Unmute" else "Mute") },
                        leadingIcon = { Icon(if (header.muted) Icons.Rounded.NotificationsActive else Icons.Rounded.NotificationsOff, null) },
                        onClick = {
                            menu = false
                            model.mute(!header.muted)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (sound) "Silence sound" else "Play sound") },
                        leadingIcon = { Icon(if (sound) Icons.Rounded.MusicOff else Icons.Rounded.MusicNote, null) },
                        onClick = {
                            menu = false
                            sound = !sound
                            ConversationPrefs.setSoundEnabled(context, header.id, sound)
                        },
                    )
                    if (!header.group && header.peer.isNotBlank()) {
                        DropdownMenuItem(
                            text = { Text("Enhanced secure chat") },
                            leadingIcon = { Icon(Icons.Rounded.Lock, null) },
                            onClick = {
                                menu = false
                                EnhancedSecureHub.reveal()
                                EnhancedSecureHub.ensureStarted()
                                EnhancedSecureHub.start(header.peer)
                                navigator.openSecure()
                            },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Clear on this device") },
                        leadingIcon = { Icon(Icons.Rounded.CleaningServices, null) },
                        onClick = {
                            menu = false
                            model.clearLocalChat(header.id)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete chat", color = colors.error) },
                        leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = colors.error) },
                        onClick = {
                            menu = false
                            confirmDelete = true
                        },
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surfaceContainer),
    )
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null) },
            title = { Text("Delete ${header.name}?") },
            text = { Text("This conversation will be removed. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    model.deleteConversation(header.id)
                }) { Text("Delete", color = colors.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
