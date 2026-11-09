package com.aird.chat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aird.chat.data.CollabRepository
import com.aird.chat.model.PollArtifact
import com.aird.chat.model.PollDraft
import com.aird.chat.model.PollSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AirdPollsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var polls by remember { mutableStateOf(listOf<PollSummary>()) }
    var openId by remember { mutableStateOf("") }
    val slots by CollabRepository.slots.collectAsState()
    val detail = slots.values.firstOrNull { it.artifact?.id == openId }?.artifact as? PollArtifact
    var error by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var share by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf(setOf<String>()) }

    fun reload() {
        scope.launch {
            try {
                polls = CollabRepository.pollBoard(context).distinctBy { it.id }
                error = ""
            } catch (err: CancellationException) {
                throw err
            } catch (err: Exception) {
                error = err.message ?: "Could not load polls"
            }
        }
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

    LaunchedEffect(Unit) { reload() }
    LaunchedEffect(openId) {
        if (openId.isBlank()) {
            picked = emptySet()
            return@LaunchedEffect
        }
        CollabRepository.refresh(context, "poll", openId, "")
        val poll = CollabRepository.slots.value.values.firstOrNull { it.artifact?.id == openId }?.artifact as? PollArtifact
        picked = poll?.options?.filter { it.mine }?.map { it.id }?.toSet().orEmpty()
    }
    BackHandler(enabled = openId.isNotEmpty()) { openId = "" }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (openId.isBlank()) "Polls" else detail?.question ?: "Poll") },
                navigationIcon = {
                    IconButton(onClick = { if (openId.isBlank()) onBack() else openId = "" }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (openId.isBlank()) TextButton(onClick = { creating = true }) { Text("New") }
                    else TextButton(onClick = { share = true }) { Text("Share") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            if (error.isNotEmpty()) Text(error, fontSize = 13.sp)
            if (openId.isBlank()) {
                LazyColumn(Modifier.weight(1f)) {
                    items(polls, key = { it.id }) { poll ->
                        Column(Modifier.fillMaxWidth().clickable { openId = poll.id }.padding(vertical = 10.dp)) {
                            Text(poll.question, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${poll.votes} votes${if (poll.active) "" else " · Closed"}",
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            } else {
                val poll = detail
                if (poll == null) Text("Opening…")
                else {
                    val max = poll.options.maxOfOrNull { it.votes }?.coerceAtLeast(1) ?: 1
                    poll.options.forEach { option ->
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = poll.active) {
                                if (poll.allowMulti) {
                                    picked = if (option.id in picked) picked - option.id else picked + option.id
                                } else {
                                    work {
                                        CollabRepository.vote(context, poll, listOf(option.id))
                                        reload()
                                    }
                                }
                            }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (poll.allowMulti) {
                                Checkbox(checked = option.id in picked, onCheckedChange = null, enabled = poll.active)
                            }
                            Column(Modifier.weight(1f)) {
                                Text(option.label)
                                LinearProgressIndicator(
                                    progress = { option.votes / max.toFloat() },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            Text(option.votes.toString(), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                    if (poll.allowMulti && poll.active) {
                        TextButton(onClick = {
                            work {
                                CollabRepository.vote(context, poll, picked.toList())
                                reload()
                            }
                        }) { Text("Save vote") }
                    }
                }
            }
        }
    }
    if (creating) PollCreateDialog(onDismiss = { creating = false }) { question, options, multi ->
        work {
            val id = CollabRepository.createBoardPoll(context, PollDraft(question, options, multi, false))
            creating = false
            reload()
            openId = id
        }
    }
    if (share && detail != null) ShareCollabDialog("poll", detail!!.id, onDismiss = { share = false })
}

@Composable
private fun PollCreateDialog(onDismiss: () -> Unit, onCreate: (String, List<String>, Boolean) -> Unit) {
    var question by remember { mutableStateOf("") }
    var options by remember { mutableStateOf("Yes\nNo") }
    var multi by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New poll") },
        text = {
            Column {
                OutlinedTextField(question, { question = it }, placeholder = { Text("Question") })
                OutlinedTextField(options, { options = it }, placeholder = { Text("One option per line") })
                TextButton(onClick = { multi = !multi }) { Text(if (multi) "Several choices" else "One choice") }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onCreate(question, options.lines().map { it.trim() }.filter { it.isNotEmpty() }, multi)
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
