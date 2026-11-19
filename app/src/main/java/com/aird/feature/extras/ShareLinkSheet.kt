package com.aird.feature.extras

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aird.ServerStore
import com.aird.designsystem.components.AirdSheet
import com.aird.designsystem.components.SwitchRow
import com.aird.designsystem.components.SegmentedGroup
import com.aird.designsystem.theme.Spacing
import com.aird.session.AirdHttp
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.temporal.ChronoUnit

data class CreatedShare(val id: String, val url: String, val secretToken: String?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareLinkSheet(
    paths: List<String>,
    onDismiss: () -> Unit,
    onCreated: (CreatedShare) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var publicLink by remember { mutableStateOf(true) }
    var expiryDays by remember { mutableStateOf("7") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var created by remember { mutableStateOf<CreatedShare?>(null) }

    AirdSheet(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.md)) {
            Text("Share link", style = MaterialTheme.typography.headlineSmall)
            Text(
                paths.joinToString(", ").ifBlank { "Selected files" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.lg))
            if (created == null) {
                SegmentedGroup {
                    SwitchRow(
                        title = "Public link",
                        subtitle = "Anyone with the link can open it",
                        checked = publicLink,
                        onCheckedChange = { publicLink = it },
                    )
                }
                Spacer(Modifier.height(Spacing.md))
                OutlinedTextField(
                    value = expiryDays,
                    onValueChange = { expiryDays = it.filter { ch -> ch.isDigit() }.take(3) },
                    label = { Text("Expires in days") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                if (error.isNotBlank()) {
                    Spacer(Modifier.height(Spacing.sm))
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(Spacing.lg))
                Button(
                    onClick = {
                        if (busy) return@Button
                        scope.launch {
                            busy = true
                            error = ""
                            val result = createShare(
                                context,
                                paths,
                                disableToken = publicLink,
                                expiryDays = expiryDays.toIntOrNull() ?: 7,
                            )
                            busy = false
                            if (result == null) {
                                error = "Could not create share"
                            } else {
                                created = result
                                onCreated(result)
                            }
                        }
                    },
                    enabled = !busy && paths.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (busy) "Creating…" else "Create link") }
            } else {
                val share = created!!
                val absolute = AirdHttp.absolute(context, share.url)
                Image(
                    painter = rememberQrCodePainter(absolute),
                    contentDescription = "QR code",
                    modifier = Modifier.size(180.dp).align(Alignment.CenterHorizontally),
                )
                Spacer(Modifier.height(Spacing.md))
                Text(absolute, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(Spacing.md))
                Button(
                    onClick = {
                        copyText(context, absolute)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Copy link") }
                if (!share.secretToken.isNullOrBlank()) {
                    TextButton(onClick = { copyText(context, share.secretToken) }) { Text("Copy token") }
                }
            }
            Spacer(Modifier.height(Spacing.xl))
        }
    }
}

suspend fun createShare(
    context: Context,
    paths: List<String>,
    disableToken: Boolean,
    expiryDays: Int,
): CreatedShare? {
    val expiry = Instant.now().plus(expiryDays.toLong().coerceIn(1, 365), ChronoUnit.DAYS).toString()
    val body = JSONObject()
        .put("paths", JSONArray(paths.map { it.trim().trim('/') }))
        .put("share_type", "static")
        .put("disable_token", disableToken)
        .put("expiry_date", expiry)
        .put("allowed_users", JSONArray())
    val response = try {
        AirdHttp.send(context, "/share/create", "POST", body)
    } catch (_: Exception) {
        return null
    }
    if (!response.ok) return null
    val json = response.json()
    val id = json.optString("id")
    val url = json.optString("url").ifBlank { "/shared/$id" }
    if (id.isEmpty()) return null
    return CreatedShare(id, url, json.optString("secret_token").ifBlank { null })
}

fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Aird", text))
}

fun shareAbsoluteUrl(context: Context, shareId: String): String =
    AirdHttp.absolute(context, "/shared/$shareId").ifBlank { ServerStore.get(context) + "/shared/$shareId" }
