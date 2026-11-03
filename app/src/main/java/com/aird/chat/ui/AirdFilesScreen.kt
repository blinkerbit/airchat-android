package com.aird.chat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material3.Button
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aird.chat.data.AirdEntry
import com.aird.chat.data.ChatRepository
import com.aird.chat.model.ChatAttachment
import kotlinx.coroutines.launch
import java.net.URLEncoder

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AirdFilesScreen(
    startPath: String = "",
    onBack: () -> Unit,
    onPick: ((List<String>) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var path by remember { mutableStateOf(normalizePath(startPath)) }
    var entries by remember { mutableStateOf<List<AirdEntry>>(emptyList()) }
    var failed by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf(listOf<String>()) }
    var opening by remember { mutableStateOf("") }
    val segments = if (path.isEmpty()) emptyList() else path.split('/').filter { it.isNotEmpty() }

    fun goUp() {
        if (path.isEmpty()) onBack() else path = path.substringBeforeLast('/', "")
    }

    BackHandler(onBack = ::goUp)
    LaunchedEffect(path) {
        loading = true
        failed = false
        val listed = ChatRepository.listFiles(context, path)
        failed = listed == null
        entries = listed.orEmpty().sortedWith(compareBy<AirdEntry>({ !it.dir }, { it.name.lowercase() }))
        loading = false
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.ime),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
                title = { Text("Files", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = ::goUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        bottomBar = {
            if (onPick != null) {
                Button(
                    onClick = { onPick(selected.toList()) },
                    enabled = selected.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) {
                    Text(if (selected.isEmpty()) "Select" else "Select (${selected.size})")
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Crumb("Home", path.isEmpty()) { path = "" }
                var walked = ""
                segments.forEach { segment ->
                    walked = if (walked.isEmpty()) segment else "$walked/$segment"
                    Text(
                        "/",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                        modifier = Modifier.padding(horizontal = 2.dp),
                    )
                    val target = walked
                    Crumb(segment, path == target) { path = target }
                }
            }
            HorizontalDivider()
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                failed -> Text(
                    "Could not load this folder",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(entries, key = { it.path }) { entry ->
                        val chosen = entry.path in selected
                        val textColor = if (chosen) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .background(
                                    if (chosen) {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.background
                                    },
                                )
                                .combinedClickable(
                                    onClick = {
                                        if (entry.dir) {
                                            path = entry.path
                                        } else if (opening.isEmpty()) {
                                            opening = entry.path
                                            scope.launch {
                                                try {
                                                    openChatAttachment(
                                                        context,
                                                        browseMessageId(entry.path),
                                                        ChatAttachment(
                                                            name = entry.name.ifBlank { "file" },
                                                            url = downloadUrl(entry.path),
                                                            mediaKind = "file",
                                                            index = 0,
                                                        ),
                                                    )
                                                } finally {
                                                    if (opening == entry.path) opening = ""
                                                }
                                            }
                                        }
                                    },
                                    onLongClick = if (onPick == null) {
                                        null
                                    } else {
                                        {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            selected = if (entry.path in selected) selected - entry.path else selected + entry.path
                                        }
                                    },
                                )
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (entry.dir) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = textColor,
                            )
                            Text(
                                if (entry.dir) "${entry.name}/" else entry.name,
                                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = textColor,
                            )
                            if (opening == entry.path) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else if (chosen) {
                                Icon(Icons.Default.Check, contentDescription = "Selected", tint = textColor)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Crumb(label: String, current: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            label,
            maxLines = 1,
            softWrap = false,
            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
            color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun normalizePath(raw: String): String = raw.replace('\\', '/').trim().trim('/')

private fun downloadUrl(path: String): String {
    val encoded = normalizePath(path).split('/')
        .filter { it.isNotEmpty() }
        .joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
    return if (encoded.isEmpty()) "/files/?download=1" else "/files/$encoded?download=1"
}

private fun browseMessageId(path: String): String {
    val slim = path.filter { it.isLetterOrDigit() }.take(40)
    return "b$slim${path.hashCode().toUInt().toString(16)}"
}
