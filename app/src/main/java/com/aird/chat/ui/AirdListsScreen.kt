package com.aird.chat.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aird.chat.data.ChatRepository
import com.aird.chat.data.CollabException
import com.aird.chat.data.CollabRepository
import com.aird.chat.data.ListAlarms
import com.aird.chat.model.ListArtifact
import com.aird.chat.model.ListDraft
import com.aird.chat.model.ListEntry
import com.aird.chat.model.ListGroup
import com.aird.chat.model.ListSummary
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
    var lists by remember { mutableStateOf(listOf<ListSummary>()) }
    var groups by remember { mutableStateOf(listOf<ListGroup>()) }
    var query by remember { mutableStateOf("") }
    var openId by remember { mutableStateOf("") }
    val slots by CollabRepository.slots.collectAsState()
    val detail = slots.values.firstOrNull { it.artifact?.id == openId }?.artifact as? ListArtifact
    var error by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var grouping by remember { mutableStateOf(false) }
    var share by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ListEntry?>(null) }
    var draft by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var due by remember { mutableStateOf("") }

    fun reload() {
        scope.launch {
            try {
                val board = CollabRepository.listBoard(context, query.trim())
                lists = board.lists
                groups = board.groups
                ListAlarms.sync(context, board.reminders)
                error = ""
            } catch (err: Exception) {
                error = err.message ?: "Could not load lists"
            }
        }
    }

    LaunchedEffect(query) { reload() }
    LaunchedEffect(openId) {
        draft = ""
        note = ""
        due = ""
        if (openId.isNotBlank()) CollabRepository.refresh(context, "list", openId, "")
    }
    BackHandler(enabled = openId.isNotEmpty() || creating || grouping) {
        when {
            creating -> creating = false
            grouping -> grouping = false
            else -> openId = ""
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (openId.isBlank()) "Lists" else detail?.title ?: "List") },
                navigationIcon = {
                    IconButton(onClick = { if (openId.isBlank()) onBack() else openId = "" }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (openId.isNotBlank()) {
                        TextButton(onClick = { share = true }) { Text("Share") }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            if (openId.isBlank()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Find a list or a note") },
                )
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val sections = groups.map { group -> group to lists.filter { it.groupId == group.id } } +
                        (ListGroup("", "Ungrouped") to lists.filter { it.groupId.isBlank() })
                    sections.forEach { (group, rows) ->
                        item(key = "g:${group.id}") {
                            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(group.name, fontWeight = FontWeight.Bold)
                                if (group.id.isNotEmpty()) {
                                    TextButton(onClick = {
                                        scope.launch {
                                            CollabRepository.deleteGroup(context, group.id)
                                            reload()
                                        }
                                    }) { Text("Remove") }
                                }
                            }
                        }
                        items(rows, key = { it.id }) { row ->
                            Column(
                                Modifier.fillMaxWidth().clickable { openId = row.id }.padding(vertical = 8.dp),
                            ) {
                                Text(row.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOf(
                                        if (row.style == "bullet") "Notes" else "To-do",
                                        if (row.total > 0) "${row.done}/${row.total}" else "",
                                        friendlyTime(row.nextDue),
                                    ).filter { it.isNotBlank() }.joinToString(" · "),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                                )
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { grouping = true }) { Text("New group") }
                    TextButton(onClick = { creating = true }) { Text("New list") }
                }
            } else {
                val list = detail
                if (list == null) {
                    Text("Opening…")
                } else {
                    if (list.notes.isNotBlank()) Text(list.notes, modifier = Modifier.padding(bottom = 8.dp))
                    if (groups.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                scope.launch {
                                    CollabRepository.placeList(context, list.id, "")
                                    CollabRepository.refresh(context, "list", list.id, "")
                                    reload()
                                }
                            }) { Text(if (list.groupId.isBlank()) "Ungrouped" else "Clear group") }
                            groups.forEach { group ->
                                TextButton(onClick = {
                                    scope.launch {
                                        CollabRepository.placeList(context, list.id, group.id)
                                        CollabRepository.refresh(context, "list", list.id, "")
                                        reload()
                                    }
                                }) { Text(if (list.groupId == group.id) "• ${group.name}" else group.name) }
                            }
                        }
                    }
                    LazyColumn(Modifier.weight(1f)) {
                        items(list.items, key = { it.id }) { item ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (list.checklist) {
                                    Checkbox(checked = item.done, onCheckedChange = { checked ->
                                        scope.launch {
                                            CollabRepository.setDone(context, list, item, checked)
                                            reload()
                                        }
                                    })
                                }
                                Column(Modifier.weight(1f).clickable { editing = item }.padding(vertical = 6.dp)) {
                                    Text(item.text)
                                    val line = listOf(friendlyTime(item.dueAt), item.notes).filter { it.isNotBlank() }
                                    if (line.isNotEmpty()) {
                                        Text(line.joinToString(" · "), fontSize = 12.sp, maxLines = 3)
                                    }
                                }
                                IconButton(onClick = {
                                    scope.launch {
                                        val ids = list.items.map { it.id }.toMutableList()
                                        val index = ids.indexOf(item.id)
                                        if (index > 0) {
                                            val previous = ids[index - 1]
                                            ids[index - 1] = ids[index]
                                            ids[index] = previous
                                            CollabRepository.reorder(context, list, ids)
                                        }
                                    }
                                }) { Text("Up", fontSize = 12.sp) }
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Add an item") },
                            singleLine = true,
                        )
                        IconButton(onClick = { pickDateTime(context) { due = it } }) { Text("Due") }
                        IconButton(onClick = {
                            scope.launch {
                                CollabRepository.addItem(context, list, draft, notes = note, dueAt = due)
                                draft = ""
                                note = ""
                                due = ""
                                reload()
                            }
                        }) { Icon(Icons.Default.Add, contentDescription = "Add item") }
                    }
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Note") },
                    )
                }
            }
        }
    }

    if (creating) {
        ListCreateDialog(groups, onDismiss = { creating = false }) { title, notes, style, groupId ->
            scope.launch {
                CollabRepository.createList(context, ListDraft(title, style, emptyList(), false, notes, groupId))
                creating = false
                reload()
            }
        }
    }
    if (grouping) {
        GroupNameDialog(onDismiss = { grouping = false }) { name ->
            scope.launch {
                CollabRepository.createGroup(context, name)
                grouping = false
                reload()
            }
        }
    }
    if (share && detail != null) {
        ShareCollabDialog("list", detail!!.id, onDismiss = { share = false })
    }
    editing?.let { item ->
        val list = detail ?: return@let
        ItemEditDialog(item, onDismiss = { editing = null }) { text, notes, due ->
            scope.launch {
                CollabRepository.updateItem(context, list, item, text, item.labels, notes, due)
                editing = null
                reload()
            }
        }
    }
}

