package com.aird.widget

import android.content.Context
import com.aird.chat.model.Conversation
import org.json.JSONArray
import org.json.JSONObject

data class UnreadChatRow(
    val id: String,
    val title: String,
    val preview: String,
    val unread: Int,
)

/** Lightweight prefs-backed snapshot for Glance widgets. */
object WidgetStore {
    private const val PREFS = "aird_widgets"
    private const val KEY_UNREAD = "unread_chats_json"
    private const val KEY_LIST_ID = "checklist_list_id"
    private const val KEY_LIST_TITLE = "checklist_list_title"
    private const val KEY_LIST_ITEMS = "checklist_items_json"

    fun writeUnread(context: Context, conversations: List<Conversation>) {
        val top = conversations
            .filter { it.unread > 0 && it.request.isEmpty() }
            .sortedByDescending { it.updatedAt }
            .take(5)
        val array = JSONArray()
        top.forEach { conv ->
            array.put(
                JSONObject()
                    .put("id", conv.id)
                    .put("title", conv.name())
                    .put("preview", conv.preview.take(80))
                    .put("unread", conv.unread),
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_UNREAD, array.toString())
            .apply()
    }

    fun readUnread(context: Context): List<UnreadChatRow> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_UNREAD, null)
            ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    add(
                        UnreadChatRow(
                            id = o.optString("id"),
                            title = o.optString("title"),
                            preview = o.optString("preview"),
                            unread = o.optInt("unread"),
                        ),
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun checklistListId(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LIST_ID, "").orEmpty()

    fun setChecklistList(context: Context, id: String, title: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LIST_ID, id)
            .putString(KEY_LIST_TITLE, title)
            .apply()
    }

    fun checklistTitle(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LIST_TITLE, "Checklist").orEmpty()

    fun writeChecklistItems(context: Context, items: List<Pair<String, Pair<String, Boolean>>>) {
        val array = JSONArray()
        items.take(8).forEach { (id, pair) ->
            array.put(JSONObject().put("id", id).put("text", pair.first).put("done", pair.second))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LIST_ITEMS, array.toString())
            .apply()
    }

    fun readChecklistItems(context: Context): List<Triple<String, String, Boolean>> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LIST_ITEMS, null)
            ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    add(Triple(o.optString("id"), o.optString("text"), o.optBoolean("done")))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
