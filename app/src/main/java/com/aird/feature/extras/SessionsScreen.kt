package com.aird.feature.extras

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.PhonelinkErase
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aird.designsystem.components.AirdLargeTopBar
import com.aird.designsystem.components.BackButton
import com.aird.designsystem.components.EmptyState
import com.aird.designsystem.components.SectionHeader
import com.aird.designsystem.components.SegmentedGroup
import com.aird.designsystem.components.SettingRow
import com.aird.session.AirdHttp
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private data class DeviceSession(
    val id: String,
    val ip: String,
    val userAgent: String,
    val lastActive: String,
    val current: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sessions by remember { mutableStateOf<List<DeviceSession>>(emptyList()) }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    suspend fun reload() {
        loading = true
        try {
            val response = AirdHttp.get(context, "/api/sessions")
            if (!response.ok) {
                error = response.json().optString("error").ifBlank { "Could not load sessions" }
                sessions = emptyList()
                return
            }
            val arr = response.json().optJSONArray("sessions")
            sessions = buildList {
                if (arr == null) return@buildList
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("id")
                    if (id.isEmpty()) continue
                    add(
                        DeviceSession(
                            id = id,
                            ip = obj.optString("ip_address"),
                            userAgent = obj.optString("user_agent").ifBlank { "Unknown device" },
                            lastActive = formatTs(obj.optString("last_active_at")),
                            current = obj.optBoolean("current"),
                        ),
                    )
                }
            }
            error = ""
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            error = e.message ?: "Could not load sessions"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            AirdLargeTopBar(title = "Active devices", scrollBehavior = scroll, navigationIcon = { BackButton(onClick = onBack) })
        },
    ) { padding ->
        when {
            loading && sessions.isEmpty() -> Text("Loading…", Modifier.padding(padding).padding(24.dp))
            sessions.isEmpty() -> EmptyState(
                icon = Icons.Rounded.Devices,
                title = "No sessions",
                body = error.ifBlank { "Signed-in devices will show up here." },
                modifier = Modifier.padding(padding),
            )
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 48.dp),
            ) {
                item { SectionHeader("Signed in") }
                item {
                    SegmentedGroup {
                        sessions.forEach { session ->
                            SettingRow(
                                title = deviceLabel(session.userAgent),
                                subtitle = buildString {
                                    if (session.current) append("This device · ")
                                    if (session.ip.isNotBlank()) append(session.ip).append(" · ")
                                    append(session.lastActive)
                                },
                                icon = Icons.Rounded.Devices,
                                trailing = if (session.current) {
                                    { Text("Current", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                                } else {
                                    null
                                },
                                onClick = if (session.current) null else {
                                    {
                                        scope.launch {
                                            revoke(context, session.id)
                                            reload()
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
                val others = sessions.filterNot { it.current }
                if (others.isNotEmpty()) {
                    item { SectionHeader("Revoke") }
                    item {
                        SegmentedGroup {
                            others.forEach { session ->
                                SettingRow(
                                    title = "Revoke ${deviceLabel(session.userAgent)}",
                                    icon = Icons.Rounded.PhonelinkErase,
                                    destructive = true,
                                    onClick = {
                                        scope.launch {
                                            revoke(context, session.id)
                                            reload()
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

private suspend fun revoke(context: android.content.Context, id: String) {
    val encoded = URLEncoder.encode(id, "UTF-8")
    try {
        AirdHttp.send(context, "/api/sessions/$encoded/revoke", "POST", JSONObject())
    } catch (_: Exception) {
    }
}

private fun deviceLabel(ua: String): String {
    val lower = ua.lowercase()
    return when {
        "android" in lower -> "Android"
        "iphone" in lower || "ipad" in lower -> "iOS"
        "windows" in lower -> "Windows"
        "macintosh" in lower || "mac os" in lower -> "Mac"
        "linux" in lower -> "Linux"
        ua.isBlank() -> "Unknown device"
        else -> ua.take(48)
    }
}

private fun formatTs(raw: String): String {
    if (raw.isBlank()) return "Unknown"
    return try {
        val instant = Instant.parse(raw.replace(' ', 'T').let { if (it.endsWith("Z") || '+' in it) it else "${it}Z" })
        DateTimeFormatter.ofPattern("MMM d · HH:mm").withZone(ZoneId.systemDefault()).format(instant)
    } catch (_: Exception) {
        raw.take(19)
    }
}
