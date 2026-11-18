package com.aird.feature.thread

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aird.app.LocalNavigator
import com.aird.app.SharedKeys
import com.aird.app.shared
import com.aird.chat.data.ChatRepository
import com.aird.chat.data.ConversationPrefs
import com.aird.chat.ui.AddMemberDialog
import com.aird.chat.ui.ChatViewModel
import com.aird.chat.ui.EnhancedSecureHub
import com.aird.data.ChatPrefsStore
import com.aird.designsystem.components.AirdTopBar
import com.aird.designsystem.components.Avatar
import com.aird.designsystem.components.BackButton
import com.aird.designsystem.components.Segment
import com.aird.designsystem.components.SectionHeader
import com.aird.designsystem.components.SegmentedGroup
import com.aird.designsystem.components.SettingRow
import com.aird.designsystem.components.SwitchRow
import com.aird.designsystem.haptics.LocalAirdHaptics
import com.aird.designsystem.theme.LocalAirdColors
import com.aird.designsystem.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatInfoScreen(id: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val navigator = LocalNavigator.current
    val haptics = LocalAirdHaptics.current
    val vm: ThreadViewModel = viewModel(key = "info:$id") { ThreadViewModel(app, id, ownsThread = false) }
    val model: ChatViewModel = viewModel()
    val header by vm.header.collectAsStateWithLifecycle()
    val conversations by ChatRepository.conversations.collectAsStateWithLifecycle()
    val presence by ChatRepository.presence.collectAsStateWithLifecycle()
    val prefs by ChatPrefsStore.prefs.collectAsStateWithLifecycle()
    val conversation = conversations.firstOrNull { it.id == id }
    val pinned = (prefs[id]?.pinnedAt ?: 0L) > 0L
    var sound by remember(id) { mutableStateOf(ConversationPrefs.isSoundEnabled(context, id)) }
    var addMember by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val call = rememberCallLauncher(id, header.name)
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            AirdTopBar(title = {}, navigationIcon = { BackButton(onClick = onBack) }, scrollBehavior = scroll)
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = Spacing.xxxl),
        ) {
            item(key = "header") {
                Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.xxl), horizontalAlignment = Alignment.CenterHorizontally) {
                    Avatar(
                        header.name,
                        modifier = Modifier.shared(SharedKeys.avatar(id)),
                        size = 112.dp,
                        online = header.online,
                        group = header.group,
                    )
                    Spacer(Modifier.height(Spacing.lg))
                    Text(
                        header.name,
                        style = MaterialTheme.typography.headlineMedium,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        header.status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (header.online || header.typing.isNotEmpty()) LocalAirdColors.current.online else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Spacing.xl))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        if (header.request.isEmpty()) {
                            InfoAction(Icons.Rounded.Call, "Voice") { call(false) }
                            InfoAction(Icons.Rounded.Videocam, "Video") { call(true) }
                        }
                        InfoAction(if (header.muted) Icons.Rounded.NotificationsActive else Icons.Rounded.NotificationsOff, if (header.muted) "Unmute" else "Mute") {
                            haptics.toggle(!header.muted)
                            model.mute(!header.muted)
                        }
                        InfoAction(Icons.Rounded.PushPin, if (pinned) "Unpin" else "Pin") {
                            haptics.toggle(!pinned)
                            ChatPrefsStore.setPinned(id, !pinned)
                        }
                    }
                    Spacer(Modifier.height(Spacing.xl))
                }
            }
            item(key = "privacy") {
                SegmentedGroup {
                    SettingRow(
                        title = "End-to-end encrypted",
                        subtitle = "Messages and calls are sealed on your devices. The server only relays ciphertext.",
                        icon = Icons.Rounded.VerifiedUser,
                    )
                    if (!header.group && header.peer.isNotBlank()) {
                        SettingRow(
                            title = "Enhanced secure chat",
                            subtitle = "Start an ephemeral session that is never stored",
                            icon = Icons.Rounded.Lock,
                            chevron = true,
                            onClick = {
                                EnhancedSecureHub.reveal()
                                EnhancedSecureHub.ensureStarted()
                                EnhancedSecureHub.start(header.peer)
                                navigator.openSecure()
                            },
                        )
                    }
                }
            }
            item(key = "notify") {
                SectionHeader("Notifications")
                SegmentedGroup {
                    SwitchRow(
                        title = "Mute notifications",
                        checked = header.muted,
                        onCheckedChange = { model.mute(it) },
                        icon = Icons.Rounded.NotificationsOff,
                    )
                    SwitchRow(
                        title = "Message sound",
                        checked = sound,
                        onCheckedChange = {
                            sound = it
                            ConversationPrefs.setSoundEnabled(context, id, it)
                        },
                        icon = Icons.Rounded.MusicNote,
                    )
                }
            }
            if (header.group && conversation != null) {
                item(key = "membersHeader") {
                    SectionHeader(if (header.members == 1) "1 member" else "${header.members} members")
                }
                item(key = "members") {
                    SegmentedGroup {
                        SettingRow(
                            title = "Add member",
                            icon = Icons.Rounded.PersonAdd,
                            iconContainer = MaterialTheme.colorScheme.primaryContainer,
                            iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                            onClick = { addMember = true },
                        )
                        conversation.members.forEach { member ->
                            val online = presence[member.username] ?: presence[member.username.lowercase()] ?: member.online
                            Segment {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.md),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Avatar(member.username, size = 40.dp, online = online)
                                    Spacer(Modifier.width(Spacing.lg))
                                    Column(Modifier.weight(1f)) {
                                        Text(member.username, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            if (online) "Online" else "Offline",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (online) LocalAirdColors.current.online else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item(key = "danger") {
                Spacer(Modifier.height(Spacing.xl))
                SegmentedGroup {
                    SettingRow(
                        title = "Clear on this device",
                        subtitle = "History reloads from the server when you open the chat",
                        icon = Icons.Rounded.CleaningServices,
                        onClick = { model.clearLocalChat(id) },
                    )
                    if (header.group) {
                        SettingRow(
                            title = "Leave group",
                            icon = Icons.AutoMirrored.Rounded.ExitToApp,
                            destructive = true,
                            onClick = { confirmLeave = true },
                        )
                    }
                    SettingRow(
                        title = "Delete chat",
                        icon = Icons.Rounded.DeleteOutline,
                        destructive = true,
                        onClick = { confirmDelete = true },
                    )
                }
                Spacer(Modifier.navigationBarsPadding())
            }
        }
    }

    if (addMember) AddMemberDialog(model, onDismiss = { addMember = false })
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Leave ${header.name}?") },
            text = { Text("You'll stop receiving messages from this group.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    model.leaveGroup()
                }) { Text("Leave", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Cancel") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null) },
            title = { Text("Delete ${header.name}?") },
            text = { Text("This conversation will be removed. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    model.deleteConversation(id)
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun InfoAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp)) {
        FilledTonalIconButton(
            onClick = onClick,
            modifier = Modifier.size(56.dp),
            shape = RoundedCornerShape(18.dp),
        ) { Icon(icon, contentDescription = label) }
        Spacer(Modifier.height(Spacing.xs))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
