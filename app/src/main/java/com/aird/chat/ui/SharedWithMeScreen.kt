package com.aird.chat.ui

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aird.chat.data.ChatRepository
import com.aird.chat.model.ChatAttachment
import com.aird.session.AirdHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private data class SharedItem(
    val id: String,
    val name: String,
    val sender: String,
    val url: String,
    val conversationId: String,
    val mediaKind: String,
    val index: Int,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedWithMeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<SharedItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var notice by remember { mutableStateOf("") }
    var noticeError by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<SharedItem?>(null) }
    var opening by remember { mutableStateOf(false) }

    suspend fun reload() {
        loading = true
        try {
            val response = withContext(Dispatchers.IO) {
                AirdHttp.get(context, "/api/chat/shared-with-me")
            }
            if (!response.ok) {
                noticeError = true
                notice = response.json().optString("error").ifBlank { "Could not load shared files" }
                return
            }
            val raw = response.json().optJSONArray("items")
            val next = buildList {
                if (raw == null) return@buildList
                for (i in 0 until raw.length()) {
                    val obj = raw.optJSONObject(i) ?: continue
                    val id = obj.field("id")
                    if (id.isEmpty()) continue
                    add(
                        SharedItem(
                            id = id,
                            name = obj.field("name", "original_name", "relative_path").ifBlank { "file" },
                            sender = obj.field("sender_username", "sender").ifBlank { "unknown" },
                            url = obj.field("url", "file_url", "share_url"),
                            conversationId = obj.field("conversation_id"),
                            mediaKind = obj.field("media_kind").ifBlank { "file" },
                            index = obj.optInt("index", 0),
                        ),
                    )
                }
            }
            items = next
            notice = ""
            noticeError = false
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            noticeError = true
            notice = e.message?.substringBefore('\n')?.ifBlank { null } ?: "Could not load shared files"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Shared with me", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (notice.isNotEmpty()) {
                Text(
                    notice,
                    color = if (noticeError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            when {
                loading && items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Nothing shared with you",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(items, key = { "${it.id}-${it.index}" }) { item ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                Modifier
                                    .weight(1f)
                                    .clickable(enabled = !opening) {
                                        scope.launch {
                                            opening = true
                                            try {
                                                if (item.url.isNotEmpty()) {
                                                    val opened = openChatAttachment(
                                                        context,
                                                        item.id,
                                                        ChatAttachment(
                                                            name = item.name,
                                                            url = item.url,
                                                            mediaKind = item.mediaKind,
                                                            index = item.index,
                                                        ),
                                                    )
                                                    if (!opened) {
                                                        noticeError = true
                                                        notice = "Could not open file"
                                                    }
                                                } else if (item.conversationId.isNotEmpty()) {
                                                    ChatRepository.requestOpen(item.conversationId)
                                                    onBack()
                                                } else {
                                                    noticeError = true
                                                    notice = "Could not open file"
                                                }
                                            } catch (e: kotlinx.coroutines.CancellationException) {
                                                throw e
                                            } catch (e: Exception) {
                                                noticeError = true
                                                notice = e.message?.substringBefore('\n')?.ifBlank { null } ?: "Could not open file"
                                            } finally {
                                                opening = false
                                            }
                                        }
                                    }
                                    .padding(vertical = 8.dp),
                            ) {
                                Text(
                                    item.name,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    item.sender,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            TextButton(onClick = { pendingDelete = item }) { Text("Remove") }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Remove file") },
            text = { Text("Remove this file from Shared with me?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        scope.launch {
                            try {
                                val response = withContext(Dispatchers.IO) {
                                    AirdHttp.send(
                                        context,
                                        "/api/chat/shared-with-me/${Uri.encode(item.id)}",
                                        "DELETE",
                                        JSONObject(),
                                    )
                                }
                                if (!response.ok) {
                                    noticeError = true
                                    notice = response.json().optString("error").ifBlank { "Could not remove file" }
                                    return@launch
                                }
                                items = items.filterNot { it.id == item.id }
                                notice = ""
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                noticeError = true
                                notice = e.message?.substringBefore('\n')?.ifBlank { null } ?: "Could not remove file"
                            }
                        }
                    },
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

private fun JSONObject.field(vararg keys: String): String {
    for (key in keys) {
        if (!has(key) || isNull(key)) continue
        val value = optString(key).trim()
        if (value.isNotEmpty() && value != "null") return value
    }
    return ""
}
