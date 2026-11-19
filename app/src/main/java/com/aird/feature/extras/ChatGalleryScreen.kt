package com.aird.feature.extras

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.aird.chat.model.ChatAttachment
import com.aird.chat.ui.openChatAttachment
import com.aird.data.db.LocalStore
import com.aird.data.db.MessageEntity
import com.aird.data.db.attachmentsOf
import com.aird.data.db.parseArray
import com.aird.data.net.AirdImages
import com.aird.designsystem.components.AirdLargeTopBar
import com.aird.designsystem.components.BackButton
import com.aird.designsystem.components.EmptyState
import com.aird.designsystem.components.SegmentedGroup
import com.aird.designsystem.components.SettingRow
import com.aird.designsystem.theme.Spacing
import com.aird.session.AirdHttp
import com.aird.session.Session
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

private data class MediaItem(val messageId: String, val attachment: ChatAttachment)
private data class LinkItem(val messageId: String, val url: String, val preview: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatGalleryScreen(conversationId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val me by Session.username.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var attachFlow by remember { mutableStateOf(flowOf(emptyList<MessageEntity>())) }
    var linkFlow by remember { mutableStateOf(flowOf(emptyList<MessageEntity>())) }
    LaunchedEffect(me, conversationId) {
        val db = LocalStore.db(context, me)
        if (db == null) {
            attachFlow = flowOf(emptyList())
            linkFlow = flowOf(emptyList())
        } else {
            attachFlow = db.messages().withAttachments(conversationId)
            linkFlow = db.messages().withLinks(conversationId)
        }
    }
    val attached by attachFlow.collectAsStateWithLifecycle(emptyList())
    val linked by linkFlow.collectAsStateWithLifecycle(emptyList())
    val media = remember(attached) {
        attached.flatMap { msg ->
            attachmentsOf(parseArray(msg.attachments))
                .filter { it.visual() || it.video() }
                .map { MediaItem(msg.id, it) }
        }
    }
    val files = remember(attached) {
        attached.flatMap { msg ->
            attachmentsOf(parseArray(msg.attachments))
                .filterNot { it.visual() || it.video() }
                .map { MediaItem(msg.id, it) }
        }
    }
    val links = remember(linked) {
        linked.flatMap { msg ->
            extractUrls(msg.body).map { LinkItem(msg.id, it, msg.body.take(80)) }
        }.distinctBy { it.url }
    }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            AirdLargeTopBar(title = "Media, files & links", scrollBehavior = scroll, navigationIcon = { BackButton(onClick = onBack) })
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Media") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Files") })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Links") })
            }
            when (tab) {
                0 -> if (media.isEmpty()) {
                    EmptyState(Icons.Rounded.PhotoLibrary, "No media", body = "Images and videos from this chat appear here.")
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(110.dp),
                        contentPadding = PaddingValues(Spacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(media, key = { "${it.messageId}:${it.attachment.index}" }) { item ->
                            val url = AirdHttp.absolute(context, item.attachment.url)
                            AsyncImage(
                                model = ImageRequest.Builder(context).data(url).crossfade(true).build(),
                                imageLoader = AirdImages.stillLoader(context),
                                contentDescription = item.attachment.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .clickable {
                                        scope.launch {
                                            val opened = openChatAttachment(context, item.messageId, item.attachment)
                                            if (!opened) {
                                                try {
                                                    context.startActivity(
                                                        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                                    )
                                                } catch (_: Exception) {
                                                }
                                            }
                                        }
                                    },
                            )
                        }
                    }
                }
                1 -> if (files.isEmpty()) {
                    EmptyState(Icons.Rounded.InsertDriveFile, "No files")
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 48.dp)) {
                        item {
                            SegmentedGroup {
                                files.forEach { item ->
                                    SettingRow(
                                        title = item.attachment.name.ifBlank { "File" },
                                        icon = Icons.Rounded.InsertDriveFile,
                                        chevron = true,
                                        onClick = {
                                            scope.launch { openChatAttachment(context, item.messageId, item.attachment) }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                else -> if (links.isEmpty()) {
                    EmptyState(Icons.Rounded.Link, "No links")
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 48.dp)) {
                        items(links, key = { it.url }) { item ->
                            SegmentedGroup(Modifier.padding(bottom = Spacing.xs)) {
                                SettingRow(
                                    title = item.url,
                                    subtitle = item.preview,
                                    icon = Icons.Rounded.Link,
                                    chevron = true,
                                    onClick = {
                                        try {
                                            context.startActivity(
                                                Intent(Intent.ACTION_VIEW, Uri.parse(item.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                            )
                                        } catch (_: Exception) {
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private val urlRegex = Regex("""https?://[^\s<>"')\]]+""", RegexOption.IGNORE_CASE)

private fun extractUrls(body: String): List<String> =
    urlRegex.findAll(body).map { it.value.trimEnd('.', ',', ';', ')') }.distinct().toList()
