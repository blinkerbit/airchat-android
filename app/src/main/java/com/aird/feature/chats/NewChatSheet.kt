package com.aird.feature.chats

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PersonSearch
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aird.chat.data.ChatRepository
import com.aird.designsystem.components.AirdSheet
import com.aird.designsystem.components.Avatar
import com.aird.designsystem.components.IconTile
import com.aird.designsystem.components.SearchField
import com.aird.designsystem.components.SectionHeader
import com.aird.designsystem.haptics.LocalAirdHaptics
import com.aird.designsystem.motion.AirdMotion
import com.aird.designsystem.motion.AirdMotion.slide
import com.aird.designsystem.motion.LocalReducedMotion
import com.aird.designsystem.theme.Spacing
import com.aird.session.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NewChatSheet(onDismiss: () -> Unit, onSecure: () -> Unit) {
    val context = LocalContext.current
    val haptics = LocalAirdHaptics.current
    val reduced = LocalReducedMotion.current
    val conversations by ChatRepository.conversations.collectAsStateWithLifecycle()
    val me by Session.username.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var group by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(listOf<String>()) }
    var results by remember { mutableStateOf<List<String>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf("") }

    val recent = remember(conversations) {
        conversations.filter { it.kind != "group" && it.peer.isNotBlank() }.map { it.peer }.distinct().take(12)
    }
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) {
            results = emptyList()
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(220)
        results = try {
            ChatRepository.searchUsers(context, q).filterNot { it.equals(me, true) }
        } catch (err: CancellationException) {
            throw err
        } catch (_: Exception) {
            emptyList()
        }
        searching = false
    }
    val people = if (query.trim().length >= 2) {
        val typed = query.trim()
        val exact = results.any { it.equals(typed, true) }
        if (exact || typed.equals(me, true)) results else results + typed
    } else {
        recent
    }

    AirdSheet(onDismiss = onDismiss) {
        val sheet = this

        fun start(user: String) {
            if (busy.isNotEmpty()) return
            busy = user
            failure = ""
            scope.launch {
                val ok = ChatRepository.createDm(context, user)
                busy = ""
                if (ok) {
                    haptics.confirm()
                    dismiss()
                } else {
                    haptics.reject()
                    failure = ChatRepository.error.value.ifBlank { "Couldn't start a chat with $user" }
                }
            }
        }

        fun create() {
            if (busy.isNotEmpty() || title.isBlank() || picked.isEmpty()) return
            busy = "group"
            failure = ""
            scope.launch {
                val ok = ChatRepository.createGroup(context, title.trim(), picked)
                busy = ""
                if (ok) {
                    haptics.confirm()
                    dismiss()
                } else {
                    haptics.reject()
                    failure = ChatRepository.error.value.ifBlank { "Couldn't create the group" }
                }
            }
        }

        AnimatedContent(
            targetState = group,
            transitionSpec = { slide(forward = targetState, reduced = reduced) },
            label = "newChatMode",
        ) { groupMode ->
            Column(Modifier.fillMaxWidth().imePadding()) {
                Text(
                    if (groupMode) "New group" else "New chat",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(horizontal = Spacing.xxl, vertical = Spacing.sm),
                )
                if (groupMode) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Group name") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xl, vertical = Spacing.xs),
                    )
                    AnimatedVisibility(picked.isNotEmpty(), enter = AirdMotion.reveal(reduced), exit = AirdMotion.hide(reduced)) {
                        LazyRow(
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Spacing.xl),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                            modifier = Modifier.padding(vertical = Spacing.xs),
                        ) {
                            items(picked, key = { it }) { name ->
                                InputChip(
                                    selected = true,
                                    onClick = { picked = picked - name },
                                    label = { Text(name) },
                                    avatar = { Avatar(name, size = InputChipDefaults.AvatarSize) },
                                    trailingIcon = { Icon(Icons.Rounded.Close, contentDescription = "Remove $name", modifier = Modifier.size(16.dp)) },
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }
                    }
                }
                SearchField(
                    query = query,
                    onQueryChange = {
                        query = it
                        failure = ""
                    },
                    placeholder = if (groupMode) "Add people" else "Search people by username",
                    autofocus = !groupMode,
                    modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.sm),
                    leading = Icons.Rounded.PersonSearch,
                    onSearch = {
                        val typed = query.trim()
                        if (typed.isEmpty()) return@SearchField
                        if (groupMode) {
                            if (typed !in picked) picked = picked + typed
                            query = ""
                        } else {
                            start(typed)
                        }
                    },
                )
                if (failure.isNotEmpty()) {
                    Text(
                        failure,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = Spacing.xxl, vertical = Spacing.xs),
                    )
                }
                if (!groupMode && query.isEmpty()) {
                    QuickAction(Icons.Rounded.GroupAdd, "New group", "Chat with several people at once") { group = true }
                    QuickAction(Icons.Rounded.Lock, "Enhanced secure chat", "Ephemeral and never stored") {
                        sheet.dismiss(onSecure)
                    }
                }
                SectionHeader(
                    when {
                        query.trim().length >= 2 -> if (searching) "Searching…" else "People"
                        people.isEmpty() -> ""
                        else -> "Recent"
                    },
                )
                LazyColumn(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 360.dp)) {
                    items(people, key = { it }) { name ->
                        val chosen = name in picked
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    haptics.tick()
                                    if (groupMode) {
                                        picked = if (chosen) picked - name else picked + name
                                    } else {
                                        start(name)
                                    }
                                }
                                .padding(horizontal = Spacing.xxl, vertical = 10.dp)
                                .animateItem(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(name, size = 42.dp)
                            Spacer(Modifier.width(Spacing.lg))
                            Text(
                                name,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            when {
                                busy == name -> LoadingIndicator(Modifier.size(28.dp))
                                groupMode -> Checkbox(checked = chosen, onCheckedChange = null)
                            }
                        }
                    }
                }
                if (groupMode) {
                    Button(
                        onClick = ::create,
                        enabled = title.isNotBlank() && picked.isNotEmpty() && busy.isEmpty(),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xl, vertical = Spacing.md).heightIn(min = 52.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (busy == "group") {
                                LoadingIndicator(Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                            } else {
                                Text(if (picked.isEmpty()) "Create group" else "Create group · ${picked.size + 1} people")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickAction(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.xxl, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, size = 42.dp, container = MaterialTheme.colorScheme.primaryContainer, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        Spacer(Modifier.width(Spacing.lg))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
