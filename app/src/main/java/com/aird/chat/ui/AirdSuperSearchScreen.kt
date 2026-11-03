package com.aird.chat.ui

import android.webkit.CookieManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aird.ServerStore
import com.aird.session.AirdHttp
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private val patternChips = listOf(
    "*.py",
    "*.js,*.ts",
    "**/*.java",
    "!node_modules",
    "*.{md,txt,log}",
)

private const val PATTERN_HELP = "* ? ** [abc] {a,b}"

private data class SearchHit(
    val name: String,
    val path: String,
    val snippet: String,
    val openPath: String,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AirdSuperSearchScreen(
    startPath: String,
    onBack: () -> Unit,
    onOpenPath: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf<Boolean?>(null) }
    var filenames by remember { mutableStateOf(false) }
    var pattern by remember { mutableStateOf("") }
    var searchText by remember { mutableStateOf("") }
    var help by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var errorLine by remember { mutableStateOf("") }
    var emptyLine by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf(listOf<SearchHit>()) }
    val generation = remember { AtomicInteger(0) }
    val alive = remember { AtomicBoolean(true) }
    val socketRef = remember { AtomicReference<WebSocket?>(null) }
    val wsClient = remember {
        AirdHttp.client.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()
    }

    DisposableEffect(Unit) {
        onDispose {
            alive.set(false)
            generation.incrementAndGet()
            socketRef.getAndSet(null)?.cancel()
        }
    }
    LaunchedEffect(Unit) {
        val response = try {
            AirdHttp.get(context, "/api/features")
        } catch (_: Exception) {
            null
        }
        enabled = if (response == null || !response.ok) {
            true
        } else {
            val json = response.json()
            !json.has("super_search") || json.optBoolean("super_search", true)
        }
    }

    fun post(block: () -> Unit) {
        scope.launch {
            if (alive.get()) block()
        }
    }

    fun applyMessage(text: String) {
        val data = try {
            JSONObject(text)
        } catch (_: Exception) {
            errorLine = "Received a malformed message."
            return
        }
        when (data.optString("type").trim()) {
            "match", "result" -> {
                hits = hits + hitFrom(data)
            }
            "scanning", "progress" -> progress = progressLine(data)
            "search_start" -> if (searching) progress = "Searching…"
            "error" -> {
                errorLine = data.optString("message").ifBlank { "Search failed" }
                emptyLine = ""
                searching = false
                progress = ""
            }
            "file_error" -> {
                errorLine = data.optString("message").ifBlank { "Search failed" }
                emptyLine = ""
            }
            "auth_required" -> {
                errorLine = data.optString("message").ifBlank { "Authentication required." }
                emptyLine = ""
                searching = false
                progress = ""
            }
            "no_files" -> {
                searching = false
                progress = ""
                if (hits.isEmpty() && errorLine.isEmpty()) {
                    emptyLine = data.optString("message").ifBlank { "No matches" }
                }
            }
            "search_complete", "done" -> {
                searching = false
                progress = ""
                if (hits.isEmpty() && errorLine.isEmpty()) {
                    emptyLine = data.optString("message").ifBlank { "No matches" }
                }
            }
            "cancelled" -> {
                searching = false
                progress = ""
                emptyLine = ""
                errorLine = "Search cancelled."
            }
        }
    }

    fun cancelSearch() {
        if (!searching) return
        generation.incrementAndGet()
        socketRef.getAndSet(null)?.cancel()
        searching = false
        progress = ""
        emptyLine = ""
        errorLine = "Search cancelled."
    }

    fun startSearch() {
        if (searching) return
        val patternValue = pattern.trim()
        val query = searchText.trim()
        if (patternValue.isEmpty() || query.isEmpty()) {
            errorLine = "Enter a pattern and search text."
            emptyLine = ""
            return
        }
        val server = ServerStore.get(context)
        val wsUrl = server.replace("https://", "wss://").replace("http://", "ws://") + "/search/ws"
        val payload = JSONObject()
            .put("pattern", patternValue)
            .put("search_text", query)
            .put("search_mode", if (filenames) "filename" else "content")
            .toString()
        val request = try {
            Request.Builder().url(wsUrl).header("Origin", server).build()
        } catch (_: Exception) {
            errorLine = "Connection error."
            emptyLine = ""
            return
        }
        val gen = generation.incrementAndGet()
        socketRef.getAndSet(null)?.cancel()
        searching = true
        progress = "Connecting…"
        errorLine = ""
        emptyLine = ""
        hits = emptyList()
        CookieManager.getInstance().flush()
        val socket = wsClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val accepted = webSocket.send(payload)
                post {
                    if (gen != generation.get()) return@post
                    if (!accepted) {
                        searching = false
                        progress = ""
                        errorLine = "Connection error."
                    } else {
                        progress = "Searching…"
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                post {
                    if (gen != generation.get()) return@post
                    applyMessage(text)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                post {
                    if (gen != generation.get()) return@post
                    val wasSearching = searching
                    searching = false
                    progress = ""
                    if (code == 1008) {
                        errorLine = reason.ifBlank { "Authentication required." }
                        emptyLine = ""
                    } else if (wasSearching && errorLine.isEmpty() && hits.isEmpty()) {
                        errorLine = "Connection error."
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                post {
                    if (gen != generation.get() || !searching) return@post
                    searching = false
                    progress = ""
                    if (errorLine.isEmpty()) errorLine = "Connection error."
                    emptyLine = ""
                }
            }
        })
        socketRef.set(socket)
    }

    SearchScaffold(onBack = onBack, bottomBar = {
        if (enabled == true) {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    if (searching) {
                        Text(
                            progress.ifBlank { "Searching…" },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = ::startSearch,
                            enabled = !searching,
                            modifier = Modifier.weight(1f),
                        ) { Text("Search") }
                        TextButton(onClick = ::cancelSearch, enabled = searching) { Text("Cancel") }
                    }
                }
            }
        }
    }) { padding ->
        when (enabled) {
            null -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            false -> Text(
                "Super Search is off",
                modifier = Modifier.padding(padding).padding(16.dp),
                color = MaterialTheme.colorScheme.onSurface,
            )
            true -> LazyColumn(
                Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(bottom = 12.dp),
            ) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            SegmentedButton(
                                selected = !filenames,
                                onClick = { filenames = false },
                                enabled = !searching,
                                shape = SegmentedButtonDefaults.itemShape(0, 2),
                                modifier = Modifier.weight(1f),
                            ) { Text("Content") }
                            SegmentedButton(
                                selected = filenames,
                                onClick = { filenames = true },
                                enabled = !searching,
                                shape = SegmentedButtonDefaults.itemShape(1, 2),
                                modifier = Modifier.weight(1f),
                            ) { Text("Filenames") }
                        }
                        OutlinedTextField(
                            value = pattern,
                            onValueChange = { pattern = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Pattern") },
                            singleLine = true,
                            enabled = !searching,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        )
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            patternChips.forEach { chip ->
                                SuggestionChip(
                                    onClick = { pattern = chipPattern(chip, startPath) },
                                    label = { Text(chip) },
                                    enabled = !searching,
                                )
                            }
                        }
                        OutlinedTextField(
                            value = searchText,
                            onValueChange = { searchText = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(if (filenames) "Filename" else "Search") },
                            singleLine = true,
                            enabled = !searching,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { startSearch() }),
                        )
                        TextButton(onClick = { help = !help }) { Text("Pattern help") }
                        if (help) {
                            Text(
                                PATTERN_HELP,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
                if (errorLine.isNotEmpty()) {
                    item {
                        Text(
                            errorLine,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                } else if (emptyLine.isNotEmpty()) {
                    item {
                        Text(
                            emptyLine,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                items(hits) { hit ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { onOpenPath(hit.openPath) }
                            .padding(12.dp),
                    ) {
                        Text(hit.name, fontWeight = FontWeight.SemiBold)
                        Text(
                            hit.path,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        if (hit.snippet.isNotEmpty()) {
                            Text(hit.snippet, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchScaffold(
    onBack: () -> Unit,
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    BackHandler(onBack = onBack)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.ime),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
                title = { Text("Super Search", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        bottomBar = bottomBar,
        content = content,
    )
}

private fun chipPattern(chip: String, startPath: String): String {
    val base = startPath.replace('\\', '/').trim().trim('/')
    if (base.isEmpty() || chip.startsWith("!") || chip.startsWith("**")) return chip
    return "$base/**/$chip"
}

private fun normalizePath(raw: String): String = raw.replace('\\', '/').trim().trim('/')

private fun parentPath(raw: String): String {
    val normalized = normalizePath(raw)
    return normalized.substringBeforeLast('/', "")
}

private fun hitFrom(data: JSONObject): SearchHit {
    val filePath = data.optString("file_path").trim()
    val sent = data.optString("path").trim()
    val normalized = normalizePath(filePath.ifBlank { sent })
    val name = normalized.substringAfterLast('/').ifBlank { normalized.ifBlank { "File" } }
    val line = data.optString("line_content").ifBlank { data.optString("snippet") }.trim()
    val filenameHit = data.has("line_number") && data.optInt("line_number") == 0
    val snippet = if (filenameHit || normalizePath(line) == normalized) "" else line
    val openPath = if (filePath.isNotEmpty()) parentPath(filePath) else normalizePath(sent)
    return SearchHit(name, normalized.ifBlank { filePath.ifBlank { sent } }, snippet, openPath)
}

private fun progressLine(data: JSONObject): String {
    val count = when {
        data.has("files_searched") -> data.optInt("files_searched")
        data.has("scanned") -> data.optInt("scanned")
        else -> -1
    }
    val file = data.optString("file_path").ifBlank { data.optString("path") }
    val built = buildString {
        if (count >= 0) append("$count files scanned")
        if (file.isNotEmpty()) {
            if (isNotEmpty()) append(" · ")
            append(file)
        }
    }
    return built.ifBlank { data.optString("message").ifBlank { "Searching…" } }
}
