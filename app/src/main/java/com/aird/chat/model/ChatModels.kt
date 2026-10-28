package com.aird.chat.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class Member(val username: String, val online: Boolean)

data class Conversation(
    val id: String,
    val title: String,
    val peer: String,
    val preview: String,
    val unread: Int,
    val updatedAt: String,
    val kind: String = "dm",
    val muted: Boolean = false,
    val members: List<Member> = emptyList(),
    val online: Boolean = false,
    val request: String = "",
) {
    fun name(): String = peer.ifEmpty { title.ifEmpty { "Chat" } }
    fun whenLabel(): String = formatListTime(updatedAt)
}

fun filterConversations(items: List<Conversation>, query: String): List<Conversation> {
    val q = query.trim()
    if (q.isEmpty()) return items
    return items.filter { conversation ->
        conversation.name().contains(q, true) ||
            conversation.peer.contains(q, true) ||
            conversation.title.contains(q, true) ||
            conversation.preview.contains(q, true)
    }
}

data class ChatAttachment(
    val name: String,
    val url: String,
    val mediaKind: String,
    val index: Int,
    val shareUrl: String = "",
    val ownerRel: String = "",
) {
    fun visual(): Boolean = mediaKind == "image" || mediaKind == "gif"

    fun video(): Boolean = matches("video", setOf("mp4", "webm", "mkv", "mov", "3gp", "m4v"))

    fun audio(): Boolean = matches("audio", setOf("mp3", "m4a", "aac", "wav", "ogg", "flac", "opus"))

    private fun matches(kind: String, extensions: Set<String>): Boolean {
        if (mediaKind == kind) return true
        if (mediaKind == "image" || mediaKind == "gif") return false
        if (mediaKind.isNotEmpty() && mediaKind != "file" && mediaKind != kind) return false
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in extensions
    }
}

data class Reaction(val emoji: String, val users: List<String>)

data class ReadReceipt(
    val username: String,
    val lastReadId: String,
    val lastReadAt: String,
    val decrypted: Boolean,
)

data class ChatPin(val messageId: String, val preview: String)

data class ChatMessage(
    val id: String,
    val conversationId: String,
    val sender: String,
    val body: String,
    val createdAt: String,
    val mine: Boolean,
    val pending: Boolean = false,
    val failed: Boolean = false,
    val e2e: Boolean = false,
    val decryptOk: Boolean = true,
    val replyToId: String = "",
    val mentions: List<String> = emptyList(),
    val attachments: List<ChatAttachment> = emptyList(),
    val reactions: List<Reaction> = emptyList(),
    val forwarded: Boolean = false,
    val edited: Boolean = false,
) {
    fun clock(): String = formatMessageTime(createdAt)

    fun replyTargetId(): String {
        val raw = replyToId.trim()
        if (raw.isEmpty() || raw.equals("null", true) || raw == id) return ""
        return raw
    }

    fun previewLabel(): String {
        val text = body.trim()
        if (text.isNotEmpty() && !text.equals("Encrypted message", true)) return text
        val att = attachments.firstOrNull() ?: return "Message"
        val gif = att.mediaKind == "gif" || att.name.endsWith(".gif", true)
        if (gif) return att.name.ifBlank { "GIF" }
        if (att.visual()) return att.name.ifBlank { "Photo" }
        return att.name.ifBlank { "File" }
    }
}

fun editAllowed(
    message: ChatMessage,
    receipts: List<ReadReceipt>,
    me: String,
    now: Instant = Instant.now(),
): Boolean {
    if (!message.mine || message.pending || message.failed || message.attachments.isNotEmpty()) return false
    if (!message.decryptOk || message.body.isBlank()) return false
    val seen = receipts.mapNotNull { receipt ->
        if (receipt.username.equals(me, true)) return@mapNotNull null
        if (!receiptCovers(receipt, message)) return@mapNotNull null
        parseInstant(receipt.lastReadAt)
    }.minOrNull() ?: return true
    return java.time.Duration.between(seen, now).toMillis() < 30_000
}

fun seenLabel(
    message: ChatMessage,
    messages: List<ChatMessage>,
    receipts: List<ReadReceipt>,
    me: String,
    kind: String,
): String {
    if (!message.mine || message.pending) return ""
    val names = receipts.mapNotNull { receipt ->
        if (receipt.username.equals(me, true)) return@mapNotNull null
        val covered = messages.filter { item ->
            item.mine && !item.pending && receiptCovers(receipt, item)
        }
        val latest = covered.maxWithOrNull(compareBy<ChatMessage> { it.createdAt }.thenBy { it.id })
        if (latest?.id == message.id) receipt.username else null
    }
    if (names.isEmpty()) return ""
    return if (kind == "group") "Seen by ${names.joinToString(", ")}" else "Seen"
}

private fun receiptCovers(receipt: ReadReceipt, message: ChatMessage): Boolean {
    val byId = receipt.lastReadId.isNotEmpty() && receipt.lastReadId == message.id
    val byTime = !message.e2e &&
        receipt.lastReadAt.isNotEmpty() &&
        message.createdAt.isNotEmpty() &&
        receipt.lastReadAt >= message.createdAt
    if (!byId && !byTime) return false
    if (message.e2e && !receipt.decrypted) return false
    return true
}

fun formatMessageTime(iso: String): String = formatTime(iso, withDate = true)

fun formatListTime(iso: String): String {
    val instant = parseInstant(iso) ?: return ""
    val zone = ZoneId.systemDefault()
    val whenLocal = instant.atZone(zone)
    val today = LocalDate.now(zone)
    val day = whenLocal.toLocalDate()
    val time = whenLocal.format(DateTimeFormatter.ofPattern("h:mm a"))
    if (day == today) return time
    val days = java.time.temporal.ChronoUnit.DAYS.between(day, today)
    if (days in 1..6) return whenLocal.format(DateTimeFormatter.ofPattern("EEE"))
    return whenLocal.format(DateTimeFormatter.ofPattern("MMM d"))
}

private fun formatTime(iso: String, withDate: Boolean): String {
    val instant = parseInstant(iso) ?: return ""
    val zone = ZoneId.systemDefault()
    val whenLocal = instant.atZone(zone)
    val time = whenLocal.format(DateTimeFormatter.ofPattern("h:mm a"))
    if (!withDate || whenLocal.toLocalDate() == LocalDate.now(zone)) return time
    val date = whenLocal.format(DateTimeFormatter.ofPattern("MMM d"))
    return "$date, $time"
}

private fun parseInstant(iso: String): Instant? {
    if (iso.isBlank()) return null
    return try {
        Instant.parse(iso)
    } catch (_: Exception) {
        try {
            java.time.OffsetDateTime.parse(iso).toInstant()
        } catch (_: Exception) {
            null
        }
    }
}

fun nowIso(): String = Instant.now().toString()
