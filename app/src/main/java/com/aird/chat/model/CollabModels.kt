package com.aird.chat.model

import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

data class CollabMention(val kind: String, val slug: String, val start: Int, val end: Int)

data class CollabRef(
    val kind: String,
    val id: String,
    val slug: String,
    val title: String,
    val token: String,
    val active: Boolean = true,
)

data class CollabFocus(val kind: String, val id: String, val slug: String)

data class PollOption(val id: String, val label: String, val votes: Int, val mine: Boolean)

data class PollDraft(
    val question: String,
    val options: List<String>,
    val allowMulti: Boolean,
    val privateShare: Boolean,
)

data class ListEntry(
    val id: String,
    val text: String,
    val done: Boolean,
    val labels: List<String>,
    val notes: String = "",
    val dueAt: String = "",
    val remindAt: String = "",
    val kind: String = "checkbox",
    val depth: Int = 0,
    val orderKey: String = "",
) {
    val type: String get() = when (kind) {
        "bullet" -> "bullet"
        "paragraph", "note", "text" -> "paragraph"
        else -> "checkbox"
    }
}

data class ListDraft(
    val title: String,
    val style: String,
    val items: List<String>,
    val privateShare: Boolean,
    val notes: String = "",
    val groupId: String = "",
)

data class ListGroup(val id: String, val name: String)

data class CollabTag(val name: String, val color: String = "", val label: String = "") {
    val shown: String get() = label.ifBlank { name }
}

data class ListSummary(
    val id: String,
    val title: String,
    val style: String,
    val groupId: String,
    val done: Int,
    val total: Int,
    val nextDue: String,
    val active: Boolean,
    val organizer: String = "",
    val tags: List<CollabTag> = emptyList(),
    val search: String = "",
)

data class PollSummary(
    val id: String,
    val question: String,
    val votes: Int,
    val active: Boolean,
)

data class ListReminder(
    val listId: String,
    val itemId: String,
    val text: String,
    val title: String,
    val remindAt: String,
)

data class MeetDraft(val title: String, val privateShare: Boolean)

sealed class CollabArtifact {
    abstract val id: String
    abstract val slug: String
    abstract val organizer: String
    abstract val visibility: String
    abstract val participants: List<String>
    abstract val heading: String
    abstract val active: Boolean
    val type: String
        get() = when (this) {
            is PollArtifact -> "poll"
            is ListArtifact -> "list"
            is MeetArtifact -> "meet"
        }

    val privateShare: Boolean get() = visibility == "private"
}

data class PollArtifact(
    override val id: String,
    override val slug: String,
    override val organizer: String,
    override val visibility: String,
    override val participants: List<String>,
    val question: String,
    val allowMulti: Boolean,
    val totalVotes: Int,
    val options: List<PollOption>,
    override val active: Boolean = true,
) : CollabArtifact() {
    override val heading: String get() = question
}

data class ListArtifact(
    override val id: String,
    override val slug: String,
    override val organizer: String,
    override val visibility: String,
    override val participants: List<String>,
    val title: String,
    val style: String,
    val items: List<ListEntry>,
    val done: Int,
    val total: Int,
    val notes: String = "",
    val groupId: String = "",
    val tags: List<CollabTag> = emptyList(),
    override val active: Boolean = true,
) : CollabArtifact() {
    override val heading: String get() = title
    val checklist: Boolean get() = style != "bullet"
}

data class MeetArtifact(
    override val id: String,
    override val slug: String,
    override val organizer: String,
    override val visibility: String,
    override val participants: List<String>,
    val title: String,
    val live: Boolean,
    val expiresAt: String,
    val joinPath: String,
    override val active: Boolean = live,
) : CollabArtifact() {
    override val heading: String get() = title
}

fun PollArtifact.toDraft() = PollDraft(
    question,
    options.map { it.label }.ifEmpty { listOf("", "") },
    allowMulti,
    privateShare,
)

fun ListArtifact.toDraft() = ListDraft(title, if (style == "bullet") "bullet" else "todo", emptyList(), privateShare, notes, groupId)

fun MeetArtifact.toDraft() = MeetDraft(title, privateShare)

fun collabInactiveLabel(kind: String): String = when (kind) {
    "poll" -> "Closed"
    "meet" -> "Ended"
    else -> "Done"
}

fun collabKindLabel(kind: String): String = when (kind) {
    "poll" -> "Poll"
    "meet" -> "Meet"
    else -> "List"
}

fun collabPreview(kind: String, slug: String): String {
    val name = slug.trim()
    val label = collabKindLabel(kind)
    return if (name.isEmpty()) label else "$label · $name"
}

fun collabToken(kind: String, slug: String): String {
    val name = slug.trim().lowercase()
    if (name.isEmpty()) return ""
    val dollar = '$'
    return when (kind) {
        "poll" -> "$dollar$dollar$name"
        "meet" -> "//$name"
        else -> "#$name"
    }
}

fun previewText(body: String): String {
    if (body.equals("Encrypted message", true)) return body
    val text = textWithoutCollab(body).trim()
    if (text.isNotEmpty()) return text
    val mention = collabMentions(body).firstOrNull() ?: return body.trim()
    return collabPreview(mention.kind, mention.slug)
}

fun stampStillOpen(iso: String, now: Instant = Instant.now()): Boolean {
    if (iso.isBlank()) return true
    val end = parseStamp(iso) ?: return true
    return now.isBefore(end)
}

fun meetExpiryLabel(iso: String, now: Instant = Instant.now()): String {
    if (iso.isBlank()) return ""
    val end = parseStamp(iso) ?: return ""
    val minutes = Duration.between(now, end).toMinutes()
    if (minutes <= 0) return "Expired"
    val hours = ((minutes + 59) / 60).coerceAtLeast(1)
    return "Expires in ~${hours}h"
}

private const val DOLLARS = "${'$'}${'$'}"

private val collabTokenRegex = Regex(
    "(?:\\Q$DOLLARS\\Epoll:|\\Q$DOLLARS\\E|//meet:|//|#list:|#)([a-z0-9]+(?:-[a-z0-9]+)*)",
    RegexOption.IGNORE_CASE,
)

fun collabMentions(text: String): List<CollabMention> {
    if (text.isEmpty()) return emptyList()
    return collabTokenRegex.findAll(text).mapNotNull { match ->
        val start = match.range.first
        if (start > 0 && !text[start - 1].isWhitespace()) return@mapNotNull null
        val raw = match.value
        val slug = match.groupValues[1].lowercase()
        val explicit = raw.startsWith("${DOLLARS}poll:", true) ||
            raw.startsWith("//meet:", true) ||
            raw.startsWith("#list:", true)
        if (!explicit && !slug.contains('-')) return@mapNotNull null
        val kind = when {
            raw.startsWith(DOLLARS) -> "poll"
            raw.startsWith("//") -> "meet"
            else -> "list"
        }
        CollabMention(kind, slug, start, match.range.last + 1)
    }.toList()
}

fun textWithoutCollab(text: String): String {
    val mentions = collabMentions(text)
    if (mentions.isEmpty()) return text
    val raw = StringBuilder(text)
    mentions.sortedByDescending { it.start }.forEach { raw.delete(it.start, it.end) }
    return raw.toString()
        .lines()
        .map { it.replace(Regex("[ \\t]{2,}"), " ").trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")
}

private fun parseStamp(iso: String): Instant? = try {
    Instant.parse(iso)
} catch (_: Exception) {
    try {
        OffsetDateTime.parse(iso).toInstant()
    } catch (_: Exception) {
        null
    }
}
