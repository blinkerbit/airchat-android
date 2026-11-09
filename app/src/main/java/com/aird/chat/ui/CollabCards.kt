package com.aird.chat.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.aird.call.CallController
import com.aird.call.CallPhase
import com.aird.chat.data.CollabRepository
import com.aird.chat.data.CollabSlot
import com.aird.chat.model.CollabArtifact
import com.aird.chat.model.CollabRef
import com.aird.chat.model.ListArtifact
import com.aird.chat.model.ListEntry
import com.aird.chat.model.MeetArtifact
import com.aird.chat.model.PollArtifact
import com.aird.chat.model.collabInactiveLabel
import com.aird.chat.model.collabKindLabel
import com.aird.chat.model.collabToken
import com.aird.chat.model.meetExpiryLabel
import com.aird.chat.model.toDraft
import com.aird.session.AirdHttp
import com.aird.session.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun CollabShelf(refs: List<CollabRef>) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(refs, key = { "${it.kind}:${it.id}" }) { ref ->
            val label = collabKindLabel(ref.kind)
            val name = ref.title.ifBlank { ref.token }
            val text = if (ref.active) "$label  $name" else "$label  $name · ${collabInactiveLabel(ref.kind)}"
            Text(
                text,
                modifier = Modifier
                    .alpha(if (ref.active) 1f else 0.45f)
                    .widthIn(max = 220.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(999.dp))
                    .clickable { CollabRepository.open(ref.kind, ref.id, ref.slug) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
fun CollabFocusSheet() {
    val focus by CollabRepository.focus.collectAsState()
    val current = focus ?: return
    CollabFocusSheetBody(current.kind, current.id, current.slug)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CollabFocusSheetBody(kind: String, id: String, slug: String) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = { CollabRepository.close() }, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
        ) {
            CollabSurface(kind, id, slug, fallback = "", onPrimary = false, expanded = true)
        }
    }
}

@Composable
fun CollabInline(kind: String, id: String, slug: String, fallback: String, onPrimary: Boolean) {
    CollabSurface(kind, id, slug, fallback, onPrimary, expanded = false)
}

@Composable
private fun CollabSurface(
    kind: String,
    id: String,
    slug: String,
    fallback: String,
    onPrimary: Boolean,
    expanded: Boolean,
) {
    val slot = rememberCollab(kind, id, slug, force = expanded)
    val artifact = slot.artifact
    val fg = if (onPrimary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val muted = fg.copy(alpha = 0.72f)
    val accent = if (onPrimary) Color.White else MaterialTheme.colorScheme.primary
    val frame = if (expanded) {
        Modifier.fillMaxWidth()
    } else {
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = if (onPrimary) 0.18f else 0.04f))
            .padding(10.dp)
    }
    Column(if (artifact?.active == false) frame.alpha(0.5f) else frame) {
        if (artifact == null) {
            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                if (slot.loading || slot.error.isBlank()) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = accent)
                } else {
                    Text(slot.error.ifBlank { fallback.ifBlank { "Could not load" } }, color = fg, fontSize = 13.sp)
                }
            }
            return
        }
        var editing by remember(artifact.id) { mutableStateOf(false) }
        if (expanded && editing) {
            ArtifactEditor(artifact, onDone = { editing = false })
            return
        }
        val me by Session.username.collectAsState()
        val mine = artifact.organizer.equals(me, true)
        Text(collabKindLabel(artifact.type).uppercase(), color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(artifact.heading.ifBlank { fallback }, color = fg, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Text(artifact.meta(), color = muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
        when (artifact) {
            is PollArtifact -> PollBody(artifact, slot, fg, accent, onPrimary)
            is ListArtifact -> ListBody(artifact, slot, expanded, fg, accent, onPrimary)
            is MeetArtifact -> MeetBody(artifact, fg, accent, onPrimary)
        }
        if (expanded && artifact.slug.isNotBlank()) {
            val context = LocalContext.current
            TextButton(onClick = { copyText(context, "Mention", collabToken(artifact.type, artifact.slug)) }) {
                Text("Copy mention")
            }
        }
        if (expanded && mine) {
            TextButton(onClick = { editing = true }) { Text("Edit") }
        }
        if (!expanded) {
            Text(
                "Full view",
                color = accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable { CollabRepository.open(artifact.type, artifact.id, artifact.slug) }
                    .padding(top = 6.dp),
            )
        }
        if (slot.error.isNotBlank()) {
            Text(slot.error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun rememberCollab(kind: String, id: String, slug: String, force: Boolean): CollabSlot {
    val context = LocalContext.current
    LaunchedEffect(kind, id, slug, force) {
        if (id.isBlank() && slug.isBlank()) return@LaunchedEffect
        if (force) CollabRepository.refresh(context, kind, id, slug) else CollabRepository.ensure(context, kind, id, slug)
    }
    val slots by CollabRepository.slots.collectAsState()
    return slots[CollabRepository.slotKey(kind, id, slug)] ?: CollabSlot(loading = id.isNotBlank() || slug.isNotBlank())
}

@Composable
private fun PollBody(poll: PollArtifact, slot: CollabSlot, fg: Color, accent: Color, onPrimary: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mineKey = poll.options.joinToString { "${it.id}:${it.mine}:${it.votes}" }
    var picked by remember(poll.id, mineKey) { mutableStateOf(poll.options.filter { it.mine }.map { it.id }.toSet()) }
    var hint by remember(poll.id) { mutableStateOf("") }
    val maxVotes = poll.options.maxOfOrNull { it.votes }?.coerceAtLeast(1) ?: 1
    val track = if (onPrimary) Color.White.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant
    val fill = if (onPrimary) Color.White.copy(alpha = 0.45f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
    poll.options.forEach { option ->
        val selected = if (poll.allowMulti) option.id in picked else option.mine
        val fraction = option.votes.toFloat() / maxVotes.toFloat()
        Box(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp)
                .height(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(track)
                .border(1.dp, if (selected) accent else Color.Transparent, RoundedCornerShape(10.dp))
                .clickable(enabled = !slot.busy && poll.active) {
                    if (poll.allowMulti) {
                        picked = if (option.id in picked) picked - option.id else picked + option.id
                        hint = ""
                    } else {
                        scope.collab { CollabRepository.vote(context, poll, listOf(option.id)) }
                    }
                },
        ) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(fill))
            Row(
                Modifier.fillMaxSize().padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(option.label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, color = fg, fontSize = 14.sp)
                Text(option.votes.toString(), color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
    if (poll.allowMulti) {
        if (hint.isNotBlank()) Text(hint, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        TextButton(
            onClick = {
                if (picked.isEmpty()) {
                    hint = "Pick at least one option."
                } else {
                    hint = ""
                    scope.collab { CollabRepository.vote(context, poll, picked.toList()) }
                }
            },
            enabled = !slot.busy && poll.active,
        ) { Text("Save vote", color = accent) }
    }
}

@Composable
private fun ListBody(
    list: ListArtifact,
    slot: CollabSlot,
    expanded: Boolean,
    fg: Color,
    accent: Color,
    onPrimary: Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember(list.id) { mutableStateOf("") }
    var labels by remember(list.id) { mutableStateOf("") }
    var editing by remember(list.id) { mutableStateOf<ListEntry?>(null) }
    val shown = if (expanded) list.items else list.items.take(6)
    if (list.checklist && list.total > 0) {
        LinearProgressIndicator(
            progress = { list.done.toFloat() / list.total.toFloat() },
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp).height(4.dp).clip(RoundedCornerShape(99.dp)),
            color = accent,
            trackColor = accent.copy(alpha = 0.2f),
        )
    }
    if (shown.isEmpty()) Text("No items yet.", color = fg.copy(alpha = 0.7f), fontSize = 13.sp)
    shown.forEach { item ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (list.checklist) {
                Checkbox(
                    checked = item.done,
                    onCheckedChange = { checked ->
                        scope.collab { CollabRepository.setDone(context, list, item, checked) }
                    },
                    enabled = !slot.busy,
                    colors = CheckboxDefaults.colors(
                        checkedColor = accent,
                        uncheckedColor = fg.copy(alpha = 0.6f),
                        checkmarkColor = if (onPrimary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onPrimary,
                    ),
                )
            } else {
                Text("•", color = accent, modifier = Modifier.padding(horizontal = 10.dp))
            }
            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                Text(
                    item.text,
                    color = fg,
                    fontSize = 14.sp,
                    textDecoration = if (item.done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                    modifier = if (expanded) Modifier.clickable { editing = item } else Modifier,
                )
                val dueText = friendlyTime(item.dueAt)
                val noteText = if (item.notes.equals("null", ignoreCase = true)) "" else item.notes.lineSequence().firstOrNull().orEmpty()
                val extra = listOf(dueText, noteText).filter { it.isNotBlank() }
                if (extra.isNotEmpty()) {
                    Text(extra.joinToString(" · "), color = fg.copy(alpha = 0.65f), fontSize = 11.sp, maxLines = 2)
                }
                if (item.labels.isNotEmpty()) {
                    Text(item.labels.joinToString(" · "), color = fg.copy(alpha = 0.65f), fontSize = 11.sp)
                }
            }
            if (expanded) {
                IconButton(onClick = { scope.collab { CollabRepository.deleteItem(context, list, item.id) } }, enabled = !slot.busy) {
                    Icon(Icons.Default.Close, contentDescription = "Remove item", tint = fg.copy(alpha = 0.7f))
                }
            }
        }
    }
    if (!expanded && list.items.size > shown.size) {
        Text("${list.items.size - shown.size} more in full view", color = fg.copy(alpha = 0.7f), fontSize = 12.sp)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Add an item") },
            singleLine = true,
        )
        IconButton(
            onClick = {
                val text = draft
                val tags = if (expanded) labels.split(',') else emptyList()
                scope.collab {
                    CollabRepository.addItem(context, list, text, tags)
                    draft = ""
                    labels = ""
                }
            },
            enabled = draft.isNotBlank() && !slot.busy,
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add item", tint = accent)
        }
    }
    if (expanded) {
        OutlinedTextField(
            value = labels,
            onValueChange = { labels = it },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            placeholder = { Text("Labels, comma separated") },
            singleLine = true,
        )
    }
    editing?.let { item ->
        ItemEditor(item, onDismiss = { editing = null }) { text, tags ->
            scope.collab {
                CollabRepository.updateItem(context, list, item, text, tags)
                editing = null
            }
        }
    }
}

@Composable
private fun ItemEditor(item: ListEntry, onDismiss: () -> Unit, onSave: (String, List<String>) -> Unit) {
    var text by remember(item.id) { mutableStateOf(item.text) }
    var labels by remember(item.id) { mutableStateOf(item.labels.joinToString(", ")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit item") },
        text = {
            Column {
                OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Text") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = labels,
                    onValueChange = { labels = it },
                    label = { Text("Labels") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text, labels.split(',')) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun MeetBody(meet: MeetArtifact, fg: Color, accent: Color, onPrimary: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var copied by remember(meet.id) { mutableStateOf("") }
    var confirmEnd by remember(meet.id) { mutableStateOf(false) }
    val me by Session.username.collectAsState()
    if (meet.live) {
        MeetJoinButton(meet, onPrimary)
    } else {
        Text("This meeting has ended.", color = fg.copy(alpha = 0.75f), fontSize = 13.sp)
    }
    Row {
        TextButton(onClick = {
            val path = meet.joinPath.ifBlank { "/meet/${meet.slug}" }
            copyText(context, "Meeting link", AirdHttp.absolute(context, path))
            copied = "Link copied"
        }) { Text("Copy link", color = accent) }
    }
    if (copied.isNotBlank()) Text(copied, color = fg.copy(alpha = 0.7f), fontSize = 12.sp)
    if (meet.organizer.equals(me, true) && meet.live) {
        TextButton(onClick = { confirmEnd = true }) { Text("End meeting", color = MaterialTheme.colorScheme.error) }
    }
    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text("End meeting") },
            text = { Text("People will no longer be able to join.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmEnd = false
                    scope.collab { CollabRepository.endMeet(context, meet) }
                }) { Text("End") }
            },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MeetJoinButton(meet: MeetArtifact, onPrimary: Boolean) {
    val context = LocalContext.current
    var error by remember(meet.id) { mutableStateOf("") }
    fun start() {
        if (CallController.snapshot.value.phase != CallPhase.Idle) {
            error = "Finish your current call first."
            return
        }
        if (!CallController.joinMeet(context, meet.id, meet.heading)) error = "Finish your current call first."
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val mic = granted[Manifest.permission.RECORD_AUDIO] == true || CallController.hasMicrophone(context)
        val camera = granted[Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!mic && !camera) error = "Allow the microphone or camera to join." else start()
    }
    Button(
        onClick = {
            error = ""
            val need = buildList {
                if (!CallController.hasMicrophone(context)) add(Manifest.permission.RECORD_AUDIO)
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    add(Manifest.permission.CAMERA)
                }
            }
            if (need.isEmpty()) start() else permission.launch(need.toTypedArray())
        },
        modifier = Modifier.padding(top = 4.dp),
        colors = if (onPrimary) {
            ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = MaterialTheme.colorScheme.primary)
        } else {
            ButtonDefaults.buttonColors()
        },
    ) { Text("Join") }
    if (error.isNotBlank()) {
        Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun ArtifactEditor(artifact: CollabArtifact, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember(artifact.id) { mutableStateOf(false) }
    var error by remember(artifact.id) { mutableStateOf("") }
    fun run(block: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            error = ""
            try {
                block()
                onDone()
            } catch (err: CancellationException) {
                throw err
            } catch (err: Exception) {
                error = err.message ?: "Could not save"
            } finally {
                busy = false
            }
        }
    }
    when (artifact) {
        is PollArtifact -> PollFields(
            resetKey = artifact.id,
            initial = artifact.toDraft(),
            submitLabel = "Save poll",
            note = "Changing the question or the options clears every vote.",
            busy = busy,
            error = error,
            onSubmit = { draft -> run { CollabRepository.updatePoll(context, artifact, draft) } },
        )
        is ListArtifact -> ListFields(
            resetKey = artifact.id,
            initial = artifact.toDraft(),
            submitLabel = "Save list",
            note = "",
            busy = busy,
            error = error,
            showItems = false,
            onSubmit = { draft -> run { CollabRepository.updateList(context, artifact, draft) } },
        )
        is MeetArtifact -> MeetFields(
            resetKey = artifact.id,
            initial = artifact.toDraft(),
            submitLabel = "Save meeting",
            note = "The join link stays live for 48 hours.",
            busy = busy,
            error = error,
            onSubmit = { draft -> run { CollabRepository.updateMeet(context, artifact, draft) } },
        )
    }
    TextButton(onClick = onDone, modifier = Modifier.padding(start = 8.dp)) { Text("Cancel") }
}

private fun CollabArtifact.meta(): String {
    val access = if (privateShare) "Private" else "Public"
    return when (this) {
        is PollArtifact -> "${if (active) "" else "Closed · "}${totalVotes} votes · ${if (allowMulti) "Multiple · " else ""}$access"
        is ListArtifact -> "${if (active) "" else "Done · "}$done/$total · ${if (checklist) "Checklist" else "Bullets"} · $access"
        is MeetArtifact -> {
            val whenLabel = if (!live) "Ended" else meetExpiryLabel(expiresAt).ifBlank { "Live" }
            "$whenLabel · $access"
        }
    }
}

private fun copyText(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
}

private fun CoroutineScope.collab(block: suspend () -> Unit) = launch {
    try {
        block()
    } catch (err: CancellationException) {
        throw err
    } catch (_: Exception) {
    }
}
