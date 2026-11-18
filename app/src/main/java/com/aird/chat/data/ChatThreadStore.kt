package com.aird.chat.data

import android.content.Context
import androidx.room.withTransaction
import com.aird.chat.model.ChatMessage
import com.aird.chat.model.ChatPin
import com.aird.chat.model.ReadReceipt
import com.aird.data.db.LocalStore
import com.aird.data.db.ThreadEntity
import com.aird.data.db.parseArray
import com.aird.data.db.pinsJson
import com.aird.data.db.pinsOf
import com.aird.data.db.receiptsJson
import com.aird.data.db.receiptsOf
import com.aird.data.db.toEntity
import com.aird.data.db.toModel
import java.util.concurrent.ConcurrentHashMap

internal data class StoredThread(
    val messages: List<ChatMessage>,
    val receipts: List<ReadReceipt>,
    val pins: List<ChatPin>,
    val hasMore: Boolean,
)

/** Thread cache in Room. Saves write only the rows that changed since the last save. */
internal object ChatThreadStore {
    private const val CAP = 2000
    private val saved = ConcurrentHashMap<String, Map<String, ChatMessage>>()

    suspend fun load(context: Context, user: String, conversationId: String): StoredThread? {
        val db = LocalStore.db(context, user) ?: return null
        val rows = db.messages().thread(conversationId).map { it.toModel() }
        saved[conversationId] = rows.associateBy { it.id }
        if (rows.isEmpty()) return null
        val meta = db.threads().get(conversationId)
        return StoredThread(
            messages = rows,
            receipts = receiptsOf(parseArray(meta?.receipts.orEmpty())),
            pins = pinsOf(parseArray(meta?.pins.orEmpty())),
            hasMore = meta?.hasMore ?: true,
        )
    }

    suspend fun save(
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
        val db = LocalStore.db(context, user) ?: return
        val previous = saved[conversationId]
            ?: db.messages().thread(conversationId).associate { it.id to it.toModel() }
        val changed = trimmed.filter { previous[it.id] != it }
        val keep = trimmed.mapTo(HashSet(trimmed.size)) { it.id }
        val removed = previous.keys.filter { it !in keep }
        db.withTransaction {
            changed.chunked(400).forEach { chunk -> db.messages().upsert(chunk.map { it.toEntity() }) }
            removed.chunked(400).forEach { chunk -> db.messages().deleteIds(chunk) }
            db.threads().upsert(ThreadEntity(conversationId, more, receiptsJson(receipts), pinsJson(pins)))
        }
        saved[conversationId] = trimmed.associateBy { it.id }
    }

    suspend fun delete(context: Context, user: String, conversationId: String) {
        saved.remove(conversationId)
        val db = LocalStore.db(context, user) ?: return
        db.withTransaction {
            db.messages().deleteThread(conversationId)
            db.threads().delete(conversationId)
        }
    }

    suspend fun deleteUser(context: Context, user: String) {
        saved.clear()
        LocalStore.wipe(context, user)
    }
}
