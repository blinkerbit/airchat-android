package com.aird.data

import android.content.Context
import com.aird.data.db.ConversationPrefsEntity
import com.aird.data.db.LocalStore
import com.aird.data.db.SerialWriter
import com.aird.data.db.StarredEntity
import com.aird.session.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Per-chat look and placement on this device: pinned chats, wallpaper, accent. */
object ChatPrefsStore {
    private val _prefs = MutableStateFlow<Map<String, ConversationPrefsEntity>>(emptyMap())
    val prefs: StateFlow<Map<String, ConversationPrefsEntity>> = _prefs
    private val writer = SerialWriter()
    @Volatile private var appContext: Context? = null

    suspend fun attach(context: Context, user: String) {
        appContext = context.applicationContext
        val rows = LocalStore.db(context, user)?.conversationPrefs()?.all().orEmpty()
        _prefs.update { live -> rows.associateBy { it.conversationId } + live }
    }

    fun of(conversationId: String): ConversationPrefsEntity =
        _prefs.value[conversationId] ?: ConversationPrefsEntity(conversationId)

    fun setPinned(conversationId: String, pinned: Boolean) =
        edit(conversationId) { it.copy(pinnedAt = if (pinned) System.currentTimeMillis() else 0) }

    fun setWallpaper(conversationId: String, wallpaper: String, dim: Float = 0f) =
        edit(conversationId) { it.copy(wallpaper = wallpaper, wallpaperDim = dim) }

    fun setAccent(conversationId: String, accent: Long?) =
        edit(conversationId) { it.copy(accent = accent) }

    private fun edit(conversationId: String, change: (ConversationPrefsEntity) -> ConversationPrefsEntity) {
        if (conversationId.isEmpty()) return
        val next = change(of(conversationId))
        _prefs.update { it + (conversationId to next) }
        val app = appContext ?: return
        val user = Session.username.value
        writer.post {
            val dao = LocalStore.db(app, user)?.conversationPrefs() ?: return@post
            val empty = next.pinnedAt == 0L && next.wallpaper.isEmpty() && next.accent == null
            if (empty) dao.delete(conversationId) else dao.upsert(next)
        }
    }

    fun clear() {
        _prefs.value = emptyMap()
    }
}

/** Messages the user starred. Kept on this device only. */
object StarredStore {
    private val _items = MutableStateFlow<List<StarredEntity>>(emptyList())
    val items: StateFlow<List<StarredEntity>> = _items
    private val writer = SerialWriter()
    @Volatile private var appContext: Context? = null

    suspend fun attach(context: Context, user: String) {
        appContext = context.applicationContext
        _items.value = LocalStore.db(context, user)?.starred()?.list().orEmpty()
    }

    fun isStarred(messageId: String): Boolean = _items.value.any { it.messageId == messageId }

    fun toggle(row: StarredEntity) {
        val on = isStarred(row.messageId)
        _items.update { list -> if (on) list.filterNot { it.messageId == row.messageId } else listOf(row) + list }
        val app = appContext ?: return
        val user = Session.username.value
        writer.post {
            val dao = LocalStore.db(app, user)?.starred() ?: return@post
            if (on) dao.delete(row.messageId) else dao.upsert(row)
        }
    }

    fun clear() {
        _items.value = emptyList()
    }
}
