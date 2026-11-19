package com.aird.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.aird.chat.data.ChatRepository
import com.aird.chat.data.CollabRepository
import com.aird.chat.model.ListArtifact
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object WidgetUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun onConversationsRefreshed(context: Context) {
        val app = context.applicationContext
        WidgetStore.writeUnread(app, ChatRepository.conversations.value)
        scope.launch {
            UnreadChatsWidget().updateAll(app)
            refreshChecklist(app)
            ChecklistWidget().updateAll(app)
        }
    }

    suspend fun refreshChecklist(context: Context) {
        val app = context.applicationContext
        var listId = WidgetStore.checklistListId(app)
        if (listId.isBlank()) {
            val board = runCatching { CollabRepository.listBoard(app) }.getOrNull() ?: return
            val first = board.lists.firstOrNull() ?: return
            listId = first.id
            WidgetStore.setChecklistList(app, first.id, first.title)
        }
        CollabRepository.ensure(app, "list", listId, "")
        val list = CollabRepository.slots.value.values
            .mapNotNull { it.artifact as? ListArtifact }
            .firstOrNull { it.id == listId }
            ?: return
        WidgetStore.setChecklistList(app, list.id, list.title)
        WidgetStore.writeChecklistItems(
            app,
            list.items.map { entry -> entry.id to (entry.text to entry.done) },
        )
    }

    fun requestUpdate(context: Context) {
        val app = context.applicationContext
        scope.launch {
            UnreadChatsWidget().updateAll(app)
            ChecklistWidget().updateAll(app)
        }
    }
}
