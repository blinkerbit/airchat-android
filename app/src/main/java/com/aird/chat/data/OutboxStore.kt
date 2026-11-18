package com.aird.chat.data

import android.content.Context
import com.aird.data.db.LocalStore
import com.aird.data.db.OutboxEntity
import com.aird.data.db.SerialWriter
import com.aird.session.Session

/** Failed text sends. Reads come from memory; writes go to Room in order. */
object OutboxStore {
    data class Item(
        val localId: String,
        val conversationId: String,
        val body: String,
        val replyToId: String,
    )

    private val lock = Any()
    private var items: List<Item> = emptyList()
    private val writer = SerialWriter()

    suspend fun attach(context: Context, user: String) {
        val db = LocalStore.db(context, user) ?: return
        val rows = db.outbox().all().map { Item(it.localId, it.conversationId, it.body, it.replyToId) }
        synchronized(lock) { items = rows }
    }

    fun load(@Suppress("UNUSED_PARAMETER") context: Context): List<Item> = synchronized(lock) { items }

    fun contains(@Suppress("UNUSED_PARAMETER") context: Context, localId: String): Boolean =
        synchronized(lock) { items.any { it.localId == localId } }

    fun upsert(context: Context, item: Item) {
        synchronized(lock) {
            val index = items.indexOfFirst { it.localId == item.localId }
            items = if (index >= 0) items.toMutableList().also { it[index] = item } else items + item
        }
        val app = context.applicationContext
        val user = Session.username.value
        writer.post {
            LocalStore.db(app, user)?.outbox()?.upsert(
                OutboxEntity(item.localId, item.conversationId, item.body, item.replyToId, System.currentTimeMillis()),
            )
        }
    }

    fun remove(context: Context, localId: String) {
        synchronized(lock) {
            if (items.none { it.localId == localId }) return
            items = items.filterNot { it.localId == localId }
        }
        val app = context.applicationContext
        val user = Session.username.value
        writer.post { LocalStore.db(app, user)?.outbox()?.delete(localId) }
    }

    fun reset() = synchronized(lock) { items = emptyList() }
}
