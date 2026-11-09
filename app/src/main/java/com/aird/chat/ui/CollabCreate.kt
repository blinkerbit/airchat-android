package com.aird.chat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Poll
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aird.chat.data.CollabException
import com.aird.chat.data.CollabRepository
import com.aird.chat.model.ListDraft
import com.aird.chat.model.MeetDraft
import com.aird.chat.model.PollDraft
import com.aird.ui.LocalAirdReducedMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun PlusPanel(
    onClose: () -> Unit,
    onPhotos: () -> Unit,
    onTakePhoto: () -> Unit,
    onRecordVideo: () -> Unit,
    onPickDevice: () -> Unit,
    onPickAird: () -> Unit,
    onToken: (String) -> Unit,
) {
    var page by remember { mutableStateOf("menu") }
    val reduced = LocalAirdReducedMotion.current
    val done: (String) -> Unit = { token ->
        onToken(token)
        onClose()
    }
    BackHandler(enabled = page != "menu") { page = "menu" }
    AnimatedContent(
        targetState = page,
        transitionSpec = {
            if (reduced) {
                EnterTransition.None togetherWith ExitTransition.None
            } else if (targetState == "menu") {
                (fadeIn(tween(180)) + slideInHorizontally { -it / 5 }) togetherWith
                    (fadeOut(tween(140)) + slideOutHorizontally { it / 5 })
            } else {
                (fadeIn(tween(180)) + slideInHorizontally { it / 5 }) togetherWith
                    (fadeOut(tween(140)) + slideOutHorizontally { -it / 5 })
            }
        },
        modifier = Modifier.fillMaxWidth(),
        label = "plus-panel",
    ) { current ->
        when (current) {
            "attach" -> AttachPage(
                onBack = { page = "menu" },
                onPick = { action ->
                    onClose()
                    action()
                },
                onPhotos = onPhotos,
                onTakePhoto = onTakePhoto,
                onRecordVideo = onRecordVideo,
                onPickDevice = onPickDevice,
                onPickAird = onPickAird,
            )
            "poll" -> CreatePollPage(onBack = { page = "menu" }, onDone = done)
            "list" -> CreateListPage(onBack = { page = "menu" }, onDone = done)
            "meet" -> CreateMeetPage(onBack = { page = "menu" }, onDone = done)
            else -> MenuPage { page = it }
        }
    }
}

@Composable
private fun MenuPage(onPick: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        PlusTile(Icons.Default.AttachFile, "Attach", Modifier.weight(1f)) { onPick("attach") }
        PlusTile(Icons.Default.Poll, "Poll", Modifier.weight(1f)) { onPick("poll") }
        PlusTile(Icons.Default.Checklist, "List", Modifier.weight(1f)) { onPick("list") }
        PlusTile(Icons.Default.Videocam, "Meet", Modifier.weight(1f)) { onPick("meet") }
    }
}

@Composable
private fun PlusTile(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.primary)
        }
        Text(label, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun AttachPage(
    onBack: () -> Unit,
    onPick: (() -> Unit) -> Unit,
    onPhotos: () -> Unit,
    onTakePhoto: () -> Unit,
    onRecordVideo: () -> Unit,
    onPickDevice: () -> Unit,
    onPickAird: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        PanelHead("Attach", onBack)
        AttachRow("Photos and videos") { onPick(onPhotos) }
        AttachRow("Take photo") { onPick(onTakePhoto) }
        AttachRow("Record video") { onPick(onRecordVideo) }
        AttachRow("From this device") { onPick(onPickDevice) }
        AttachRow("From Aird") { onPick(onPickAird) }
    }
}

@Composable
private fun AttachRow(label: String, onClick: () -> Unit) {
    Text(
        label,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        fontSize = 16.sp,
    )
}

private class FormRun {
    var busy by mutableStateOf(false)
    var error by mutableStateOf("")
}

@Composable
private fun rememberForm(): FormRun = remember { FormRun() }

private fun kotlinx.coroutines.CoroutineScope.save(
    form: FormRun,
    onDone: (String) -> Unit,
    block: suspend () -> String,
) {
    if (form.busy) return
    launch {
        form.busy = true
        form.error = ""
        try {
            onDone(block())
        } catch (err: CancellationException) {
            throw err
        } catch (err: Exception) {
            form.error = if (err is CollabException) err.message ?: "Could not save" else "Could not reach the server"
        } finally {
            form.busy = false
        }
    }
}

@Composable
private fun CreatePollPage(onBack: () -> Unit, onDone: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val form = rememberForm()
    FormColumn("New poll", onBack) {
        PollFields(
            resetKey = "new-poll",
            initial = PollDraft("", listOf("", ""), false, false),
            submitLabel = "Create poll",
            note = "People in this chat can vote from the message.",
            busy = form.busy,
            error = form.error,
            onSubmit = { draft -> scope.save(form, onDone) { CollabRepository.createPoll(context, draft) } },
        )
    }
}

@Composable
private fun CreateListPage(onBack: () -> Unit, onDone: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val form = rememberForm()
    FormColumn("New list", onBack) {
        ListFields(
            resetKey = "new-list",
            initial = ListDraft("", "todo", emptyList(), false),
            submitLabel = "Create list",
            note = "",
            busy = form.busy,
            error = form.error,
            showItems = true,
            onSubmit = { draft -> scope.save(form, onDone) { CollabRepository.createList(context, draft) } },
        )
    }
}

