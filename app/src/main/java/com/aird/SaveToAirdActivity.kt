package com.aird

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.aird.chat.data.AirdEntry
import com.aird.chat.data.ChatRepository
import com.aird.chat.data.FilesRepository
import com.aird.data.db.LocalStore
import com.aird.data.db.RecentFileEntity
import com.aird.designsystem.theme.AirdTheme
import com.aird.designsystem.theme.Spacing
import com.aird.session.Session
import com.aird.transfer.FileUploadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Share-sheet target that uploads files into Aird storage (distinct from chat share). */
class SaveToAirdActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val uris = collectUris(intent)
        if (uris.isEmpty()) {
            Toast.makeText(this, "Nothing to save", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        setContent {
            AirdTheme {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.extraLarge) {
                    SaveToAirdScreen(
                        uris = uris,
                        onDone = { finish() },
                        onCancel = { finish() },
                    )
                }
            }
        }
    }

    private fun collectUris(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM))
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.filterNotNull().orEmpty()
            }
            else -> emptyList()
        }
    }
}

@Composable
private fun SaveToAirdScreen(
    uris: List<Uri>,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<AirdEntry>>(emptyList()) }
    var recent by remember { mutableStateOf<List<RecentFileEntity>>(emptyList()) }
    var rename by remember { mutableStateOf(if (uris.size == 1) displayName(context, uris.first()) else "") }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var signedIn by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        Session.restore(context)
        signedIn = Session.username.value.isNotBlank()
        if (!signedIn) return@LaunchedEffect
        ChatRepository.prepare(context)
        val user = Session.username.value
        recent = withContext(Dispatchers.IO) {
            LocalStore.db(context, user)?.recentFiles()?.recent(8)?.first().orEmpty()
                .filter { it.dir }
        }
    }
    LaunchedEffect(path) {
        val listed = FilesRepository.listFiles(context, path)
        failed = listed == null
        entries = listed.orEmpty().filter { it.dir }
    }

    Column(Modifier.fillMaxWidth().padding(Spacing.xl)) {
        Text("Save to Aird", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
            if (uris.size == 1) displayName(context, uris.first()) else "${uris.size} files",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(Spacing.md))
        if (!signedIn) {
            Text(
                "Sign in to Aird first, then share again.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(Spacing.md))
            TextButton(onClick = onCancel) { Text("Close") }
            return@Column
        }
        Text(
            if (path.isEmpty()) "/" else "/$path",
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (uris.size == 1) {
            OutlinedTextField(
                value = rename,
                onValueChange = { rename = it },
                label = { Text("Rename (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
            )
        }
        if (recent.isNotEmpty()) {
            Text("Recent folders", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = Spacing.md))
            recent.forEach { folder ->
                Text(
                    folder.name.ifBlank { folder.path.ifBlank { "Home" } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { path = folder.path }
                        .padding(vertical = 8.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (path.isNotEmpty()) {
                TextButton(onClick = { path = path.substringBeforeLast('/', "") }) { Text("Up") }
            } else {
                Spacer(Modifier)
            }
        }
        LazyColumn(Modifier.fillMaxWidth().height(200.dp)) {
            if (failed) {
                item { Text("Could not load this folder", color = MaterialTheme.colorScheme.error) }
            }
            items(entries, key = { it.path }) { entry ->
                Text(
                    "${entry.name}/",
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { path = entry.path }
                        .padding(vertical = 10.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(Spacing.md))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel, enabled = !busy) { Text("Cancel") }
            Button(
                onClick = {
                    busy = true
                    scope.launch {
                        val ok = uploadAll(context, uris, path, rename)
                        busy = false
                        Toast.makeText(
                            context,
                            if (ok) "Uploading to Aird" else "Could not start upload",
                            Toast.LENGTH_SHORT,
                        ).show()
                        if (ok) {
                            touchRecent(context, path)
                            onDone()
                        }
                    }
                },
                enabled = !busy,
            ) { Text(if (busy) "Saving…" else "Save here") }
        }
    }
}

private suspend fun uploadAll(context: Context, uris: List<Uri>, dir: String, rename: String): Boolean {
    if (!Session.signedIn.value) {
        Session.restore(context)
        if (!Session.signedIn.value) return false
    }
    return try {
        uris.forEachIndexed { index, uri ->
            val name = when {
                uris.size == 1 && rename.isNotBlank() -> rename.trim()
                else -> displayName(context, uri)
            }
            val size = uriSize(context, uri)
            FileUploadWorker.enqueue(context, uri, dir, name, size)
            if (index == 0) Unit
        }
        true
    } catch (_: Exception) {
        false
    }
}

private suspend fun touchRecent(context: Context, path: String) {
    val user = Session.username.value
    val dao = LocalStore.db(context, user)?.recentFiles() ?: return
    val name = path.substringAfterLast('/').ifBlank { "Home" }
    dao.upsert(RecentFileEntity(path = path, name = name, dir = true, touchedAt = System.currentTimeMillis()))
    dao.trim(24)
}

private fun uriSize(context: Context, uri: Uri): Long {
    return try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && !cursor.isNull(idx)) return cursor.getLong(idx)
            }
        }
        -1L
    } catch (_: Exception) {
        -1L
    }
}

private fun displayName(context: Context, uri: Uri): String {
    try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) {
                    val name = cursor.getString(idx)
                    if (!name.isNullOrBlank()) return name
                }
            }
        }
    } catch (_: Exception) {
    }
    return uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { "file" } ?: "file"
}
