package com.aird.chat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aird.chat.data.AirdEntry
import com.aird.chat.data.ChatRepository

enum class AirdPickMode { Attach, Save }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AirdPathSheet(
    mode: AirdPickMode,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    val context = LocalContext.current
    var path by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<AirdEntry>>(emptyList()) }
    var failed by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(path) {
        val listed = ChatRepository.listFiles(context, path)
        failed = listed == null
        entries = listed.orEmpty()
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(if (mode == AirdPickMode.Save) "Save a copy" else "From Aird", fontWeight = FontWeight.SemiBold)
            Text(
                if (path.isEmpty()) "/" else "/$path",
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (path.isNotEmpty()) {
                TextButton(onClick = {
                    path = path.substringBeforeLast('/', "")
                }) { Text("Up") }
            }
            TextButton(
                onClick = {
                    if (mode == AirdPickMode.Save) onConfirm(listOf(path))
                    else onConfirm(picked.toList())
                },
                enabled = mode == AirdPickMode.Save || picked.isNotEmpty(),
            ) { Text(if (mode == AirdPickMode.Save) "Save here" else "Attach") }
            if (failed) {
                Text("Could not load this folder", modifier = Modifier.padding(bottom = 8.dp))
            }
            LazyColumn(Modifier.fillMaxWidth().height(320.dp)) {
                items(entries, key = { it.path }) { entry ->
                    val selected = entry.path in picked
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (entry.dir) "${entry.name}/" else entry.name,
                            modifier = Modifier.weight(1f).clickable {
                                if (entry.dir) path = entry.path
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (mode == AirdPickMode.Attach) {
                            TextButton(onClick = {
                                picked = if (selected) picked - entry.path else picked + entry.path
                            }) { Text(if (selected) "Selected" else "Select") }
                        }
                    }
                }
            }
        }
    }
}