@Composable
private fun CreateMeetPage(onBack: () -> Unit, onDone: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val form = rememberForm()
    FormColumn("New meeting", onBack) {
        MeetFields(
            resetKey = "new-meet",
            initial = MeetDraft("", false),
            submitLabel = "Create meeting",
            note = "The join link stays live for 48 hours.",
            busy = form.busy,
            error = form.error,
            onSubmit = { draft -> scope.save(form, onDone) { CollabRepository.createMeet(context, draft) } },
        )
    }
}

@Composable
private fun FormColumn(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()).padding(bottom = 8.dp),
    ) {
        PanelHead(title, onBack)
        content()
    }
}

@Composable
internal fun PanelHead(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

@Composable
internal fun PollFields(
    resetKey: Any,
    initial: PollDraft,
    submitLabel: String,
    note: String,
    busy: Boolean,
    error: String,
    onSubmit: (PollDraft) -> Unit,
) {
    var question by remember(resetKey) { mutableStateOf(initial.question) }
    val options = remember(resetKey) { mutableStateListOf<String>().apply { addAll(initial.options.ifEmpty { listOf("", "") }) } }
    var multi by remember(resetKey) { mutableStateOf(initial.allowMulti) }
    var privateShare by remember(resetKey) { mutableStateOf(initial.privateShare) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (note.isNotBlank()) Text(note, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
        OutlinedTextField(
            value = question,
            onValueChange = { question = it },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            label = { Text("Question") },
            minLines = 2,
        )
        Text("Options", modifier = Modifier.padding(top = 10.dp, bottom = 4.dp), fontSize = 13.sp)
        options.forEachIndexed { index, value ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { options[index] = it },
                    modifier = Modifier.weight(1f).padding(bottom = 6.dp),
                    placeholder = { Text("Option ${index + 1}") },
                    singleLine = true,
                )
                if (options.size > 2) {
                    IconButton(onClick = { options.removeAt(index) }) {
                        Icon(Icons.Default.Close, contentDescription = "Remove option")
                    }
                }
            }
        }
        if (options.size < 12) {
            TextButton(onClick = { options.add("") }) { Text("Add option") }
        }
        PrivateCheck(privateShare) { privateShare = it }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { multi = !multi }) {
            Checkbox(checked = multi, onCheckedChange = { multi = it })
            Text("Allow more than one choice", fontSize = 14.sp)
        }
        SubmitRow(submitLabel, busy, error) {
            onSubmit(PollDraft(question, options.toList(), multi, privateShare))
        }
    }
}

@Composable
internal fun ListFields(
    resetKey: Any,
    initial: ListDraft,
    submitLabel: String,
    note: String,
    busy: Boolean,
    error: String,
    showItems: Boolean,
    onSubmit: (ListDraft) -> Unit,
) {
    var title by remember(resetKey) { mutableStateOf(initial.title) }
    var style by remember(resetKey) { mutableStateOf(if (initial.style == "bullet") "bullet" else "todo") }
    var items by remember(resetKey) { mutableStateOf(initial.items.joinToString("\n")) }
    var privateShare by remember(resetKey) { mutableStateOf(initial.privateShare) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (note.isNotBlank()) Text(note, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            label = { Text("Title") },
            singleLine = true,
        )
        Text("Type", modifier = Modifier.padding(top = 10.dp, bottom = 6.dp), fontSize = 13.sp)
        ChoiceChips(
            options = listOf("todo" to "Checklist", "bullet" to "Bullets"),
            value = style,
            onChange = { style = it },
        )
        if (showItems) {
            OutlinedTextField(
                value = items,
                onValueChange = { items = it },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                label = { Text("Items") },
                placeholder = { Text("One item per line") },
                minLines = 3,
            )
        }
        PrivateCheck(privateShare) { privateShare = it }
        SubmitRow(submitLabel, busy, error) {
            onSubmit(ListDraft(title, style, if (showItems) items.lines() else emptyList(), privateShare))
        }
    }
}

@Composable
internal fun MeetFields(
    resetKey: Any,
    initial: MeetDraft,
    submitLabel: String,
    note: String,
    busy: Boolean,
    error: String,
    onSubmit: (MeetDraft) -> Unit,
) {
    var title by remember(resetKey) { mutableStateOf(initial.title) }
    var privateShare by remember(resetKey) { mutableStateOf(initial.privateShare) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (note.isNotBlank()) {
            Text(note, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f), modifier = Modifier.padding(top = 4.dp))
        }
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            label = { Text("Title") },
            placeholder = { Text("Team sync") },
            singleLine = true,
        )
        PrivateCheck(privateShare) { privateShare = it }
        SubmitRow(submitLabel, busy, error) { onSubmit(MeetDraft(title, privateShare)) }
    }
}

@Composable
private fun PrivateCheck(checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(top = 6.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text("Private to this chat", fontSize = 14.sp)
    }
}

@Composable
internal fun ChoiceChips(options: List<Pair<String, String>>, value: String, onChange: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (id, label) ->
            val selected = value == id
            Text(
                label,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onChange(id) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun SubmitRow(label: String, busy: Boolean, error: String, onClick: () -> Unit) {
    if (error.isNotBlank()) {
        Text(error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
    }
    Button(
        onClick = onClick,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
        } else {
            Text(label)
        }
    }
}
