package com.aird.chat.data

/** In-memory per-conversation draft store. Survives navigation within a session. */
object DraftStore {
    private val drafts = mutableMapOf<String, String>()

    fun get(conversationId: String): String = drafts[conversationId].orEmpty()

    fun set(conversationId: String, text: String) {
        if (conversationId.isEmpty()) return
        if (text.isEmpty()) drafts.remove(conversationId) else drafts[conversationId] = text
    }

    fun clear() = drafts.clear()
}

