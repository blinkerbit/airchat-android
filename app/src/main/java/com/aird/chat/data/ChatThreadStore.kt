package com.aird.chat.data

import android.content.Context
import com.aird.chat.model.ChatAttachment
import com.aird.chat.model.ChatMessage
import com.aird.chat.model.ChatPin
import com.aird.chat.model.Reaction
import com.aird.chat.model.ReadReceipt
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal data class StoredThread(
    val messages: List<ChatMessage>,
    val receipts: List<ReadReceipt>,
    val pins: List<ChatPin>,
    val hasMore: Boolean,
)

internal object ChatThreadStore {
    private const val CAP = 2000

    fun load(context: Context, user: String, conversationId: String): StoredThread? {
        val file = file(context, user, conversationId)
        if (!file.isFile) return null
        return try {
            val root = JSONObject(file.readText())
            val messages = buildList {
                val array = root.optJSONArray("messages") ?: JSONArray()
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val row = messageOf(item)
                    if (row.id.isNotEmpty() && row.msgType != "sync" && !row.pending && !row.id.startsWith("local-")) add(row)
                }
            }
            if (messages.isEmpty()) return null
            StoredThread(
                messages = messages,
                receipts = receiptsOf(root.optJSONArray("receipts")),
                pins = pinsOf(root.optJSONArray("pins")),
                hasMore = root.optBoolean("hasMore", true),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun save(
        context: Context,
        user: String,
        conversationId: String,
        messages: List<ChatMessage>,
        receipts: List<ReadReceipt>,
        pins: List<ChatPin>,
        hasMore: Boolean,
    ) {
        if (user.isBlank() || conversationId.isBlank()) return
        val kept = messages.filter { it.msgType != "sync" && !it.pending && !it.id.startsWith("local-") }
        if (kept.isEmpty()) return
        val trimmed = if (kept.size > CAP) kept.takeLast(CAP) else kept
        val more = hasMore || trimmed.size < kept.size
        val root = JSONObject()
            .put("messages", JSONArray().apply { trimmed.forEach { put(messageJson(it)) } })
            .put("receipts", JSONArray().apply { receipts.forEach { put(receiptJson(it)) } })
            .put("pins", JSONArray().apply { pins.forEach { put(JSONObject().put("id", it.messageId).put("preview", it.preview)) } })
            .put("hasMore", more)
        val file = file(context, user, conversationId)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(root.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(root.toString())
            tmp.delete()
        }
    }

    fun delete(context: Context, user: String, conversationId: String) {
        file(context, user, conversationId).delete()
    }

    fun deleteUser(context: Context, user: String) {
        dir(context, user).deleteRecursively()
    }

    private fun file(context: Context, user: String, conversationId: String): File {
        return File(dir(context, user), safe(conversationId) + ".json")
    }

    private fun dir(context: Context, user: String): File {
        return File(context.noBackupFilesDir, "chat-threads/" + safe(user))
    }

    private fun safe(value: String): String {
        val cleaned = value.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        return cleaned.ifBlank { "chat" }.take(80)
    }

    private fun messageJson(row: ChatMessage): JSONObject {
        return JSONObject()
            .put("id", row.id)
            .put("conversationId", row.conversationId)
            .put("sender", row.sender)
            .put("body", row.body)
            .put("createdAt", row.createdAt)
            .put("mine", row.mine)
            .put("e2e", row.e2e)
            .put("decryptOk", row.decryptOk)
            .put("replyToId", row.replyToId)
            .put("mentions", JSONArray(row.mentions))
            .put("forwarded", row.forwarded)
            .put("edited", row.edited)
            .put("msgType", row.msgType)
            .put("cardKind", row.cardKind)
            .put("cardId", row.cardId)
            .put("cardTitle", row.cardTitle)
            .put("attachments", JSONArray().apply { row.attachments.forEach { put(attachmentJson(it)) } })
            .put("reactions", JSONArray().apply { row.reactions.forEach { put(reactionJson(it)) } })
    }

    private fun attachmentJson(row: ChatAttachment): JSONObject {
        return JSONObject()
            .put("name", row.name)
            .put("url", row.url)
            .put("mediaKind", row.mediaKind)
            .put("index", row.index)
            .put("shareUrl", row.shareUrl)
            .put("ownerRel", row.ownerRel)
    }

    private fun reactionJson(row: Reaction): JSONObject {
        return JSONObject().put("emoji", row.emoji).put("users", JSONArray(row.users))
    }

    private fun receiptJson(row: ReadReceipt): JSONObject {
        return JSONObject()
            .put("username", row.username)
            .put("lastReadId", row.lastReadId)
            .put("lastReadAt", row.lastReadAt)
            .put("decrypted", row.decrypted)
    }

    private fun messageOf(item: JSONObject): ChatMessage {
        return ChatMessage(
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
    }

    private fun attachmentsOf(array: JSONArray?): List<ChatAttachment> {
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

    private fun reactionsOf(array: JSONArray?): List<Reaction> {
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

    private fun receiptsOf(array: JSONArray?): List<ReadReceipt> {
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

    private fun pinsOf(array: JSONArray?): List<ChatPin> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("id")
                if (id.isNotEmpty()) add(ChatPin(id, item.optString("preview")))
            }
        }
    }

    private fun strings(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val value = array.optString(i)
                if (value.isNotEmpty()) add(value)
            }
        }
    }
}
