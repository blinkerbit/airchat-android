package com.aird.chat.ui

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aird.chat.data.AirdEntry
import com.aird.chat.data.ChatRepository
import com.aird.chat.data.FileOp
import com.aird.chat.data.TagCatalog
import com.aird.chat.model.Conversation
import com.aird.chat.model.ChatAttachment
import com.aird.ui.LocalAirdReducedMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder

private sealed interface FilesPlace {
    data class Folder(val path: String) : FilesPlace
    data class Tag(val name: String) : FilesPlace
}

private sealed interface FilesDialog {
    data object None : FilesDialog
    data object NewFolder : FilesDialog
    data class Rename(val entry: AirdEntry) : FilesDialog
    data class Delete(val entries: List<AirdEntry>) : FilesDialog
    data class Tag(val entries: List<AirdEntry>) : FilesDialog
}

private data class Placement(val entries: List<AirdEntry>, val move: Boolean)

private data class UploadRow(
    val id: Long,
    val name: String,
    val loaded: Long,
    val total: Long,
    val status: String,
)

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
    val reduced = LocalAirdReducedMotion.current
    var folder by remember { mutableStateOf(normalizePath(startPath)) }
    var tag by remember { mutableStateOf<String?>(null) }
    var forward by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(listOf<AirdEntry>()) }
    var catalog by remember { mutableStateOf(TagCatalog(emptyList(), emptyMap())) }
    var notice by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var opening by remember { mutableStateOf("") }
    var dialog by remember { mutableStateOf<FilesDialog>(FilesDialog.None) }
    var menuEntry by remember { mutableStateOf<AirdEntry?>(null) }
    var placing by remember { mutableStateOf<Placement?>(null) }
    var draft by remember { mutableStateOf("") }
    var uploads by remember { mutableStateOf(listOf<UploadRow>()) }
    var uploadChooser by remember { mutableStateOf(false) }
    var uploadDest by remember { mutableStateOf("") }
    var uploadSerial by remember { mutableLongStateOf(0L) }
    var shareWith by remember { mutableStateOf<List<AirdEntry>?>(null) }
    val chats by ChatRepository.conversations.collectAsState()
    val cancelledUploads = remember { java.util.concurrent.ConcurrentHashMap.newKeySet<Long>() }
    val place: FilesPlace = tag?.let { FilesPlace.Tag(it) } ?: FilesPlace.Folder(folder)
    val managing = onPick == null && placing == null

    fun clearSelection() {
        selected = emptyList()
    }

    fun toggle(entry: AirdEntry) {
        selected = if (selected.any { it.path == entry.path }) {
            selected.filterNot { it.path == entry.path }
        } else {
            selected + entry
        }
    }

    fun goUp() {
        if (searchOpen) {
            searchOpen = false
            query = ""
            return
        }
        if (placing != null) {
            placing = null
            return
        }
        if (selected.isNotEmpty()) {
            clearSelection()
            return
        }
        val activeTag = tag
        if (activeTag != null) {
            forward = false
            tag = null
            return
        }
        if (folder.isNotEmpty()) {
            forward = false
            folder = folder.substringBeforeLast('/', "")
            return
        }
        onBack()
    }

    fun beginPlace(entries: List<AirdEntry>, move: Boolean) {
        placing = Placement(entries, move)
        clearSelection()
        if (tag != null) {
            forward = false
            tag = null
        }
    }

    fun openFolder(next: String, deeper: Boolean) {
        forward = deeper
        tag = null
        folder = normalizePath(next)
        clearSelection()
        query = ""
    }

    fun queueUpload(dest: String, uri: Uri, relative: String, size: Long) {
        uploadSerial += 1
        val id = uploadSerial
        val leaf = relative.substringAfterLast('/')
        uploads = uploads + UploadRow(id, relative, 0, size.coerceAtLeast(0), "")
        val parent = relative.substringBeforeLast('/', "")
        val dir = listOf(dest.trim().trim('/'), parent).filter { it.isNotEmpty() }.joinToString("/")
        scope.launch {
            val op = ChatRepository.uploadUri(
                context,
                dir,
                safeFileName(leaf),
                uri,
                size,
                { id in cancelledUploads },
            ) { loaded, total ->
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    uploads = uploads.map { row ->
                        if (row.id == id) row.copy(loaded = loaded, total = if (total > 0) total else row.total) else row
                    }
                }
            }
            uploads = uploads.map { row ->
                if (row.id != id) {
                    row
                } else if (op.ok) {
                    row.copy(status = "done", loaded = row.total.coerceAtLeast(row.loaded))
                } else if (op.message == "cancelled") {
                    row.copy(status = "cancelled")
                } else {
                    row.copy(status = op.message.ifBlank { "Could not upload" })
                }
            }
            if (op.ok) reload += 1
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val dest = uploadDest
        uris.forEach { uri -> queueUpload(dest, uri, displayName(context, uri), uriSize(context, uri)) }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val dest = uploadDest
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
        scope.launch {
            val items = withContext(Dispatchers.IO) { filesInTree(context, uri) }
            if (items.isEmpty()) notice = "That folder is empty"
            else items.forEach { queueUpload(dest, it.uri, it.relative, it.size) }
        }
    }

    BackHandler(onBack = ::goUp)
    LaunchedEffect(Unit) {
        catalog = ChatRepository.tagCatalog(context)
    }
    LaunchedEffect(shareWith) {
        if (shareWith != null && chats.isEmpty()) ChatRepository.refresh(context, quiet = true)
    }

    suspend fun finish(op: FileOp, okText: String) {
        notice = if (op.ok) okText else op.message
        if (op.ok) {
            catalog = ChatRepository.tagCatalog(context)
            reload += 1
            clearSelection()
            dialog = FilesDialog.None
            menuEntry = null
        }
    }

    val title = when {
        placing != null -> if (placing?.move == true) "Move to" else "Copy to"
        tag != null -> tag.orEmpty()
        folder.isEmpty() -> "Files"
        else -> folder.substringAfterLast('/')
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.ime),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
                title = { Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = ::goUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (managing) {
                        IconButton(onClick = { draft = ""; dialog = FilesDialog.NewFolder }, enabled = !busy) {
                            Icon(
                                Icons.Default.CreateNewFolder,
                                contentDescription = "New folder",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        IconButton(onClick = { uploadDest = folder; uploadChooser = true }, enabled = !busy) {
                            Icon(
                                Icons.Outlined.FileUpload,
                                contentDescription = "Upload",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) query = "" }) {
                        Icon(Icons.Default.Search, contentDescription = "Filter")
                    }
                },
            )
        },
        bottomBar = {
            FilesBar(
                reduced = reduced,
                managing = managing,
                picking = onPick != null,
                tagging = tag != null,
                selectedCount = selected.size,
                placing = placing,
                folderLabel = if (folder.isEmpty()) "Home" else folder.substringAfterLast('/'),
                busy = busy,
                onNewFolder = { draft = ""; dialog = FilesDialog.NewFolder },
                onUpload = {
                    uploadDest = folder
                    uploadChooser = true
                },
                onMove = { beginPlace(selected, true) },
                onCopy = { beginPlace(selected, false) },
                onTag = {
                    draft = ""
                    dialog = FilesDialog.Tag(selected)
                },
                onShare = { shareWith = selected },
                onDelete = { dialog = FilesDialog.Delete(selected) },
                onPlace = {
                    val job = placing ?: return@FilesBar
                    scope.launch {
                        busy = true
                        var failed = ""
                        job.entries.forEach { entry ->
                            val name = entry.path.substringAfterLast('/')
                            if (entry.dir && (folder == entry.path || folder.startsWith("${entry.path}/"))) {
                                failed = "Choose a different folder"
                                return@forEach
                            }
                            val dest = if (folder.isEmpty()) name else "$folder/$name"
                            if (dest == entry.path) {
                                failed = "Already in this folder"
                                return@forEach
                            }
                            val op = ChatRepository.transferPath(context, entry.path, dest, job.move)
                            if (!op.ok && failed.isEmpty()) failed = op.message
                        }
                        busy = false
                        placing = null
                        notice = failed.ifBlank { if (job.move) "Moved" else "Copied" }
                        reload += 1
                    }
                },
                onCancelPlace = { placing = null },
                onPick = { onPick?.invoke(selected.map { it.path }) },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (tag == null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Crumb("Home", folder.isEmpty()) { openFolder("", false) }
                    var walked = ""
                    folder.split('/').filter { it.isNotEmpty() }.forEach { segment ->
                        walked = if (walked.isEmpty()) segment else "$walked/$segment"
                        Text(
                            "/",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                            modifier = Modifier.padding(horizontal = 2.dp),
                        )
                        val target = walked
                        Crumb(segment, folder == target) { openFolder(target, target.length > folder.length) }
                    }
                }
            }
            if (catalog.names.isNotEmpty() && placing == null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(selected = tag == null, onClick = { forward = false; tag = null }, label = { Text("All") })
                    catalog.names.forEach { name ->
                        FilterChip(
                            selected = tag == name,
                            onClick = {
                                if (tag == name) {
                                    forward = false
                                    tag = null
                                } else {
                                    forward = true
                                    tag = name
                                }
                                clearSelection()
                            },
                            label = { Text(name, maxLines = 1) },
                        )
                    }
                }
            }
            AnimatedVisibility(
                visible = searchOpen,
                enter = if (reduced) fadeIn(tween(120)) else expandVertically(tween(180)) + fadeIn(tween(140)),
                exit = if (reduced) fadeOut(tween(80)) else shrinkVertically(tween(140)) + fadeOut(tween(100)),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    singleLine = true,
                    placeholder = { Text("Filter by name or tag") },
                    shape = RoundedCornerShape(24.dp),
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear filter")
                            }
                        }
                    },
                )
            }
            uploads.forEach { row ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(row.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            when {
                                row.status == "done" -> "Done"
                                row.status == "cancelled" -> "Cancelled"
                                row.status.isNotEmpty() -> row.status
                                row.total > 0 -> "${((row.loaded * 100) / row.total).coerceIn(0, 100)}%"
                                else -> "Uploading"
                            },
                            maxLines = 2,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        IconButton(onClick = {
                            if (row.status.isEmpty()) cancelledUploads.add(row.id)
                            else uploads = uploads.filterNot { it.id == row.id }
                        }) {
                            Icon(Icons.Default.Close, contentDescription = if (row.status.isEmpty()) "Cancel upload" else "Dismiss")
                        }
                    }
                    if (row.status.isEmpty()) {
                        LinearProgressIndicator(
                            progress = {
                                if (row.total <= 0L) 0f else (row.loaded.toFloat() / row.total).coerceIn(0f, 1f)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            if (notice.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(notice, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.primary, maxLines = 2)
                    IconButton(onClick = { notice = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "Dismiss")
                    }
                }
            }
            HorizontalDivider()
            AnimatedContent(
                targetState = place,
                transitionSpec = {
                    val dir = if (forward) 1 else -1
                    if (reduced) {
                        fadeIn(tween(120)) togetherWith fadeOut(tween(80))
                    } else {
                        ContentTransform(
                            slideInHorizontally(tween(260)) { it * dir } + fadeIn(tween(160)),
                            slideOutHorizontally(tween(220)) { -it * dir } + fadeOut(tween(120)),
                        )
                    }
                },
                label = "files",
            ) { current ->
                FileList(
                    place = current,
                    query = query,
                    reload = reload,
                    colors = catalog.colors,
                    selected = selected.map { it.path }.toSet(),
                    opening = opening,
                    managing = managing,
                    onClick = { entry ->
                        if (placing != null) {
                            if (entry.dir) openFolder(entry.path, true)
                            return@FileList
                        }
                        if (selected.isNotEmpty()) {
                            toggle(entry)
                            return@FileList
                        }
                        if (entry.dir) {
                            openFolder(entry.path, true)
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
                    onLongClick = { entry ->
                        if (placing != null) return@FileList
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        toggle(entry)
                    },
                    onMore = { menuEntry = it },
                )
            }
        }
    }

    val shown = dialog
    if (shown is FilesDialog.NewFolder || shown is FilesDialog.Rename) {
        val renaming = shown is FilesDialog.Rename
        AlertDialog(
            onDismissRequest = { if (!busy) dialog = FilesDialog.None },
            title = { Text(if (renaming) "Rename" else "New folder") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    placeholder = { Text("Name") },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !busy && validName(draft),
                    onClick = {
                        scope.launch {
                            busy = true
                            val op = if (shown is FilesDialog.Rename) {
                                ChatRepository.renamePath(context, shown.entry.path, draft.trim())
                            } else {
                                ChatRepository.createFolder(context, folder, draft.trim())
                            }
                            busy = false
                            finish(op, if (renaming) "Renamed" else "Folder created")
                        }
                    },
                ) { Text(if (renaming) "Rename" else "Create") }
            },
            dismissButton = { TextButton(onClick = { dialog = FilesDialog.None }) { Text("Cancel") } },
        )
    }
    if (shown is FilesDialog.Delete) {
        AlertDialog(
            onDismissRequest = { if (!busy) dialog = FilesDialog.None },
            title = { Text(if (shown.entries.size == 1) "Delete ${shown.entries.first().name}" else "Delete ${shown.entries.size} items") },
            text = { Text("Folders are removed with everything inside them.") },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            var failed = ""
                            shown.entries.forEach { entry ->
                                val op = ChatRepository.deletePath(context, entry.path, entry.dir)
                                if (!op.ok && failed.isEmpty()) failed = op.message
                            }
                            busy = false
                            dialog = FilesDialog.None
                            if (failed.isEmpty()) {
                                finish(FileOp(true, ""), "Deleted")
                            } else {
                                notice = failed
                                reload += 1
                                clearSelection()
                            }
                        }
                    },
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { dialog = FilesDialog.None }) { Text("Cancel") } },
        )
    }
    if (shown is FilesDialog.Tag) {
        TagDialog(
            entries = shown.entries,
            catalog = catalog,
            busy = busy,
            draft = draft,
            onDraft = { draft = it },
            onDismiss = { if (!busy) dialog = FilesDialog.None },
            onApply = { name ->
                scope.launch {
                    busy = true
                    var failed: FileOp? = null
                    shown.entries.forEach { entry ->
                        val op = ChatRepository.applyTag(context, entry.path, name)
                        if (!op.ok && failed == null) failed = op
                    }
                    busy = false
                    finish(failed ?: FileOp(true, ""), "Tagged")
                }
            },
            onRemove = { entry, name ->
                scope.launch {
                    busy = true
                    val op = ChatRepository.untagPath(context, entry.path, name)
                    busy = false
                    finish(op, "Tag removed")
                }
            },
        )
    }
    val pendingShare = shareWith
    if (pendingShare != null) {
        AlertDialog(
            onDismissRequest = { if (!busy) shareWith = null },
            title = {
                Text(if (pendingShare.size == 1) "Share ${pendingShare.first().name}" else "Share ${pendingShare.size} items")
            },
            text = {
                if (chats.isEmpty()) {
                    Text("No conversations yet")
                } else {
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(chats, key = { it.id }) { conv ->
                            TextButton(
                                onClick = {
                                    val paths = pendingShare.map { it.path }
                                    shareWith = null
                                    scope.launch {
                                        busy = true
                                        val op = ChatRepository.sharePaths(context, conv.id, paths)
                                        busy = false
                                        finish(op, "Shared in ${conv.name()}")
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(conv.name(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { shareWith = null }) { Text("Cancel") } },
        )
    }
    if (uploadChooser) {
        AlertDialog(
            onDismissRequest = { uploadChooser = false },
            title = { Text("Upload") },
            text = { Text(if (uploadDest.isEmpty()) "Into Home" else "Into $uploadDest") },
            confirmButton = {
                TextButton(onClick = {
                    uploadChooser = false
                    filePicker.launch(arrayOf("*/*"))
                }) { Text("Files") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        uploadChooser = false
                        folderPicker.launch(null)
                    }) { Text("Folder") }
                    TextButton(onClick = { uploadChooser = false }) { Text("Cancel") }
                }
            },
        )
    }
    val menu = menuEntry
    if (menu != null) {
        ModalBottomSheet(onDismissRequest = { menuEntry = null }) {
            ListItem(
                headlineContent = { Text(menu.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(menu.path.ifBlank { "Home" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
            if (!menu.dir) {
                SheetRow("Open", Icons.AutoMirrored.Filled.InsertDriveFile) {
                    menuEntry = null
                    opening = menu.path
                    scope.launch {
                        try {
                            openChatAttachment(
                                context,
                                browseMessageId(menu.path),
                                ChatAttachment(menu.name.ifBlank { "file" }, downloadUrl(menu.path), "file", 0),
                            )
                        } finally {
                            if (opening == menu.path) opening = ""
                        }
                    }
                }
            }
            if (managing) {
                SheetRow("Rename", Icons.Outlined.Edit) {
                    menuEntry = null
                    draft = menu.name
                    dialog = FilesDialog.Rename(menu)
                }
                SheetRow("Move", Icons.AutoMirrored.Filled.DriveFileMove) {
                    menuEntry = null
                    beginPlace(listOf(menu), true)
                }
                SheetRow("Copy", Icons.Outlined.ContentCopy) {
                    menuEntry = null
                    beginPlace(listOf(menu), false)
                }
                if (menu.dir) {
                    SheetRow("Upload here", Icons.Outlined.FileUpload) {
                        menuEntry = null
                        uploadDest = menu.path
                        uploadChooser = true
                    }
                }
                SheetRow("Share in chat", Icons.Outlined.Share) {
                    menuEntry = null
                    shareWith = listOf(menu)
                }
                SheetRow("Tags", Icons.AutoMirrored.Outlined.Label) {
                    menuEntry = null
                    draft = ""
                    dialog = FilesDialog.Tag(listOf(menu))
                }
                SheetRow("Delete", Icons.Outlined.Delete) {
                    menuEntry = null
                    dialog = FilesDialog.Delete(listOf(menu))
                }
            }
        }
    }
}

@Composable
private fun FilesBar(
    reduced: Boolean,
    managing: Boolean,
    picking: Boolean,
    tagging: Boolean,
    selectedCount: Int,
    placing: Placement?,
    folderLabel: String,
    busy: Boolean,
    onNewFolder: () -> Unit,
    onUpload: () -> Unit,
    onMove: () -> Unit,
    onCopy: () -> Unit,
    onTag: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onPlace: () -> Unit,
    onCancelPlace: () -> Unit,
    onPick: () -> Unit,
) {
    val mode = when {
        placing != null -> "place"
        picking -> "pick"
        selectedCount > 0 -> "select"
        else -> "none"
    }
    if (mode == "none") return
    AnimatedContent(
        targetState = mode,
        transitionSpec = {
            if (reduced) fadeIn(tween(100)) togetherWith fadeOut(tween(80))
            else (fadeIn(tween(140)) + expandVertically()) togetherWith (fadeOut(tween(100)) + shrinkVertically())
        },
        label = "filesBar",
    ) { current ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            when (current) {
                "place" -> {
                    TextButton(onClick = onCancelPlace, enabled = !busy) { Text("Cancel") }
                    Button(onClick = onPlace, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Text(if (placing?.move == true) "Move here · $folderLabel" else "Copy here · $folderLabel", maxLines = 1)
                    }
                }
                "pick" -> {
                    Button(onClick = onPick, enabled = selectedCount > 0, modifier = Modifier.fillMaxWidth()) {
                        Text("Select ($selectedCount)")
                    }
                }
                "select" -> {
                    Text("$selectedCount", modifier = Modifier.padding(horizontal = 8.dp), fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = onMove, enabled = !busy) { Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = "Move") }
                    IconButton(onClick = onCopy, enabled = !busy) { Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy") }
                    IconButton(onClick = onTag, enabled = !busy) { Icon(Icons.AutoMirrored.Outlined.Label, contentDescription = "Tag") }
                    IconButton(onClick = onShare, enabled = !busy) { Icon(Icons.Outlined.Share, contentDescription = "Share in chat") }
                    IconButton(onClick = onDelete, enabled = !busy) { Icon(Icons.Outlined.Delete, contentDescription = "Delete") }
                }
            }
        }
    }
}

@Composable
private fun TagDialog(
    entries: List<AirdEntry>,
    catalog: TagCatalog,
    busy: Boolean,
    draft: String,
    onDraft: (String) -> Unit,
    onDismiss: () -> Unit,
    onApply: (String) -> Unit,
    onRemove: (AirdEntry, String) -> Unit,
) {
    val single = entries.singleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (entries.size == 1) "Tags" else "Tag ${entries.size} items") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (single != null && single.tags.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        single.tags.forEach { name ->
                            FilterChip(
                                selected = true,
                                onClick = { if (!busy) onRemove(single, name) },
                                label = { Text("$name  ×") },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraft,
                    singleLine = true,
                    placeholder = { Text("New tag") },
                )
                if (catalog.names.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        catalog.names.forEach { name ->
                            FilterChip(selected = false, onClick = { if (!busy) onApply(name) }, label = { Text(name) })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && validName(draft), onClick = { onApply(draft.trim()) }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileList(
    place: FilesPlace,
    query: String,
    reload: Int,
    colors: Map<String, String>,
    selected: Set<String>,
    opening: String,
    managing: Boolean,
    onClick: (AirdEntry) -> Unit,
    onLongClick: (AirdEntry) -> Unit,
    onMore: (AirdEntry) -> Unit,
) {
    val context = LocalContext.current
    var entries by remember(place) { mutableStateOf<List<AirdEntry>>(emptyList()) }
    var loading by remember(place) { mutableStateOf(true) }
    var failed by remember(place) { mutableStateOf(false) }
    LaunchedEffect(place, reload) {
        val first = entries.isEmpty()
        if (first) loading = true
        val listed = when (place) {
            is FilesPlace.Folder -> ChatRepository.listFiles(context, place.path)
            is FilesPlace.Tag -> ChatRepository.filesForTag(context, place.name)
        }
        failed = listed == null
        entries = listed.orEmpty().sortedWith(compareBy<AirdEntry>({ !it.dir }, { it.name.lowercase() }))
        loading = false
    }
    val shown = entries.filter { entry ->
        query.isBlank() ||
            entry.name.contains(query, true) ||
            entry.tags.any { it.contains(query, true) } ||
            entry.path.contains(query, true)
    }
    when {
        loading && entries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        failed -> Text("Could not load files", modifier = Modifier.padding(16.dp))
        shown.isEmpty() -> Text(
            if (query.isBlank()) {
                if (place is FilesPlace.Tag) "No files with this tag" else "This folder is empty"
            } else {
                "Nothing matches"
            },
            modifier = Modifier.padding(16.dp),
        )
        else -> LazyColumn(Modifier.fillMaxSize()) {
            if (loading) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
            items(shown, key = { it.path }) { entry ->
                val chosen = entry.path in selected
                val style = getFileStyle(entry)

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 3.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = if (chosen) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
                            else MaterialTheme.colorScheme.surfaceContainer,
                    border = if (chosen) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                    shadowElevation = if (chosen) 2.dp else 0.dp,
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { onClick(entry) },
                                onLongClick = { onLongClick(entry) },
                            )
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(style.containerColor)
                        ) {
                            Icon(
                                style.icon,
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                                tint = style.iconColor
                            )
                        }

                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(
                                entry.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Medium,
                                fontSize = 14.sp
                            )
                            val meta = entryMeta(entry, place)
                            if (meta.isNotEmpty()) {
                                Text(
                                    meta,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (entry.tags.isNotEmpty()) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.padding(top = 4.dp)
                                ) {
                                    entry.tags.take(3).forEach { name ->
                                        TagDot(name, colors[name])
                                    }
                                    if (entry.tags.size > 3) {
                                        Text(
                                            "+${entry.tags.size - 3}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        if (opening == entry.path) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else if (chosen) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                            ) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        } else if (managing) {
                            IconButton(onClick = { onMore(entry) }, modifier = Modifier.size(36.dp)) {
                                Icon(
                                    Icons.Default.MoreVert,
                                    contentDescription = "Actions",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class FileStyle(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val containerColor: Color,
    val iconColor: Color,
)

@Composable
private fun getFileStyle(entry: AirdEntry): FileStyle {
    if (entry.dir) {
        return FileStyle(
            Icons.Default.Folder,
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.primary
        )
    }
    val ext = entry.name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic" -> FileStyle(
            Icons.Default.Image,
            Color(0xFFE3F2FD),
            Color(0xFF1976D2)
        )
        "mp4", "mkv", "mov", "avi", "webm" -> FileStyle(
            Icons.Default.VideoFile,
            Color(0xFFFFEBEE),
            Color(0xFFD32F2F)
        )
        "mp3", "m4a", "wav", "aac", "ogg", "flac" -> FileStyle(
            Icons.Default.AudioFile,
            Color(0xFFFFF3E0),
            Color(0xFFF57C00)
        )
        "pdf" -> FileStyle(
            Icons.Default.PictureAsPdf,
            Color(0xFFFFEBEE),
            Color(0xFFC62828)
        )
        "doc", "docx", "txt", "rtf", "md" -> FileStyle(
            Icons.Default.Description,
            Color(0xE3E8EAF6),
            Color(0xFF303F9F)
        )
        "zip", "tar", "gz", "7z", "rar" -> FileStyle(
            Icons.Default.FolderZip,
            Color(0xFFF3E5F5),
            Color(0xFF7B1FA2)
        )
        "apk" -> FileStyle(
            Icons.Default.Android,
            Color(0xE3E8F5E9),
            Color(0xFF388E3C)
        )
        else -> FileStyle(
            Icons.AutoMirrored.Filled.InsertDriveFile,
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TagDot(name: String, hex: String?, onClick: (() -> Unit)? = null) {
    val bg = tagColor(hex) ?: MaterialTheme.colorScheme.secondaryContainer
    val fg = if (hex.isNullOrBlank()) MaterialTheme.colorScheme.onSecondaryContainer else Color.White
    Surface(
        color = bg,
        shape = RoundedCornerShape(8.dp),
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    ) {
        Text(name, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), color = fg, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
private fun SheetRow(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Icon(icon, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
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

private fun entryMeta(entry: AirdEntry, place: FilesPlace): String = buildList {
    if (place is FilesPlace.Tag) {
        val parent = entry.path.substringBeforeLast('/', "")
        if (parent.isNotEmpty()) add(parent)
    }
    if (entry.dir) add("Folder") else if (entry.size.isNotBlank()) add(entry.size)
    if (entry.modified.isNotBlank()) add(entry.modified)
    if (entry.shared) add("Shared")
}.joinToString(" · ")

private fun tagColor(hex: String?): Color? {
    val raw = hex?.trim()?.removePrefix("#") ?: return null
    if (raw.length != 6) return null
    val n = raw.toIntOrNull(16) ?: return null
    return Color(0xFF000000.toInt() or n)
}

private fun validName(raw: String): Boolean {
    val name = raw.trim()
    return name.isNotEmpty() && name != "." && name != ".." && !name.contains('/') && !name.contains('\\') && name.length <= 255
}

private data class TreeFile(val uri: Uri, val relative: String, val size: Long)

private fun filesInTree(context: android.content.Context, tree: Uri): List<TreeFile> {
    val rootId = DocumentsContract.getTreeDocumentId(tree)
    val out = mutableListOf<TreeFile>()
    fun walk(documentId: String, prefix: String) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
        context.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            while (cursor.moveToNext()) {
                if (idCol < 0) continue
                val id = cursor.getString(idCol) ?: continue
                val name = (if (nameCol >= 0) cursor.getString(nameCol) else null).orEmpty().ifBlank { "file" }
                if (name == "." || name == ".." || name.contains('/') || name.contains('\\')) continue
                val mime = if (mimeCol >= 0) cursor.getString(mimeCol).orEmpty() else ""
                val child = if (prefix.isEmpty()) name else "$prefix/$name"
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walk(id, child)
                } else {
                    val size = if (sizeCol >= 0 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else -1L
                    out.add(TreeFile(DocumentsContract.buildDocumentUriUsingTree(tree, id), child, size))
                }
            }
        }
    }
    walk(rootId, "")
    return out
}

private fun uriSize(context: android.content.Context, uri: Uri): Long {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && !cursor.isNull(index)) return cursor.getLong(index)
        }
    }
    return -1L
}

private fun displayName(context: android.content.Context, uri: Uri): String {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) {
                val name = cursor.getString(index).orEmpty()
                if (name.isNotBlank()) return safeFileName(name)
            }
        }
    }
    return safeFileName(uri.lastPathSegment ?: "file")
}

private fun safeFileName(raw: String): String {
    val base = raw.substringAfterLast('/').substringAfterLast('\\').trim()
    return base.ifBlank { "file" }.take(255)
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