@Composable
private fun GroupNameDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New group") },
        text = { OutlinedTextField(name, { name = it }, placeholder = { Text("Home") }) },
        confirmButton = { TextButton(onClick = { onCreate(name) }) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ListCreateDialog(
    groups: List<ListGroup>,
    onDismiss: () -> Unit,
    onCreate: (String, String, String, String) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var checklist by remember { mutableStateOf(true) }
    var groupId by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New list") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, placeholder = { Text("Title") })
                OutlinedTextField(notes, { notes = it }, placeholder = { Text("Description") })
                TextButton(onClick = { checklist = !checklist }) {
                    Text(if (checklist) "To-do" else "Notes")
                }
                groups.forEach { group ->
                    TextButton(onClick = { groupId = group.id }) {
                        Text(if (groupId == group.id) "• ${group.name}" else group.name)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(title, notes, if (checklist) "todo" else "bullet", groupId) }) { Text("Create") } },
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
        title = { Text("Edit item") },
        text = {
            Column {
                OutlinedTextField(text, { text = it }, placeholder = { Text("Item") })
                OutlinedTextField(notes, { notes = it }, placeholder = { Text("Note") })
                TextButton(onClick = { pickDateTime(context) { due = it } }) {
                    Text(if (due.isBlank()) "Set due" else friendlyTime(due))
                }
                if (due.isNotBlank()) TextButton(onClick = { due = "" }) { Text("Clear due") }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text, notes, due) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun ShareCollabDialog(kind: String, id: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val chats = ChatRepository.conversations.value
    var message by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to a chat") },
        text = {
            Column {
                if (message.isNotEmpty()) Text(message)
                chats.forEach { chat ->
                    Text(
                        chat.name(),
                        modifier = Modifier.fillMaxWidth().clickable {
                            scope.launch {
                                try {
                                    CollabRepository.shareIntoChat(context, chat.id, kind, id)
                                    onDismiss()
                                } catch (err: CollabException) {
                                    message = err.message ?: "Could not share"
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
    if (iso.isBlank()) return ""
    return try {
        DateTimeFormatter.ofPattern("d MMM, HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(Instant.parse(iso))
    } catch (_: Exception) {
        iso.take(16).replace('T', ' ')
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
