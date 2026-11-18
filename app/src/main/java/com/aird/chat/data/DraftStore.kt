package com.aird.chat.data

import android.content.Context
import com.aird.data.db.DraftEntity
import com.aird.data.db.LocalStore
import com.aird.data.db.SerialWriter
import com.aird.session.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Per-conversation drafts. Survive restarts; reads are instant from memory. */
object DraftStore {
    private val _drafts = MutableStateFlow<Map<String, String>>(emptyMap())
    val drafts: StateFlow<Map<String, String>> = _drafts
    private val writer = SerialWriter()
    @Volatile private var appContext: Context? = null

    suspend fun attach(context: Context, user: String) {
        appContext = context.applicationContext
        val db = LocalStore.db(context, user) ?: return
        val rows = db.drafts().all().associate { it.conversationId to it.text }
        _drafts.update { live -> rows + live }
    }

    fun get(conversationId: String): String = _drafts.value[conversationId].orEmpty()

    fun set(conversationId: String, text: String) {
        if (conversationId.isEmpty()) return
        if (_drafts.value[conversationId].orEmpty() == text) return
        _drafts.update { if (text.isBlank()) it - conversationId else it + (conversationId to text) }
        val app = appContext ?: return
        val user = Session.username.value
        writer.post {
            val dao = LocalStore.db(app, user)?.drafts() ?: return@post
            if (text.isBlank()) dao.delete(conversationId)
            else dao.upsert(DraftEntity(conversationId, text, System.currentTimeMillis()))
        }
    }

    fun clear() {
        _drafts.value = emptyMap()
    }
}
