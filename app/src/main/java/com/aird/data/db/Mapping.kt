package com.aird.data.db

import com.aird.chat.model.ChatAttachment
import com.aird.chat.model.ChatMessage
import com.aird.chat.model.ChatPin
import com.aird.chat.model.Conversation
import com.aird.chat.model.Member
import com.aird.chat.model.Reaction
import com.aird.chat.model.ReadReceipt
import org.json.JSONArray
import org.json.JSONObject

internal fun ChatMessage.toEntity(): MessageEntity = MessageEntity(
    id = id,
    conversationId = conversationId,
    sender = sender,
    body = body,
    createdAt = createdAt,
    mine = mine,
    e2e = e2e,
    decryptOk = decryptOk,
    replyToId = replyToId,
    mentions = JSONArray(mentions).toString(),
    attachments = JSONArray().apply { attachments.forEach { put(attachmentJson(it)) } }.toString(),
    reactions = JSONArray().apply { reactions.forEach { put(JSONObject().put("emoji", it.emoji).put("users", JSONArray(it.users))) } }.toString(),
    forwarded = forwarded,
    edited = edited,
    msgType = msgType,
    cardKind = cardKind,
    cardId = cardId,
    cardTitle = cardTitle,
)

internal fun MessageEntity.toModel(): ChatMessage = ChatMessage(
    id = id,
    conversationId = conversationId,
    sender = sender,
    body = body,
    createdAt = createdAt,
    mine = mine,
    e2e = e2e,
    decryptOk = decryptOk,
    replyToId = replyToId,
    mentions = strings(parseArray(mentions)),
    attachments = attachmentsOf(parseArray(attachments)),
    reactions = reactionsOf(parseArray(reactions)),
    forwarded = forwarded,
    edited = edited,
    msgType = msgType.ifBlank { "text" },
    cardKind = cardKind,
    cardId = cardId,
    cardTitle = cardTitle,
)

internal fun Conversation.toEntity(position: Int): ConversationEntity = ConversationEntity(
    id = id,
    position = position,
    title = title,
    peer = peer,
    preview = preview,
    unread = unread,
    updatedAt = updatedAt,
    kind = kind,
    muted = muted,
    members = JSONArray().apply { members.forEach { put(JSONObject().put("username", it.username).put("online", it.online)) } }.toString(),
    online = online,
    request = request,
)

internal fun ConversationEntity.toModel(): Conversation = Conversation(
    id = id,
    title = title,
    peer = peer,
    preview = preview,
    unread = unread,
    updatedAt = updatedAt,
    kind = kind,
    muted = muted,
    members = buildList {
        val array = parseArray(members)
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val name = item.optString("username")
            if (name.isNotEmpty()) add(Member(name, false))
        }
    },
    online = false,
    request = request,
)

internal fun receiptsJson(rows: List<ReadReceipt>): String = JSONArray().apply {
    rows.forEach {
        put(
            JSONObject()
                .put("username", it.username)
                .put("lastReadId", it.lastReadId)
                .put("lastReadAt", it.lastReadAt)
                .put("decrypted", it.decrypted),
        )
    }
}.toString()

internal fun pinsJson(rows: List<ChatPin>): String = JSONArray().apply {
    rows.forEach { put(JSONObject().put("id", it.messageId).put("preview", it.preview)) }
}.toString()

internal fun parseArray(raw: String): JSONArray = try {
    if (raw.isBlank()) JSONArray() else JSONArray(raw)
} catch (_: Exception) {
    JSONArray()
}

internal fun attachmentJson(row: ChatAttachment): JSONObject = JSONObject()
    .put("name", row.name)
    .put("url", row.url)
    .put("mediaKind", row.mediaKind)
    .put("index", row.index)
    .put("shareUrl", row.shareUrl)
    .put("ownerRel", row.ownerRel)

internal fun attachmentsOf(array: JSONArray?): List<ChatAttachment> {
    if (array == null) return emptyList()
    return buildList {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            add(
                ChatAttachment(
                    name = item.optString("name"),
                    url = item.optString("url"),
                    mediaKind = item.optString("mediaKind"),
                    index = item.optInt("index"),
                    shareUrl = item.optString("shareUrl"),
                    ownerRel = item.optString("ownerRel"),
                ),
            )
        }
    }
}

internal fun reactionsOf(array: JSONArray?): List<Reaction> {
    if (array == null) return emptyList()
    return buildList {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val emoji = item.optString("emoji")
            if (emoji.isEmpty()) continue
            add(Reaction(emoji, strings(item.optJSONArray("users"))))
        }
    }
}

internal fun receiptsOf(array: JSONArray?): List<ReadReceipt> {
    if (array == null) return emptyList()
    return buildList {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            add(
                ReadReceipt(
                    username = item.optString("username"),
                    lastReadId = item.optString("lastReadId"),
                    lastReadAt = item.optString("lastReadAt"),
                    decrypted = item.optBoolean("decrypted"),
                ),
            )
        }
    }
}

internal fun pinsOf(array: JSONArray?): List<ChatPin> {
    if (array == null) return emptyList()
    return buildList {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optString("id")
            if (id.isNotEmpty()) add(ChatPin(id, item.optString("preview")))
        }
    }
}

internal fun strings(array: JSONArray?): List<String> {
    if (array == null) return emptyList()
    return buildList {
        for (i in 0 until array.length()) {
            val value = array.optString(i)
            if (value.isNotEmpty()) add(value)
        }
    }
}

internal fun legacyMessageOf(item: JSONObject): ChatMessage = ChatMessage(
    id = item.optString("id"),
    conversationId = item.optString("conversationId"),
    sender = item.optString("sender"),
    body = item.optString("body"),
    createdAt = item.optString("createdAt"),
    mine = item.optBoolean("mine"),
    e2e = item.optBoolean("e2e"),
    decryptOk = item.optBoolean("decryptOk", true),
    replyToId = item.optString("replyToId"),
    mentions = strings(item.optJSONArray("mentions")),
    attachments = attachmentsOf(item.optJSONArray("attachments")),
    reactions = reactionsOf(item.optJSONArray("reactions")),
    forwarded = item.optBoolean("forwarded"),
    edited = item.optBoolean("edited"),
    msgType = item.optString("msgType").ifBlank { "text" },
    cardKind = item.optString("cardKind"),
    cardId = item.optString("cardId"),
    cardTitle = item.optString("cardTitle"),
)
