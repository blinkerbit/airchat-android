package com.aird.chat.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Plaintext failed text sends. Excluded from backup. Not ciphertext. */
object OutboxStore {
    const val FILE_NAME = "chat-outbox.json"

    data class Item(
        val localId: String,
        val conversationId: String,
        val body: String,
        val replyToId: String,
    )

    private val lock = Any()

    fun load(context: Context): List<Item> = synchronized(lock) {
        read(context.applicationContext)
    }

    fun contains(context: Context, localId: String): Boolean = synchronized(lock) {
        read(context.applicationContext).any { it.localId == localId }
    }

    fun upsert(context: Context, item: Item) = synchronized(lock) {
        val app = context.applicationContext
        val items = read(app).toMutableList()
        val index = items.indexOfFirst { it.localId == item.localId }
        if (index >= 0) items[index] = item else items.add(item)
        write(app, items)
    }

    fun remove(context: Context, localId: String) = synchronized(lock) {
        val app = context.applicationContext
        val items = read(app)
        if (items.none { it.localId == localId }) return
        write(app, items.filterNot { it.localId == localId })
    }

    fun clear(context: Context) = synchronized(lock) {
        val dir = context.applicationContext.filesDir
        File(dir, FILE_NAME).delete()
        File(dir, "$FILE_NAME.part").delete()
    }

    private fun read(context: Context): List<Item> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists() || file.length() == 0L) return emptyList()
        return try {
            val array = JSONObject(file.readText()).optJSONArray("items") ?: return emptyList()
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val localId = obj.optString("localId")
                    val conversationId = obj.optString("conversationId")
                    if (localId.isEmpty() || conversationId.isEmpty()) continue
                    add(
                        Item(
                            localId = localId,
                            conversationId = conversationId,
                            body = obj.optString("body"),
                            replyToId = obj.optString("replyToId"),
                        ),
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun write(context: Context, items: List<Item>) {
        val dir = context.filesDir
        val file = File(dir, FILE_NAME)
        val part = File(dir, "$FILE_NAME.part")
        if (items.isEmpty()) {
            file.delete()
            part.delete()
            return
        }
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("localId", item.localId)
                    .put("conversationId", item.conversationId)
                    .put("body", item.body)
                    .put("replyToId", item.replyToId),
            )
        }
        part.writeText(JSONObject().put("items", array).toString())
        if (!part.renameTo(file)) {
            file.writeText(part.readText())
            part.delete()
        }
    }
}
