package com.aird.chat.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.sp
import com.aird.chat.data.ChatRepository
import com.aird.chat.data.CollabException
import com.aird.chat.data.CollabRepository
import com.aird.chat.data.ListAlarms
import com.aird.chat.model.CollabTag
import com.aird.chat.model.ListArtifact
import com.aird.chat.model.ListDraft
import com.aird.chat.model.ListEntry
import com.aird.chat.model.ListGroup
import com.aird.chat.model.ListSummary
import com.aird.session.Session
import com.aird.ui.LocalAirdReducedMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AirdListsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val reduced = LocalAirdReducedMotion.current
    var lists by remember { mutableStateOf(listOf<ListSummary>()) }
    var groups by remember { mutableStateOf(listOf<ListGroup>()) }
    var query by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf("") }
    var openId by remember { mutableStateOf("") }
    val slots by CollabRepository.slots.collectAsState()
    val detail = slots.values.firstOrNull { it.artifact?.id == openId }?.artifact as? ListArtifact
    var error by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var grouping by remember { mutableStateOf(false) }
    var organizing by remember { mutableStateOf(false) }
    var movingId by remember { mutableStateOf("") }
    var share by remember { mutableStateOf(false) }
    var editingList by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ListEntry?>(null) }
    var removing by remember { mutableStateOf<ListEntry?>(null) }
    var draft by remember { mutableStateOf("") }
    var draftKind by remember { mutableStateOf("checkbox") }
    var due by remember { mutableStateOf("") }
    var noteId by remember { mutableStateOf("") }
    var focusedId by remember { mutableStateOf("") }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    suspend fun loadBoard() {
        val board = CollabRepository.listBoard(context, "")
        lists = board.lists.distinctBy { it.id }
        groups = board.groups.distinctBy { it.id }
        ListAlarms.sync(context, board.reminders)
    }

    fun work(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
                error = ""
            } catch (err: CancellationException) {
                throw err
            } catch (err: Exception) {
                error = err.message ?: "Could not save"
            }
        }
    }

    LaunchedEffect(Unit) {
        CollabRepository.live.collect { (kind, id) ->
            if (kind != "list") return@collect
            try {
                loadBoard()
                if (openId.isNotBlank() && (id.isBlank() || id == openId)) {
                    CollabRepository.refresh(context, "list", openId, "")
                }
            } catch (err: CancellationException) {
                throw err
            } catch (_: Exception) {
            }
        }
    }
    LaunchedEffect(Unit) {
        try {
            loadBoard()
            error = ""
        } catch (err: CancellationException) {
            throw err
        } catch (err: Exception) {
            error = err.message ?: "Could not load lists"
        }
    }
    LaunchedEffect(openId) {
        draft = ""
        draftKind = "checkbox"
        due = ""
        noteId = ""
        focusedId = ""
        if (openId.isNotBlank()) {
            try {
                CollabRepository.refresh(context, "list", openId, "")
            } catch (err: CancellationException) {
                throw err
            } catch (err: Exception) {
                error = err.message ?: "Could not open that list"
            }
        }
    }
    BackHandler(enabled = noteId.isNotEmpty() || openId.isNotEmpty() || creating || grouping || organizing || movingId.isNotEmpty()) {
        when {
            noteId.isNotEmpty() -> noteId = ""
            creating -> creating = false
            grouping -> grouping = false
            organizing -> organizing = false
            movingId.isNotEmpty() -> movingId = ""
            else -> openId = ""
        }
    }

    val needle = query.trim()
    val shown = lists.filter { row ->
        val inFolder = when (folder) {
            "" -> true
            "none" -> row.groupId.isBlank()
            else -> row.groupId == folder
        }
        val matches = needle.isEmpty() || row.title.contains(needle, true) || row.search.contains(needle, true)
        inFolder && matches
    }
    val moving = lists.firstOrNull { it.id == movingId }

    Scaffold(
        contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.ime),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (openId.isBlank()) "Lists" else detail?.title?.ifBlank { "List" } ?: "List",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        when {
                            noteId.isNotEmpty() -> noteId = ""
                            openId.isNotBlank() -> openId = ""
                            else -> onBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (openId.isBlank()) {
                        TextButton(onClick = { organizing = true }) { Text("Groups") }
                    } else if (detail != null) {
                        IconButton(onClick = { share = true }) {
                            Icon(Icons.Outlined.Share, contentDescription = "Share")
                        }
                        TextButton(onClick = { editingList = true }) { Text("Edit") }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (error.isNotEmpty()) {
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            AnimatedContent(
                targetState = openId.isBlank(),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = {
                    fadeIn(tween(if (reduced) 0 else 60)) togetherWith fadeOut(tween(if (reduced) 0 else 60))
                },
                label = "lists",
            ) { index ->
                if (index) {
                    Column(Modifier.fillMaxSize()) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            singleLine = true,
                            shape = RoundedCornerShape(24.dp),
                            placeholder = { Text("Search titles and notes") },
                        )
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(selected = folder.isEmpty(), onClick = { folder = "" }, label = { Text("All") })
                            FilterChip(selected = folder == "none", onClick = { folder = "none" }, label = { Text("Ungrouped") })
                            groups.forEach { group ->
                                FilterChip(
                                    selected = folder == group.id,
                                    onClick = { folder = group.id },
                                    label = { Text(group.name, maxLines = 1) },
                                )
                            }
                        }
                        if (shown.isEmpty()) {
                            Column(
                                Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    if (lists.isEmpty()) "No lists yet" else "Nothing in this group",
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "Lists stay put when you remove a group. A group is only a folder.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 13.sp,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            }
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(shown, key = { "list:${it.id}" }) { row ->
                                    ListCard(row, groups, onOpen = { openId = row.id }, onMove = { movingId = row.id })
                                }
                            }
                        }
                        TextButton(
                            onClick = { creating = true },
                            modifier = Modifier.align(Alignment.End).padding(end = 12.dp, bottom = 8.dp),
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Text("New list", modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                } else {
                    val list = detail
                    if (list == null) {
                        Text("Opening…", modifier = Modifier.padding(24.dp))
                    } else {
                        ListDetail(
                            list = list,
                            groups = groups,
                            draft = draft,
                            due = due,
                            draftKind = draftKind,
                            focusedId = focusedId,
                            onDraft = { draft = it },
                            onDraftKind = { draftKind = it },
                            onFocus = { focusedId = it },
                            onOpenNote = { editing = list.items.firstOrNull { row -> row.id == it } },
                            onKind = { item, kind ->
                                work {
                                    val text = if (item.text.isBlank()) " " else item.text
                                    CollabRepository.updateItem(context, list, item, text, item.labels, item.notes, item.dueAt, kind)
                                }
                            },
                            onDue = { pickDateTime(context) { due = it } },
                            onClearDue = { due = "" },
                            onMove = { movingId = list.id },
                            onCheck = { item, checked ->
                                work {
                                    CollabRepository.setDone(context, list, item, checked)
                                    loadBoard()
                                }
                            },
                            onEdit = { editing = it },
                            onRemove = { removing = it },
                            onUp = { item ->
                                work {
                                    val ids = list.items.map { it.id }.toMutableList()
                                    val index = ids.indexOf(item.id)
                                    if (index > 0) {
                                        val previous = ids[index - 1]
                                        ids[index - 1] = ids[index]
                                        ids[index] = previous
                                        CollabRepository.reorder(context, list, ids)
                                    }
                                }
                            },
                            onDown = { item ->
                                work {
                                    val ids = list.items.map { it.id }.toMutableList()
                                    val index = ids.indexOf(item.id)
                                    if (index >= 0 && index < ids.lastIndex) {
                                        val next = ids[index + 1]
                                        ids[index + 1] = ids[index]
                                        ids[index] = next
                                        CollabRepository.reorder(context, list, ids)
                                    }
                                }
                            },
                            onAdd = {
                                work {
                                    CollabRepository.addItem(
                                        context,
                                        list,
                                        if (draft.isBlank()) " " else draft,
                                        notes = "",
                                        dueAt = if (draftKind == "checkbox") due else "",
                                        kind = draftKind,
                                    )
                                    draft = ""
                                    due = ""
                                    loadBoard()
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (creating) {
        ListCreateDialog(groups, folder, onDismiss = { creating = false }) { title, notes, style, groupId ->
            work {
                val id = CollabRepository.createBoardList(context, ListDraft(title, style, emptyList(), false, notes, groupId))
                creating = false
                loadBoard()
                openId = id
            }
        }
    }
    if (grouping) {
        GroupNameDialog(onDismiss = { grouping = false }) { name ->
            work {
                CollabRepository.createGroup(context, name)
                grouping = false
                loadBoard()
            }
        }
    }
    if (organizing) {
        OrganizeDialog(
            groups = groups,
            onDismiss = { organizing = false },
            onNew = { grouping = true },
            onRename = { group, name ->
                work {
                    CollabRepository.renameGroup(context, group.id, name)
                    loadBoard()
                }
            },
            onRemove = { group ->
                work {
                    CollabRepository.deleteGroup(context, group.id)
                    if (folder == group.id) folder = ""
                    loadBoard()
                }
            },
        )
    }
    if (moving != null) {
        MoveListDialog(
            list = moving,
            groups = groups,
            onDismiss = { movingId = "" },
            onPick = { groupId ->
                work {
                    CollabRepository.placeList(context, moving.id, groupId)
                    CollabRepository.refresh(context, "list", moving.id, "")
                    movingId = ""
                    loadBoard()
                }
            },
        )
    }
    if (share && detail != null) {
        ShareCollabDialog("list", detail.id, onDismiss = { share = false })
    }
    if (editingList && detail != null) {
        val list = detail
        ListEditDialog(list, onDismiss = { editingList = false }) { title, notes, style ->
            work {
                CollabRepository.updateList(context, list, ListDraft(title, style, emptyList(), list.privateShare, notes, list.groupId))
                editingList = false
                loadBoard()
            }
        }
    }
    editing?.let { item ->
        val list = detail ?: return@let
        ItemEditDialog(item, onDismiss = { editing = null }) { text, notes, dueAt ->
            work {
                CollabRepository.updateItem(context, list, item, text, item.labels, notes, dueAt)
                editing = null
                loadBoard()
            }
        }
    }
    removing?.let { item ->
        val list = detail ?: return@let
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove item") },
            text = { Text(item.text) },
            confirmButton = {
                TextButton(onClick = {
                    work {
                        CollabRepository.deleteItem(context, list, item.id)
                        removing = null
                        loadBoard()
                    }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ListCard(row: ListSummary, groups: List<ListGroup>, onOpen: () -> Unit, onMove: () -> Unit) {
    val groupName = groups.firstOrNull { it.id == row.groupId }?.name ?: "Ungrouped"
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(onClick = onOpen).padding(end = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        row.title.ifBlank { "Untitled" },
                        modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (sharedBySomeone(row.organizer)) {
                        Icon(
                            Icons.Outlined.Group,
                            contentDescription = "Shared by ${row.organizer}",
                            modifier = Modifier.padding(start = 6.dp).size(15.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (row.organizer.isNotBlank()) {
                    Text(
                        row.organizer,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                        listOf(
                        groupName,
                        if (row.total > 0) "${row.done}/${row.total}" else "",
                        friendlyTime(row.nextDue),
                    ).filter { it.isNotBlank() }.joinToString(" · "),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (row.style != "bullet" && row.total > 0) {
                    LinearProgressIndicator(
                        progress = { row.done.toFloat() / row.total.toFloat() },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(4.dp).clip(RoundedCornerShape(99.dp)),
                    )
                }
            }
            TextButton(onClick = onMove) { Text("Move") }
        }
    }
}

@Composable
private fun ListDetail(
    list: ListArtifact,
    groups: List<ListGroup>,
    draft: String,
    due: String,
    draftKind: String,
    focusedId: String,
    onDraft: (String) -> Unit,
    onDraftKind: (String) -> Unit,
    onFocus: (String) -> Unit,
    onOpenNote: (String) -> Unit,
    onKind: (ListEntry, String) -> Unit,
    onDue: () -> Unit,
    onClearDue: () -> Unit,
    onMove: () -> Unit,
    onCheck: (ListEntry, Boolean) -> Unit,
    onEdit: (ListEntry) -> Unit,
    onRemove: (ListEntry) -> Unit,
    onUp: (ListEntry) -> Unit,
    onDown: (ListEntry) -> Unit,
    onAdd: () -> Unit,
) {
    val groupName = groups.firstOrNull { it.id == list.groupId }?.name ?: "Ungrouped"
    val items = list.items.distinctBy { it.id }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (list.organizer.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                if (sharedBySomeone(list.organizer)) {
                    Icon(
                        Icons.Outlined.Group,
                        contentDescription = "Shared by ${list.organizer}",
                        modifier = Modifier.padding(end = 6.dp).size(14.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    list.organizer,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (list.tags.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                list.tags.forEach { tag -> TagMark(tag) }
            }
        }
        if (list.notes.isNotBlank()) {
            Text(list.notes, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = true, onClick = onMove, label = { Text(groupName) })
            if (list.total > 0) {
                Text(
                    "${list.done}/${list.total}",
                    modifier = Modifier.padding(start = 10.dp),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
            items(items, key = { "item:${it.id}" }) { item ->
                ItemRow(
                    item,
                    focusedId == item.id,
                    items.firstOrNull()?.id == item.id,
                    items.lastOrNull()?.id == item.id,
                    onCheck,
                    onEdit,
                    onRemove,
                    onUp,
                    onDown,
                    onFocus,
                    onOpenNote,
                    onKind,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("checkbox" to "Check", "bullet" to "Bullet", "paragraph" to "Text").forEach { (kind, label) ->
                FilterChip(selected = draftKind == kind, onClick = { onDraftKind(kind) }, label = { Text(label) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraft,
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (draftKind == "paragraph") "Text" else "Add an item") },
                singleLine = true,
            )
            IconButton(onClick = onAdd, enabled = draft.isNotBlank() || draftKind == "paragraph") {
                Icon(Icons.Default.Add, contentDescription = "Add item")
            }
        }
        if (draftKind == "checkbox") {
            Row {
                TextButton(onClick = onDue) { Text(if (due.isBlank()) "Due" else friendlyTime(due)) }
                if (due.isNotBlank()) TextButton(onClick = onClearDue) { Text("Clear") }
            }
        }
    }
}

@Composable
private fun TagMark(tag: CollabTag) {
    val tint = tagColor(tag.color)
    Text(
        tag.shown,
        fontSize = 11.sp,
        color = tint ?: MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(99.dp))
            .background((tint ?: MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

private fun sharedBySomeone(organizer: String): Boolean {
    val me = Session.username.value
    return organizer.isNotBlank() && me.isNotBlank() && !organizer.equals(me, true)
}

private fun tagColor(raw: String): Color? {
    val clean = raw.trim().removePrefix("#")
    if (clean.length != 6 && clean.length != 8) return null
    val value = clean.toLongOrNull(16) ?: return null
    return if (clean.length == 6) Color(0xFF000000 or value) else Color(value)
}

@Composable
private fun ItemRow(
    item: ListEntry,
    focused: Boolean,
    first: Boolean,
    last: Boolean,
    onCheck: (ListEntry, Boolean) -> Unit,
    onEdit: (ListEntry) -> Unit,
    onRemove: (ListEntry) -> Unit,
    onUp: (ListEntry) -> Unit,
    onDown: (ListEntry) -> Unit,
    onFocus: (String) -> Unit,
    onOpenNote: (String) -> Unit,
    onKind: (ListEntry, String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp * item.depth, top = 4.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (item.type == "checkbox") {
                Checkbox(checked = item.done, onCheckedChange = { onCheck(item, it) })
            } else if (item.type == "bullet") {
                Text("•", modifier = Modifier.padding(horizontal = 12.dp))
            }
            Column(
                Modifier.weight(1f).clickable { onEdit(item) }.padding(vertical = 8.dp),
            ) {
                Text(
                    item.text,
                    textDecoration = if (item.type == "checkbox" && item.done) TextDecoration.LineThrough else null,
                )
                if (item.type == "checkbox" && item.dueAt.isNotBlank()) {
                    Text(friendlyTime(item.dueAt), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            TextButton(onClick = { onFocus(item.id) }) { Text("Type") }
            if (!first) TextButton(onClick = { onUp(item) }) { Text("Up") }
            if (!last) TextButton(onClick = { onDown(item) }) { Text("Down") }
            TextButton(onClick = { onRemove(item) }) { Text("×") }
        }
        if (focused) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("checkbox" to "Check", "bullet" to "Bullet", "paragraph" to "Text").forEach { (kind, label) ->
                    FilterChip(selected = item.kind == kind, onClick = { onKind(item, kind) }, label = { Text(label) })
                }
            }
        }
    }
}

@Composable
private fun GroupNameDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(decorFitsSystemWindows = false),
        title = { Text("New group") },
        text = {
            Column(Modifier.imePadding().navigationBarsPadding()) {
                Text("A group is a folder on this phone's account. It does not change who can open the list.", fontSize = 13.sp)
                OutlinedTextField(name, { name = it }, modifier = Modifier.padding(top = 8.dp), placeholder = { Text("Home") })
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(name) }, enabled = name.isNotBlank()) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun OrganizeDialog(
    groups: List<ListGroup>,
    onDismiss: () -> Unit,
    onNew: () -> Unit,
    onRename: (ListGroup, String) -> Unit,
    onRemove: (ListGroup) -> Unit,
) {
    var rename by remember { mutableStateOf<ListGroup?>(null) }
    var name by remember(rename?.id) { mutableStateOf(rename?.name.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (rename == null) "Groups" else "Rename group") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                if (rename == null) {
                    Text("Removing a group leaves the lists in Ungrouped.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onNew) { Text("New group") }
                    groups.forEach { group ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(group.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            TextButton(onClick = { rename = group }) { Text("Rename") }
                            TextButton(onClick = { onRemove(group) }) { Text("Remove") }
                        }
                    }
                } else {
                    OutlinedTextField(name, { name = it }, placeholder = { Text("Name") })
                }
            }
        },
        confirmButton = {
            if (rename != null) {
                TextButton(onClick = {
                    val group = rename ?: return@TextButton
                    onRename(group, name)
                    rename = null
                }, enabled = name.isNotBlank()) { Text("Save") }
            } else {
                TextButton(onClick = onDismiss) { Text("Done") }
            }
        },
        dismissButton = {
            TextButton(onClick = { if (rename != null) rename = null else onDismiss() }) { Text("Cancel") }
        },
    )
}

@Composable
private fun MoveListDialog(list: ListSummary, groups: List<ListGroup>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("File ${list.title.ifBlank { "list" }}") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text("Only you see this folder.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { onPick("") }) { Text(if (list.groupId.isBlank()) "• Ungrouped" else "Ungrouped") }
                groups.forEach { group ->
                    TextButton(onClick = { onPick(group.id) }) {
                        Text(if (list.groupId == group.id) "• ${group.name}" else group.name)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun ListCreateDialog(
    groups: List<ListGroup>,
    folder: String,
    onDismiss: () -> Unit,
    onCreate: (String, String, String, String) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var groupId by remember { mutableStateOf(if (folder == "none") "" else folder) }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(decorFitsSystemWindows = false),
        title = { Text("New list") },
        text = {
            Column(
                Modifier.imePadding().navigationBarsPadding().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(title, { title = it }, placeholder = { Text("Title") })
                OutlinedTextField(notes, { notes = it }, placeholder = { Text("Description") })
                Text("File under", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { groupId = "" }) { Text(if (groupId.isBlank()) "• Ungrouped" else "Ungrouped") }
                groups.forEach { group ->
                    TextButton(onClick = { groupId = group.id }) {
                        Text(if (groupId == group.id) "• ${group.name}" else group.name)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(title, notes, "todo", groupId) },
                enabled = title.isNotBlank(),
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ListEditDialog(list: ListArtifact, onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    var title by remember(list.id) { mutableStateOf(list.title) }
    var notes by remember(list.id) { mutableStateOf(list.notes) }
    val mine = list.organizer.equals(Session.username.value, true)
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(decorFitsSystemWindows = false),
        title = { Text("Edit list") },
        text = {
            Column(
                Modifier.imePadding().navigationBarsPadding().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!mine) Text("Only the owner can change the title.", fontSize = 13.sp)
                OutlinedTextField(title, { title = it }, enabled = mine, placeholder = { Text("Title") })
                OutlinedTextField(notes, { notes = it }, enabled = mine, placeholder = { Text("Description") })
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(title, notes, list.style) }, enabled = mine && title.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ItemEditDialog(item: ListEntry, onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    val context = LocalContext.current
    var text by remember(item.id) { mutableStateOf(item.text) }
    var notes by remember(item.id) { mutableStateOf(item.notes) }
    var due by remember(item.id) { mutableStateOf(item.dueAt) }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(decorFitsSystemWindows = false),
        title = { Text("Edit item") },
        text = {
            Column(Modifier.imePadding().navigationBarsPadding().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(text, { text = it }, placeholder = { Text("Item") })
                OutlinedTextField(notes, { notes = it }, modifier = Modifier.padding(top = 8.dp), placeholder = { Text("Note") })
                TextButton(onClick = { pickDateTime(context) { due = it } }) {
                    Text(if (due.isBlank()) "Set due" else friendlyTime(due))
                }
                if (due.isNotBlank()) TextButton(onClick = { due = "" }) { Text("Clear due") }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text, notes, due) }, enabled = text.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun ShareCollabDialog(kind: String, id: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val chats by ChatRepository.conversations.collectAsState()
    var message by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        if (ChatRepository.conversations.value.isEmpty()) {
            try {
                ChatRepository.refresh(context)
            } catch (_: Exception) {
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to a chat") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                if (chats.isEmpty()) Text("No chats yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                chats.forEach { chat ->
                    Text(
                        chat.name(),
                        modifier = Modifier.fillMaxWidth().clickable {
                            scope.launch {
                                try {
                                    CollabRepository.shareIntoChat(context, chat.id, kind, id)
                                    onDismiss()
                                } catch (err: CancellationException) {
                                    throw err
                                } catch (err: Exception) {
                                    message = if (err is CollabException) err.message ?: "Could not share" else "Could not share"
                                }
                            }
                        }.padding(vertical = 10.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

internal fun friendlyTime(iso: String): String {
    val clean = iso.trim()
    if (clean.isBlank() || clean.equals("null", ignoreCase = true)) return ""
    return try {
        DateTimeFormatter.ofPattern("d MMM, HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(Instant.parse(clean))
    } catch (_: Exception) {
        if (clean.equals("null", ignoreCase = true)) ""
        else clean.take(16).replace('T', ' ')
    }
}

internal fun pickDateTime(context: android.content.Context, onPick: (String) -> Unit) {
    val now = Calendar.getInstance()
    DatePickerDialog(context, { _, year, month, day ->
        TimePickerDialog(context, { _, hour, minute ->
            val chosen = Calendar.getInstance()
            chosen.set(year, month, day, hour, minute, 0)
            onPick(Instant.ofEpochMilli(chosen.timeInMillis).toString())
        }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), false).show()
    }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).show()
}
