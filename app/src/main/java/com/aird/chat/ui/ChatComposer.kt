package com.aird.chat.ui

import android.net.Uri
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.widget.doAfterTextChanged
import com.aird.ui.LocalAirdReducedMotion
import com.aird.ui.PressIconButton

private val Emoji = listOf(
    "😀", "😁", "😂", "🤣", "😊", "😍", "😘", "😎", "🤩", "🥳", "😅", "😉",
    "😢", "😭", "😡", "🤔", "😴", "🤗", "👍", "👎", "👏", "🙏", "💪", "🔥",
    "❤️", "💙", "💚", "💛", "💜", "🖤", "💯", "✨", "🎉", "🎂", "🌟", "🌈",
    "☕", "🍕", "🚀", "📷", "🎵", "💬", "📞", "📍", "✅", "❌", "🤝", "💐",
)

@Composable
fun Composer(
    draft: String,
    onDraft: (String) -> Unit,
    panel: String,
    onPanel: (String) -> Unit,
    reply: String,
    onClearReply: () -> Unit,
    uploading: Boolean,
    onAttach: () -> Unit,
    onSend: () -> Unit,
    onContent: (Uri, String) -> Unit,
) {
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val hintColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f).toArgb()
    val reduced = LocalAirdReducedMotion.current
    val panelEnter = if (reduced) EnterTransition.None else fadeIn(tween(200)) + expandVertically(tween(220))
    val panelExit = if (reduced) ExitTransition.None else fadeOut(tween(180)) + shrinkVertically(tween(200))
    var shownReply by remember { mutableStateOf(reply) }
    if (reply.isNotEmpty()) shownReply = reply
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        AnimatedVisibility(visible = reply.isNotEmpty(), enter = panelEnter, exit = panelExit) {
            androidx.compose.foundation.layout.Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(shownReply, modifier = Modifier.weight(1f), maxLines = 1, color = MaterialTheme.colorScheme.primary)
                Text("✕", modifier = Modifier.clickable(enabled = reply.isNotEmpty(), onClick = onClearReply).padding(8.dp))
            }
        }
        if (uploading) {
            Text("Sending file…", modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.primary)
        }
        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onAttach) { Icon(Icons.Default.AttachFile, contentDescription = "Attach") }
            IconButton(onClick = { onPanel("emoji") }) { Icon(Icons.Default.EmojiEmotions, contentDescription = "Emoji") }
            AndroidView(
                modifier = Modifier.weight(1f).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(24.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
                factory = { context ->
                    EditText(context).apply {
                        background = null
                        hint = "Message"
                        textSize = 16f
                        minLines = 1
                        maxLines = 5
                        imeOptions = EditorInfo.IME_ACTION_SEND
                        setOnEditorActionListener { _, actionId, _ ->
                            if (actionId == EditorInfo.IME_ACTION_SEND) {
                                onSend()
                                true
                            } else {
                                false
                            }
                        }
                        doAfterTextChanged { editable -> onDraft(editable?.toString().orEmpty()) }
                        ViewCompat.setOnReceiveContentListener(this, arrayOf("image/*", "image/gif")) { _, payload ->
                            val clip = payload.clip
                            for (i in 0 until clip.itemCount) {
                                val uri = clip.getItemAt(i).uri ?: continue
                                val mime = runCatching { clip.description.getMimeType(i) }.getOrNull()
                                    ?: context.contentResolver.getType(uri)
                                    ?: "image/*"
                                if (mime.startsWith("image/")) onContent(uri, mime)
                            }
                            null
                        }
                    }
                },
                update = { edit ->
                    edit.setTextColor(textColor)
                    edit.setHintTextColor(hintColor)
                    if (edit.text.toString() != draft) {
                        edit.setText(draft)
                        edit.setSelection(draft.length)
                    }
                },
            )
            PressIconButton(onClick = onSend) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = MaterialTheme.colorScheme.primary) }
        }
        AnimatedVisibility(visible = panel == "emoji", enter = panelEnter, exit = panelExit) {
            LazyVerticalGrid(columns = GridCells.Fixed(8), modifier = Modifier.fillMaxWidth().height(240.dp), contentPadding = PaddingValues(8.dp)) {
                items(Emoji) { item ->
                    Box(
                        Modifier.size(40.dp).clickable(enabled = panel == "emoji") { onDraft(draft + item) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(item, fontSize = 24.sp)
                    }
                }
            }
        }
    }
}
