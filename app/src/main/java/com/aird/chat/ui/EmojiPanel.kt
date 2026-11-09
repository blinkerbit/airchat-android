package com.aird.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.emoji2.emojipicker.EmojiPickerView
import com.aird.chat.data.EmojiPreferences
import com.aird.chat.data.EmojiSearchIndex
import kotlinx.coroutines.delay
import java.text.BreakIterator

@Composable
fun EmojiPanel(
    modifier: Modifier = Modifier,
    showBackspace: Boolean = false,
    onBackspace: () -> Unit = {},
    onPick: (String) -> Unit,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var recentTick by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    var currentSkin by remember { mutableStateOf(EmojiPreferences.getSkinTone(context)) }
    val surface = MaterialTheme.colorScheme.surface.toArgb()

    val filteredEmojis = remember(searchQuery) {
        if (searchQuery.isBlank()) emptyList()
        else EmojiSearchIndex.search(searchQuery)
    }

    Column(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        // Drag handle indicator
        Box(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier.width(36.dp).height(4.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
            ) {}
        }

        // Top Search Bar & Skin Tone Bar Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search emojis…", fontSize = 13.sp) },
                leadingIcon = {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = "Search",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.weight(1f).height(46.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp)
            )

            Spacer(Modifier.width(8.dp))

            // Skin Tone Quick Bar
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                EmojiPreferences.allSkins.forEach { skin ->
                    val isSelected = currentSkin == skin.id
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                else Color.Transparent
                            )
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                currentSkin = skin.id
                                EmojiPreferences.setSkinTone(context, skin.id)
                                recentTick += 1
                            }
                    ) {
                        Text(skin.preview, fontSize = 14.sp)
                    }
                }
            }
        }

        // Emoji Grid / Picker Content Area
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            if (searchQuery.isNotBlank()) {
                if (filteredEmojis.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "No emojis matching \"$searchQuery\"",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(8),
                        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                        contentPadding = PaddingValues(bottom = 56.dp)
                    ) {
                        items(filteredEmojis) { baseEmoji ->
                            val chosen = EmojiPreferences.applySkinTone(baseEmoji, context)
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        EmojiPreferences.remember(context, chosen)
                                        recentTick += 1
                                        onPick(chosen)
                                    }
                            ) {
                                Text(chosen, fontSize = 22.sp)
                            }
                        }
                    }
                }
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        EmojiPickerView(ctx).apply { setBackgroundColor(surface) }
                    },
                    update = { view ->
                        view.setBackgroundColor(surface)
                        view.setOnEmojiPickedListener { item ->
                            val raw = item.emoji
                            if (raw.isNotEmpty()) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                val chosen = EmojiPreferences.applySkinTone(raw, view.context)
                                EmojiPreferences.remember(view.context, chosen)
                                recentTick += 1
                                onPick(chosen)
                            }
                        }
                    },
                )
            }

            // Auto-repeating Floating Backspace Action Button
            if (showBackspace) {
                AutoRepeatingBackspacePill(
                    onBackspace = onBackspace,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = 12.dp, end = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun AutoRepeatingBackspacePill(
    onBackspace: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    var isPressed by remember { mutableStateOf(false) }

    LaunchedEffect(isPressed) {
        if (isPressed) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onBackspace()
            delay(380) // Initial press delay
            while (isPressed) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onBackspace()
                delay(65) // Rapid repeat delay
            }
        }
    }

    Surface(
        modifier = modifier
            .size(44.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        tryAwaitRelease()
                        isPressed = false
                    }
                )
            },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 4.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.AutoMirrored.Filled.Backspace,
                contentDescription = "Delete emoji",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

fun insertAtCursor(text: String, cursor: Int, insert: String): Pair<String, Int> {
    val at = cursor.coerceIn(0, text.length)
    val next = text.substring(0, at) + insert + text.substring(at)
    return next to (at + insert.length)
}

fun deleteBeforeCursor(text: String, cursor: Int): Pair<String, Int> {
    if (text.isEmpty() || cursor <= 0) return text to cursor.coerceAtLeast(0)
    val end = cursor.coerceIn(0, text.length)
    val breaks = BreakIterator.getCharacterInstance()
    breaks.setText(text)
    val start = breaks.preceding(end)
    val from = if (start == BreakIterator.DONE) 0 else start
    return text.removeRange(from, end) to from
}
