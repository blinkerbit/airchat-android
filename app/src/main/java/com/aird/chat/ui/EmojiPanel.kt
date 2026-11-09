package com.aird.chat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.emoji2.emojipicker.EmojiPickerView
import com.aird.chat.data.EmojiPreferences
import java.text.BreakIterator

@Composable
fun EmojiPanel(
    modifier: Modifier = Modifier,
    showBackspace: Boolean = false,
    onBackspace: () -> Unit = {},
    onPick: (String) -> Unit,
) {
    val context = LocalContext.current
    var recentTick by remember { mutableIntStateOf(0) }
    val recent = remember(recentTick) { EmojiPreferences.recent(context) }
    val surface = MaterialTheme.colorScheme.surface.toArgb()
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().height(44.dp).padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (recent.isEmpty()) {
                    Text(
                        "Emoji",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                } else {
                    recent.forEach { emoji ->
                        Text(
                            emoji,
                            fontSize = 22.sp,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable {
                                    EmojiPreferences.remember(context, emoji)
                                    recentTick += 1
                                    onPick(emoji)
                                }
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            if (showBackspace) {
                IconButton(onClick = onBackspace, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.Backspace,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = { ctx ->
                EmojiPickerView(ctx).apply { setBackgroundColor(surface) }
            },
            update = { view ->
                view.setBackgroundColor(surface)
                view.setOnEmojiPickedListener { item ->
                    val emoji = item.emoji
                    if (emoji.isNotEmpty()) {
                        EmojiPreferences.remember(view.context, emoji)
                        recentTick += 1
                        onPick(emoji)
                    }
                }
            },
        )
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
