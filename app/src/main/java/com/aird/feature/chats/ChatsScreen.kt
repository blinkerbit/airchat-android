package com.aird.feature.chats

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.BubbleChart
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aird.app.LocalNavigator
import com.aird.app.SharedKeys
import com.aird.app.shared
import com.aird.chat.ui.EnhancedSecureHub
import com.aird.designsystem.components.AirdLargeTopBar
import com.aird.designsystem.components.AirdSheet
import com.aird.designsystem.components.AirdSnackbarHost
import com.aird.designsystem.components.Avatar
import com.aird.designsystem.components.BounceIconButton
import com.aird.designsystem.components.ConnectionPill
import com.aird.designsystem.components.ConnectionStatus
import com.aird.designsystem.components.CountBadge
import com.aird.designsystem.components.EmptyState
import com.aird.designsystem.components.IconTile
import com.aird.designsystem.components.SearchField
import com.aird.designsystem.components.SheetAction
import com.aird.designsystem.components.SkeletonRow
import com.aird.designsystem.components.SwipeAction
import com.aird.designsystem.components.SwipeRow
import com.aird.designsystem.haptics.LocalAirdHaptics
import com.aird.designsystem.theme.LocalAirdColors
import com.aird.designsystem.theme.Spacing
import com.aird.designsystem.theme.TabularNumbers
import com.aird.session.AirdSocket
import com.aird.session.Session
import kotlinx.coroutines.launch

