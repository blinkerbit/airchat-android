package com.aird.chat.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aird.data.ChatPrefsStore
import com.aird.designsystem.theme.Spacing

private val solidColors = listOf(
    Color(0xFF1E293B),
    Color(0xFF0F766E),
    Color(0xFF1D4ED8),
    Color(0xFF7C3AED),
    Color(0xFFBE123C),
    Color(0xFFCA8A04),
)

private val gradients = listOf(
    "gradient:#0F172A,#1E3A8A",
    "gradient:#134E4A,#0F766E",
    "gradient:#4C1D95,#DB2777",
    "gradient:#7F1D1D,#EA580C",
)

private val accents = listOf(
    Color(0xFF2563EB),
    Color(0xFF059669),
    Color(0xFFD97706),
    Color(0xFFDC2626),
    Color(0xFF7C3AED),
    Color(0xFFDB2777),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatAppearanceSheet(
    conversationId: String,
    onDismiss: () -> Unit,
) {
    val prefsMap by ChatPrefsStore.prefs.collectAsStateWithLifecycle()
    val prefs = prefsMap[conversationId] ?: com.aird.data.db.ConversationPrefsEntity(conversationId)
    var dim by remember(prefs.wallpaperDim) { mutableFloatStateOf(prefs.wallpaperDim) }
    var wallpaper by remember(prefs.wallpaper) { mutableStateOf(prefs.wallpaper) }
    var accent by remember(prefs.accent) { mutableStateOf(prefs.accent) }

    val photo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            wallpaper = "photo:${uri}"
            ChatPrefsStore.setWallpaper(conversationId, wallpaper, dim)
        }
    }

    fun saveWallpaper(value: String) {
        wallpaper = value
        ChatPrefsStore.setWallpaper(conversationId, value, dim)
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.xl, vertical = Spacing.md),
        ) {
            Text("Chat appearance", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(Spacing.lg))
            Text("Solid", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth().padding(vertical = Spacing.sm), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                solidColors.forEach { color ->
                    val token = "solid:#${Integer.toHexString(color.toArgb()).uppercase()}"
                    Swatch(
                        brush = Brush.linearGradient(listOf(color, color)),
                        selected = wallpaper == token,
                        onClick = { saveWallpaper(token) },
                    )
                }
            }
            Text("Gradient", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth().padding(vertical = Spacing.sm), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                gradients.forEach { token ->
                    val parts = token.removePrefix("gradient:").split(",")
                    val c1 = parseColor(parts.getOrNull(0).orEmpty())
                    val c2 = parseColor(parts.getOrNull(1).orEmpty())
                    Swatch(
                        brush = Brush.verticalGradient(listOf(c1, c2)),
                        selected = wallpaper == token,
                        onClick = { saveWallpaper(token) },
                    )
                }
            }
            TextButton(
                onClick = {
                    photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
            ) { Text("Choose photo") }
            TextButton(
                onClick = {
                    wallpaper = ""
                    dim = 0f
                    ChatPrefsStore.setWallpaper(conversationId, "", 0f)
                },
            ) { Text("Clear wallpaper") }
            Text("Dim", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = Spacing.md))
            Slider(
                value = dim,
                onValueChange = { dim = it },
                onValueChangeFinished = { ChatPrefsStore.setWallpaper(conversationId, wallpaper, dim) },
                valueRange = 0f..0.85f,
            )
            Text("Accent", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth().padding(vertical = Spacing.sm), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                accents.forEach { color ->
                    val value = color.toArgb().toLong() and 0xFFFFFFFFL
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(color)
                            .border(
                                width = if (accent == value) 3.dp else 0.dp,
                                color = MaterialTheme.colorScheme.onSurface,
                                shape = CircleShape,
                            )
                            .clickable {
                                accent = value
                                ChatPrefsStore.setAccent(conversationId, value)
                            },
                    )
                }
                TextButton(onClick = {
                    accent = null
                    ChatPrefsStore.setAccent(conversationId, null)
                }) { Text("Default") }
            }
            Spacer(Modifier.height(Spacing.xxl))
        }
    }
}

@Composable
private fun Swatch(brush: Brush, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(brush)
            .border(
                width = if (selected) 3.dp else 0.dp,
                color = MaterialTheme.colorScheme.onSurface,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
    )
}

fun parseWallpaperBrush(token: String): Brush? {
    return when {
        token.startsWith("solid:") -> {
            val c = parseColor(token.removePrefix("solid:"))
            Brush.linearGradient(listOf(c, c))
        }
        token.startsWith("gradient:") -> {
            val parts = token.removePrefix("gradient:").split(",")
            Brush.verticalGradient(listOf(parseColor(parts.getOrNull(0).orEmpty()), parseColor(parts.getOrNull(1).orEmpty())))
        }
        else -> null
    }
}

fun wallpaperPhotoUri(token: String): Uri? =
    if (token.startsWith("photo:")) Uri.parse(token.removePrefix("photo:")) else null

private fun parseColor(raw: String): Color {
    val hex = raw.trim().removePrefix("#")
    return try {
        Color(android.graphics.Color.parseColor("#$hex"))
    } catch (_: Exception) {
        Color(0xFF1E293B)
    }
}
