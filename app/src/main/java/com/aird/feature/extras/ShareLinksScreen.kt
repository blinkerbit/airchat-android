package com.aird.feature.extras

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aird.designsystem.components.AirdLargeTopBar
import com.aird.designsystem.components.AirdSheet
import com.aird.designsystem.components.BackButton
import com.aird.designsystem.components.EmptyState
import com.aird.designsystem.components.SectionHeader
import com.aird.designsystem.components.SegmentedGroup
import com.aird.designsystem.components.SettingRow
import com.aird.designsystem.theme.Spacing
import com.aird.session.AirdHttp
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import kotlinx.coroutines.launch
import org.json.JSONObject

private data class ShareRow(
    val id: String,
    val paths: List<String>,
    val expiry: String,
    val secretToken: String?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareLinksScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var shares by remember { mutableStateOf<List<ShareRow>>(emptyList()) }
    var error by remember { mutableStateOf("") }
    var qrFor by remember { mutableStateOf<ShareRow?>(null) }
    var revokeId by remember { mutableStateOf<String?>(null) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    suspend fun reload() {
        try {
            val response = AirdHttp.get(context, "/share/list")
            if (!response.ok) {
                error = response.json().optString("error").ifBlank { "Could not load shares" }
                shares = emptyList()
                return
            }
            val map = response.json().optJSONObject("shares") ?: JSONObject()
            shares = buildList {
                map.keys().forEach { id ->
                    val obj = map.optJSONObject(id) ?: return@forEach
                    val paths = buildList {
                        val arr = obj.optJSONArray("paths")
                        if (arr != null) for (i in 0 until arr.length()) {
                            val p = arr.optString(i)
                            if (p.isNotBlank()) add(p)
                        }
                    }
                    add(
                        ShareRow(
                            id = id,
                            paths = paths,
                            expiry = obj.optString("expiry_date"),
                            secretToken = obj.optString("secret_token").ifBlank { null },
                        ),
                    )
                }
            }.sortedBy { it.id }
            error = ""
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            error = e.message ?: "Could not load shares"
        }
    }

    LaunchedEffect(Unit) { reload() }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            AirdLargeTopBar(title = "My share links", scrollBehavior = scroll, navigationIcon = { BackButton(onClick = onBack) })
        },
    ) { padding ->
        if (shares.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.Link,
                title = "No share links",
                body = error.ifBlank { "Create a link from Files to share folders or files." },
                modifier = Modifier.padding(padding),
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 48.dp),
            ) {
                item { SectionHeader("${shares.size} links") }
                items(shares, key = { it.id }) { share ->
                    val url = shareAbsoluteUrl(context, share.id)
                    SegmentedGroup(Modifier.padding(bottom = Spacing.sm)) {
                        SettingRow(
                            title = share.paths.firstOrNull()?.substringAfterLast('/') ?: share.id.take(12),
                            subtitle = buildString {
                                append(share.paths.size).append(if (share.paths.size == 1) " path" else " paths")
                                if (share.expiry.isNotBlank()) append(" · expires ").append(share.expiry.take(10))
                            },
                            icon = Icons.Rounded.Link,
                        )
                        SettingRow(
                            title = "Copy link",
                            icon = Icons.Rounded.ContentCopy,
                            onClick = { copyText(context, url) },
                        )
                        SettingRow(
                            title = "Show QR",
                            icon = Icons.Rounded.QrCode2,
                            onClick = { qrFor = share },
                        )
                        SettingRow(
                            title = "Revoke",
                            icon = Icons.Rounded.DeleteOutline,
                            destructive = true,
                            onClick = { revokeId = share.id },
                        )
                    }
                }
            }
        }
    }

    qrFor?.let { share ->
        val url = shareAbsoluteUrl(context, share.id)
        AirdSheet(onDismiss = { qrFor = null }) {
            Column(
                Modifier.fillMaxWidth().padding(Spacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Share QR", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(Spacing.lg))
                Image(
                    painter = rememberQrCodePainter(url),
                    contentDescription = "QR",
                    modifier = Modifier.size(200.dp),
                )
                Spacer(Modifier.height(Spacing.md))
                Text(url, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { copyText(context, url) }) { Text("Copy link") }
            }
        }
    }

    revokeId?.let { id ->
        AlertDialog(
            onDismissRequest = { revokeId = null },
            title = { Text("Revoke share?") },
            text = { Text("People with this link will lose access.") },
            confirmButton = {
                TextButton(onClick = {
                    revokeId = null
                    scope.launch {
                        try {
                            AirdHttp.postForm(context, "/share/revoke", mapOf("id" to id))
                        } catch (_: Exception) {
                        }
                        reload()
                    }
                }) { Text("Revoke", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { revokeId = null }) { Text("Cancel") } },
        )
    }
}