fun AirdSocket.Link.toStatus(): ConnectionStatus = when (this) {
    AirdSocket.Link.Online -> ConnectionStatus.Online
    AirdSocket.Link.Connecting -> ConnectionStatus.Connecting
    AirdSocket.Link.Offline -> ConnectionStatus.Offline
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChatsScreen(
    selectedId: String,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
    vm: ChatsViewModel = viewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val link by AirdSocket.link.collectAsStateWithLifecycle()
    val me by Session.username.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val haptics = LocalAirdHaptics.current
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val list = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var newChat by rememberSaveable { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<ChatRowUi?>(null) }
    var confirmDelete by remember { mutableStateOf<ChatRowUi?>(null) }
    val fabExpanded by remember { derivedStateOf { list.firstVisibleItemIndex < 3 } }

    BackHandler(enabled = ui.query.isNotEmpty() || ui.filter != ChatFilter.All) {
        vm.setQuery("")
        if (ui.filter != ChatFilter.All) vm.setFilter(ui.filter)
    }

    fun openSecure(peer: String? = null, room: String? = null) {
        EnhancedSecureHub.reveal()
        EnhancedSecureHub.ensureStarted()
        if (room != null) EnhancedSecureHub.show(room)
        if (peer != null) EnhancedSecureHub.start(peer)
        navigator.openSecure()
    }

    Scaffold(
        modifier = modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            AirdLargeTopBar(
                title = "Chats",
                scrollBehavior = scroll,
                actions = {
                    BounceIconButton(onClick = { openSecure() }) {
                        Icon(Icons.Rounded.Shield, contentDescription = "Enhanced secure chat")
                    }
                    BounceIconButton(onClick = navigator::openSettings, modifier = Modifier.semantics { contentDescription = "Settings" }) {
                        Avatar(me, size = 32.dp)
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    haptics.tick()
                    newChat = true
                },
                expanded = fabExpanded,
                icon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                text = { Text("New chat") },
            )
        },
        snackbarHost = { AirdSnackbarHost(snackbar) },
    ) { padding ->
        val pull = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                haptics.confirm()
                vm.refresh()
            },
            modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
            state = pull,
            indicator = {
                PullToRefreshDefaults.LoadingIndicator(
                    state = pull,
                    isRefreshing = refreshing,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            },
        ) {
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 96.dp),
            ) {
                item(key = "link", contentType = "link") { ConnectionPill(link.toStatus()) }
                item(key = "search", contentType = "search") {
                    SearchField(
                        query = ui.query,
                        onQueryChange = vm::setQuery,
                        placeholder = "Search chats",
                        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs),
                    )
                }
                item(key = "filters", contentType = "filters") {
                    FilterRow(ui) {
                        haptics.tick()
                        vm.setFilter(it)
                    }
                }
                when {
                    !ui.loaded -> items(8, key = { "skeleton$it" }, contentType = { "skeleton" }) { SkeletonRow() }
                    ui.rows.isEmpty() && ui.secure.isEmpty() -> item(key = "empty", contentType = "empty") {
                        ChatsEmpty(
                            ui = ui,
                            onNew = { newChat = true },
                            onReset = {
                                vm.setQuery("")
                                if (ui.filter != ChatFilter.All) vm.setFilter(ui.filter)
                            },
                        )
                    }
                    else -> {
                        items(ui.secure, key = { "secure:${it.roomId}" }, contentType = { "secure" }) { room ->
                            SecureRow(room, Modifier.animateItem()) { openSecure(room = room.roomId) }
                        }
                        items(ui.rows, key = { it.id }, contentType = { "chat" }) { row ->
                            ChatRow(
                                row = row,
                                selected = row.id == selectedId,
                                modifier = Modifier.animateItem(),
                                onClick = { onOpen(row.id) },
                                onLongClick = {
                                    haptics.longPress()
                                    actionsFor = row
                                },
                                onPin = { vm.togglePin(row.id) },
                                onMute = { vm.setMuted(row.id, !row.muted) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (newChat) {
        NewChatSheet(
            onDismiss = { newChat = false },
            onSecure = { openSecure() },
        )
    }
    actionsFor?.let { row ->
        AirdSheet(onDismiss = { actionsFor = null }) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.xxl, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(row.name, size = 44.dp, online = row.online, group = row.group)
                Spacer(Modifier.width(Spacing.lg))
                Column(Modifier.weight(1f)) {
                    Text(row.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (row.group) "Group" else "Direct message",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = Spacing.sm), color = LocalAirdColors.current.hairline)
            SheetAction(Icons.Rounded.PushPin, if (row.pinned) "Unpin" else "Pin to top", onClick = { dismiss { vm.togglePin(row.id) } })
            SheetAction(
                if (row.muted) Icons.Rounded.NotificationsActive else Icons.Rounded.NotificationsOff,
                if (row.muted) "Unmute notifications" else "Mute notifications",
                onClick = { dismiss { vm.setMuted(row.id, !row.muted) } },
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                SheetAction(Icons.Rounded.BubbleChart, "Open as bubble", onClick = { dismiss { vm.bubble(row.id) } })
            }
            if (!row.group && row.peer.isNotBlank()) {
                SheetAction(
                    Icons.Rounded.Lock,
                    "Enhanced secure chat",
                    supporting = "Ephemeral, end-to-end, never stored",
                    onClick = { dismiss { openSecure(peer = row.peer) } },
                )
            }
            SheetAction(
                Icons.Rounded.CleaningServices,
                "Clear on this device",
                supporting = "Removes the saved copy; history reloads from the server",
                onClick = {
                    dismiss {
                        vm.clearLocal(row.id)
                        scope.launch { snackbar.showSnackbar("Cleared ${row.name} from this device") }
                    }
                },
            )
            SheetAction(
                Icons.Rounded.DeleteOutline,
                "Delete chat",
                destructive = true,
                onClick = { dismiss { confirmDelete = row } },
            )
        }
    }
    confirmDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            icon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null) },
            title = { Text("Delete ${row.name}?") },
            text = { Text("This conversation will be removed. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    haptics.confirm()
                    vm.delete(row.id) { ok ->
                        if (!ok) scope.launch { snackbar.showSnackbar("Couldn't delete the chat") }
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FilterRow(ui: ChatsUi, onFilter: (ChatFilter) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        ChatFilter.entries.forEach { f ->
            val count = when (f) {
                ChatFilter.All -> 0
                ChatFilter.Unread -> ui.unreadChats
                ChatFilter.Groups -> ui.groups
                ChatFilter.Requests -> ui.requests
            }
            val selected = ui.filter == f
            if ((f == ChatFilter.Requests || f == ChatFilter.Groups) && count == 0 && !selected) return@forEach
            FilterChip(
                selected = selected,
                onClick = { onFilter(f) },
                label = {
                    Text(if (count > 0 && f != ChatFilter.Groups) "${f.label}  $count" else f.label, style = MaterialTheme.typography.labelLarge.merge(TabularNumbers))
                },
                leadingIcon = if (selected) {
                    { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                } else {
                    null
                },
                shape = CircleShape,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(
    row: ChatRowUi,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPin: () -> Unit,
    onMute: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val unread = row.unread > 0
    SwipeRow(
        modifier = modifier,
        start = SwipeAction(Icons.Rounded.PushPin, if (row.pinned) "Unpin" else "Pin", colors.tertiaryContainer, colors.onTertiaryContainer, onPin),
        end = SwipeAction(
            if (row.muted) Icons.Rounded.NotificationsActive else Icons.Rounded.NotificationsOff,
            if (row.muted) "Unmute" else "Mute",
            colors.secondaryContainer,
            colors.onSecondaryContainer,
            onMute,
        ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .padding(horizontal = Spacing.sm)
                .clip(RoundedCornerShape(20.dp))
                .background(if (selected) colors.secondaryContainer else Color.Transparent)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "Chat actions")
                .padding(horizontal = Spacing.sm, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(
                row.name,
                modifier = Modifier.shared(SharedKeys.avatar(row.id)),
                size = 54.dp,
                online = row.online,
                group = row.group,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        row.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (unread) FontWeight.Bold else FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (row.muted) {
                        Icon(
                            Icons.Rounded.NotificationsOff,
                            contentDescription = "Muted",
                            tint = colors.onSurfaceVariant,
                            modifier = Modifier.padding(start = Spacing.xs).size(15.dp),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        row.time,
                        style = MaterialTheme.typography.labelMedium.merge(TabularNumbers),
                        color = if (unread && !row.muted) colors.primary else colors.onSurfaceVariant,
                        modifier = Modifier.padding(start = Spacing.sm),
                    )
                }
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PreviewLine(row, Modifier.weight(1f))
                    if (row.pinned && !unread) {
                        Icon(
                            Icons.Rounded.PushPin,
                            contentDescription = "Pinned",
                            tint = colors.onSurfaceVariant,
                            modifier = Modifier.padding(start = Spacing.sm).size(16.dp).rotate(45f),
                        )
                    }
                    CountBadge(row.unread, Modifier.padding(start = Spacing.sm), muted = row.muted)
                }
            }
        }
    }
}

@Composable
private fun PreviewLine(row: ChatRowUi, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val unread = row.unread > 0
    val text = when (row.tone) {
        PreviewTone.Draft -> buildAnnotatedString {
            withStyle(SpanStyle(color = colors.error, fontWeight = FontWeight.SemiBold)) { append("Draft: ") }
            append(row.preview)
        }
        else -> buildAnnotatedString { append(row.preview) }
    }
    val color = when (row.tone) {
        PreviewTone.Typing -> colors.primary
        PreviewTone.Request -> colors.tertiary
        else -> if (unread) colors.onSurface else colors.onSurfaceVariant
    }
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = if (row.tone == PreviewTone.Request) FontWeight.SemiBold else null,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun SecureRow(room: SecureRowUi, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.xs)
            .clip(RoundedCornerShape(20.dp))
            .background(colors.errorContainer.copy(alpha = 0.55f))
            .combinedClickable(onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(Icons.Rounded.Lock, size = 48.dp, container = colors.error, tint = colors.onError)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                room.peer.ifBlank { "Enhanced secure chat" },
                style = MaterialTheme.typography.titleMedium,
                color = colors.onErrorContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (room.requested) "Waiting for ${room.peer.ifBlank { "them" }} to join" else room.preview.ifBlank { "Secure session open" },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onErrorContainer.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ChatsEmpty(ui: ChatsUi, onNew: () -> Unit, onReset: () -> Unit) {
    when {
        ui.query.isNotBlank() -> EmptyState(
            icon = Icons.Rounded.SearchOff,
            title = "No chats match “${ui.query.trim()}”",
            body = "Try the Search tab to look through messages and files.",
            action = "Clear search",
            onAction = onReset,
        )
        ui.filter == ChatFilter.Unread -> EmptyState(
            icon = Icons.Rounded.DoneAll,
            title = "You're all caught up",
            body = "No unread chats right now.",
            action = "Show all chats",
            onAction = onReset,
        )
        ui.filter != ChatFilter.All -> EmptyState(
            icon = Icons.Rounded.Forum,
            title = "Nothing here",
            action = "Show all chats",
            onAction = onReset,
        )
        else -> EmptyState(
            icon = Icons.AutoMirrored.Rounded.Chat,
            title = "No chats yet",
            body = "Start a conversation with anyone on your Aird server. Messages are end-to-end encrypted.",
            action = "Start a chat",
            onAction = onNew,
        )
    }
}
