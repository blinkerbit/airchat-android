package com.aird.feature.extras

import android.content.Intent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PieChart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.aird.app.LocalNavigator
import com.aird.chat.data.FilesRepository
import com.aird.designsystem.components.AirdLargeTopBar
import com.aird.designsystem.components.BackButton
import com.aird.designsystem.components.EmptyState
import com.aird.designsystem.theme.Spacing
import com.aird.security.HostPolicy
import com.aird.session.AirdHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

private data class StorageNode(
    val name: String,
    val path: String,
    val bytes: Long,
    val dir: Boolean,
    val sizeLabel: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(path: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    var nodes by remember { mutableStateOf<List<StorageNode>>(emptyList()) }
    var totalLabel by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var downloading by remember { mutableStateOf(false) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val colors = MaterialTheme.colorScheme
    val palette = remember(colors) {
        listOf(
            colors.primary,
            colors.secondary,
            colors.tertiary,
            colors.primaryContainer,
            colors.secondaryContainer,
            colors.tertiaryContainer,
            colors.error,
            colors.outline,
        )
    }

    suspend fun reload() {
        loading = true
        error = ""
        try {
            val clean = path.trim().trim('/')
            val total = folderSize(context, clean)
            totalLabel = total?.sizeLabel.orEmpty()
            val entries = FilesRepository.listFiles(context, clean).orEmpty()
            nodes = coroutineScope {
                entries.map { entry ->
                    async {
                        if (!entry.dir) {
                            StorageNode(
                                name = entry.name,
                                path = entry.path,
                                bytes = entry.sizeBytes.coerceAtLeast(0),
                                dir = false,
                                sizeLabel = entry.size.ifBlank { formatBytes(entry.sizeBytes) },
                            )
                        } else {
                            val size = folderSize(context, entry.path)
                            StorageNode(
                                name = entry.name,
                                path = entry.path,
                                bytes = size?.bytes ?: 0L,
                                dir = true,
                                sizeLabel = size?.sizeLabel ?: "—",
                            )
                        }
                    }
                }.awaitAll().filter { it.bytes > 0 || it.dir }.sortedByDescending { it.bytes }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            error = e.message ?: "Could not load storage"
            nodes = emptyList()
        } finally {
            loading = false
        }
    }

    LaunchedEffect(path) { reload() }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            AirdLargeTopBar(
                title = path.trim('/').ifBlank { "Storage" }.substringAfterLast('/').ifBlank { "Storage" },
                scrollBehavior = scroll,
                navigationIcon = { BackButton(onClick = onBack) },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg),
        ) {
            Text(
                totalLabel.ifBlank { if (loading) "Calculating…" else path.ifBlank { "/" } },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.md))
            FilledTonalButton(
                onClick = {
                    if (downloading) return@FilledTonalButton
                    scope.launch {
                        downloading = true
                        try {
                            downloadZip(context, path.trim().trim('/'))
                        } finally {
                            downloading = false
                        }
                    }
                },
                enabled = !downloading,
            ) {
                Icon(Icons.Rounded.Download, contentDescription = null)
                Spacer(Modifier.padding(horizontal = 4.dp))
                Text(if (downloading) "Preparing zip…" else "Download zip")
            }
            Spacer(Modifier.height(Spacing.lg))
            when {
                loading && nodes.isEmpty() -> Text("Scanning…")
                error.isNotBlank() && nodes.isEmpty() -> EmptyState(Icons.Rounded.PieChart, "Could not scan", body = error)
                nodes.isEmpty() -> EmptyState(Icons.Rounded.PieChart, "Empty folder", body = "Nothing to show in this path.")
                else -> {
                    val progress = remember { Animatable(0f) }
                    LaunchedEffect(nodes) {
                        progress.snapTo(0f)
                        progress.animateTo(1f, tween(520))
                    }
                    val layout = remember(nodes) { squarify(nodes, 1f, 1f) }
                    Canvas(
                        Modifier
                            .fillMaxWidth()
                            .height(320.dp)
                            .pointerInput(layout) {
                                detectTapGestures { offset ->
                                    val w = size.width.toFloat()
                                    val h = size.height.toFloat()
                                    val hit = layout.firstOrNull { cell ->
                                        offset.x >= cell.rect.left * w &&
                                            offset.x <= cell.rect.right * w &&
                                            offset.y >= cell.rect.top * h &&
                                            offset.y <= cell.rect.bottom * h
                                    } ?: return@detectTapGestures
                                    if (hit.node.dir) navigator.openStorage(hit.node.path)
                                }
                            },
                    ) {
                        val w = size.width
                        val h = size.height
                        layout.forEachIndexed { index, cell ->
                            val rect = Rect(
                                Offset(cell.rect.left * w, cell.rect.top * h),
                                Size(cell.rect.width * w * progress.value, cell.rect.height * h * progress.value),
                            )
                            if (rect.width <= 1f || rect.height <= 1f) return@forEachIndexed
                            drawRect(palette[index % palette.size].copy(alpha = 0.85f), topLeft = rect.topLeft, size = rect.size)
                            drawRect(Color.Black.copy(alpha = 0.12f), topLeft = rect.topLeft, size = rect.size, style = Stroke(2f))
                        }
                    }
                    Spacer(Modifier.height(Spacing.lg))
                    nodes.forEachIndexed { index, node ->
                        Text(
                            "${node.name} · ${node.sizeLabel}",
                            color = palette[index % palette.size],
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(48.dp))
        }
    }
}

private data class FolderSize(val bytes: Long, val sizeLabel: String)

private suspend fun folderSize(context: android.content.Context, path: String): FolderSize? {
    val q = URLEncoder.encode(path, "UTF-8")
    val response = try {
        AirdHttp.get(context, "/api/folder-size?path=$q")
    } catch (_: Exception) {
        return null
    }
    if (!response.ok) return null
    val json = response.json()
    return FolderSize(json.optLong("bytes"), json.optString("size_str").ifBlank { formatBytes(json.optLong("bytes")) })
}

private data class TreeCell(val node: StorageNode, val rect: Rect)

private fun squarify(nodes: List<StorageNode>, width: Float, height: Float): List<TreeCell> {
    val total = nodes.sumOf { it.bytes }.coerceAtLeast(1)
    val remaining = nodes.toMutableList()
    val out = mutableListOf<TreeCell>()
    var x = 0f
    var y = 0f
    var w = width
    var h = height
    while (remaining.isNotEmpty()) {
        val vertical = w >= h
        val side = if (vertical) h else w
        val row = mutableListOf<StorageNode>()
        var rowSum = 0L
        while (remaining.isNotEmpty()) {
            val next = remaining.first()
            val trial = row + next
            val trialSum = rowSum + next.bytes
            if (row.isEmpty() || worst(row, rowSum, side, total, w, h) >= worst(trial, trialSum, side, total, w, h)) {
                remaining.removeAt(0)
                row += next
                rowSum = trialSum
            } else break
        }
        val rowFrac = rowSum.toFloat() / total.toFloat()
        if (vertical) {
            val rowW = w * rowFrac
            var cursor = y
            row.forEach { node ->
                val nh = h * (node.bytes.toFloat() / rowSum.toFloat())
                out += TreeCell(node, Rect(Offset(x, cursor), Size(rowW, nh)))
                cursor += nh
            }
            x += rowW
            w -= rowW
        } else {
            val rowH = h * rowFrac
            var cursor = x
            row.forEach { node ->
                val nw = w * (node.bytes.toFloat() / rowSum.toFloat())
                out += TreeCell(node, Rect(Offset(cursor, y), Size(nw, rowH)))
                cursor += nw
            }
            y += rowH
            h -= rowH
        }
    }
    return out
}

private fun worst(row: List<StorageNode>, rowSum: Long, side: Float, total: Long, w: Float, h: Float): Float {
    if (row.isEmpty() || rowSum <= 0) return Float.MAX_VALUE
    val area = (if (w >= h) w else h) * side * (rowSum.toFloat() / total.toFloat())
    val s = side.coerceAtLeast(0.0001f)
    var worst = 0f
    row.forEach { node ->
        val a = area * (node.bytes.toFloat() / rowSum.toFloat())
        val ratio = maxOf(s * s * a / (a * a + 0.0001f), a / (s * s + 0.0001f))
        worst = maxOf(worst, ratio)
    }
    return worst
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = bytes / 1024.0
    var i = 0
    while (v >= 1024 && i < units.lastIndex) {
        v /= 1024
        i++
    }
    return "%.1f %s".format(v, units[i])
}

private suspend fun downloadZip(context: android.content.Context, path: String) = withContext(Dispatchers.IO) {
    val server = com.aird.ServerStore.get(context)
    val url = AirdHttp.absolute(context, "/api/download/zip")
    if (!HostPolicy.isSafeRemoteUrl(url)) return@withContext
    val name = (path.substringAfterLast('/').ifBlank { "aird" }) + ".zip"
    val payload = JSONObject().put("paths", JSONArray().put(path.ifBlank { "." })).put("filename", name)
    val dest = File(context.cacheDir, "zips").apply { mkdirs() }.resolve(name)
    val builder = Request.Builder()
        .url(url)
        .header("Accept", "application/zip")
        .header("Origin", server)
        .header("Content-Type", "application/json")
    val token = AirdHttp.xsrf(server)
    if (token.isNotEmpty()) builder.header("X-XSRFToken", token)
    builder.post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
    AirdHttp.client.newCall(builder.build()).execute().use { response ->
        if (!response.isSuccessful) return@withContext
        response.body.byteStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", dest)
    val share = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    withContext(Dispatchers.Main) {
        context.startActivity(Intent.createChooser(share, "Share zip").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
