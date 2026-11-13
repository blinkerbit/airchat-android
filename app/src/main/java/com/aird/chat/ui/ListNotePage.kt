package com.aird.chat.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aird.chat.model.ListEntry
import com.aird.ui.LocalAirdReducedMotion

@Composable
fun ListNotePage(
    item: ListEntry,
    notes: List<ListEntry>,
    onBack: () -> Unit,
    onSave: (String, () -> Unit) -> Unit,
    onOpen: (ListEntry) -> Unit,
) {
    val reduced = LocalAirdReducedMotion.current
    var value by remember(item.id) { mutableStateOf(TextFieldValue(item.text)) }
    var writing by remember(item.id) { mutableStateOf(true) }
    var error by remember(item.id) { mutableStateOf("") }
    val motion = if (reduced) 0 else 60
    val mention = mentionQuery(value)
    val matches = if (mention == null) emptyList() else notes.filter {
        it.id != item.id && noteTitle(it.text).contains(mention, true)
    }.take(6)

    fun leave() {
        val text = value.text.trim()
        if (text.isEmpty()) {
            error = "Add a title before leaving this note."
            return
        }
        if (text != item.text) onSave(text, onBack) else onBack()
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { leave() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(noteTitle(value.text), modifier = Modifier.weight(1f), maxLines = 1, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = { writing = !writing }) { Text(if (writing) "Preview" else "Write") }
        }
        if (error.isNotEmpty()) {
            Text(error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp))
        }
        AnimatedContent(
            targetState = writing,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            transitionSpec = { fadeIn(tween(motion)) togetherWith fadeOut(tween(motion)) },
            label = "note",
        ) { edit ->
            if (edit) {
                BasicTextField(
                    value = value,
                    onValueChange = {
                        value = it
                        error = ""
                    },
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp),
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                )
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    noteBlocks(value.text).forEach { block ->
                        Text(block.text, fontWeight = block.weight, fontSize = block.size, modifier = Modifier.padding(bottom = 6.dp))
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (mention != null) {
                if (matches.isEmpty()) Text("No notes", modifier = Modifier.padding(8.dp), fontSize = 13.sp)
                matches.forEach { note ->
                    TextButton(onClick = {
                        value = insertMention(value, note.id)
                    }) { Text(noteTitle(note.text), maxLines = 1) }
                }
            } else if (writing) {
                TextButton(onClick = { value = insertAtLine(value, "# ") }) { Text("H") }
                TextButton(onClick = { value = wrapSelection(value, "**") }) { Text("B") }
                TextButton(onClick = { value = insertAtLine(value, "- ") }) { Text("List") }
                TextButton(onClick = { value = wrapSelection(value, "`") }) { Text("Code") }
                TextButton(onClick = { value = wrapSelection(value, "[", "](https://)") }) { Text("Link") }
            }
        }
        if (!writing) {
            val linked = notes.filter { value.text.contains("[[${it.id}]]") }
            linked.forEach { note ->
                TextButton(onClick = { onOpen(note) }) { Text(noteTitle(note.text)) }
            }
        }
    }
}

private data class NoteBlock(val text: String, val weight: FontWeight, val size: androidx.compose.ui.unit.TextUnit)

private fun noteBlocks(source: String): List<NoteBlock> {
    return source.replace("\r\n", "\n").lines().map { line ->
        val head = Regex("^(#{1,3})\\s+(.*)$").find(line)
        if (head != null) {
            val size = when (head.groupValues[1].length) {
                1 -> 28.sp
                2 -> 22.sp
                else -> 18.sp
            }
            NoteBlock(head.groupValues[2], FontWeight.SemiBold, size)
        } else {
            NoteBlock(line.ifBlank { " " }, FontWeight.Normal, 16.sp)
        }
    }
}

internal fun noteTitle(text: String): String {
    val line = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
    return line.removePrefix("# ").removePrefix("## ").removePrefix("### ").ifBlank { "Untitled" }
}

private fun mentionQuery(value: TextFieldValue): String? {
    val before = value.text.take(value.selection.start)
    val at = before.lastIndexOf("[[")
    if (at < 0 || before.substring(at).contains("]]")) return null
    return before.substring(at + 2)
}

private fun insertMention(value: TextFieldValue, id: String): TextFieldValue {
    val before = value.text.take(value.selection.start)
    val at = before.lastIndexOf("[[")
    if (at < 0) return value
    val next = value.text.take(at) + "[[$id]] " + value.text.drop(value.selection.start)
    val cursor = at + id.length + 5
    return TextFieldValue(next, TextRange(cursor))
}

private fun wrapSelection(value: TextFieldValue, before: String, after: String = before): TextFieldValue {
    val start = value.selection.start
    val end = value.selection.end
    val selected = value.text.substring(start, end).ifEmpty { "text" }
    val next = value.text.take(start) + before + selected + after + value.text.drop(end)
    val cursor = start + before.length + selected.length
    return TextFieldValue(next, TextRange(cursor))
}

private fun insertAtLine(value: TextFieldValue, token: String): TextFieldValue {
    val start = value.selection.start
    val next = value.text.take(start) + token + value.text.drop(start)
    return TextFieldValue(next, TextRange(start + token.length))
}
