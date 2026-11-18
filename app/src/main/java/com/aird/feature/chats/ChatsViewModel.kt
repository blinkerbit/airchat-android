package com.aird.feature.chats

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aird.chat.data.ChatNotifier
import com.aird.chat.data.ChatRepository
import com.aird.chat.data.DraftStore
import com.aird.chat.model.Conversation
import com.aird.chat.ui.EnhancedSecureHub
import com.aird.data.ChatPrefsStore
import com.aird.data.db.ConversationPrefsEntity
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class ChatFilter(val label: String) { All("All"), Unread("Unread"), Groups("Groups"), Requests("Requests") }

enum class PreviewTone { Normal, Typing, Draft, Request }

@Immutable
data class ChatRowUi(
    val id: String,
    val name: String,
    val peer: String,
    val group: Boolean,
    val online: Boolean,
    val preview: String,
    val tone: PreviewTone,
    val time: String,
    val unread: Int,
    val muted: Boolean,
    val pinned: Boolean,
    val request: String,
)

@Immutable
data class SecureRowUi(val roomId: String, val peer: String, val preview: String, val requested: Boolean)

@Immutable
data class ChatsUi(
    val loaded: Boolean = false,
    val rows: ImmutableList<ChatRowUi> = persistentListOf(),
    val secure: ImmutableList<SecureRowUi> = persistentListOf(),
    val filter: ChatFilter = ChatFilter.All,
    val query: String = "",
    val total: Int = 0,
    val unreadChats: Int = 0,
    val requests: Int = 0,
    val groups: Int = 0,
)

class ChatsViewModel(application: Application) : AndroidViewModel(application) {
    private val filter = MutableStateFlow(ChatFilter.All)
    private val query = MutableStateFlow("")

    val refreshing = ChatRepository.refreshing

    private val rows = combine(
        ChatRepository.conversations,
        ChatRepository.presence,
        ChatRepository.typingIn,
        DraftStore.drafts,
        ChatPrefsStore.prefs,
    ) { list, presence, typing, drafts, prefs -> buildRows(list, presence, typing, drafts, prefs) }
        .flowOn(Dispatchers.Default)

    val ui: StateFlow<ChatsUi> = combine(
        rows,
        EnhancedSecureHub.summaries,
        filter,
        query,
        ChatRepository.loaded,
    ) { all, secure, f, q, loaded ->
        val needle = q.trim()
        val shown = all.filter { row ->
            val passes = when (f) {
                ChatFilter.All -> true
                ChatFilter.Unread -> row.unread > 0
                ChatFilter.Groups -> row.group
                ChatFilter.Requests -> row.request.isNotEmpty()
            }
            passes && (needle.isEmpty() || row.name.contains(needle, true) || row.peer.contains(needle, true) || row.preview.contains(needle, true))
        }
        val secureShown = if (f != ChatFilter.All && f != ChatFilter.Unread) emptyList() else secure.filter {
            needle.isEmpty() || it.peer.contains(needle, true) || it.preview.contains(needle, true)
        }
        ChatsUi(
            loaded = loaded || all.isNotEmpty(),
            rows = shown.toImmutableList(),
            secure = secureShown.map { SecureRowUi(it.roomId, it.peer, it.preview, it.requested) }.toImmutableList(),
            filter = f,
            query = q,
            total = all.size,
            unreadChats = all.count { it.unread > 0 },
            requests = all.count { it.request.isNotEmpty() },
            groups = all.count { it.group },
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatsUi(loaded = ChatRepository.loaded.value))

    fun setFilter(value: ChatFilter) {
        filter.value = if (filter.value == value) ChatFilter.All else value
    }

    fun setQuery(value: String) {
        query.value = value
    }

    fun refresh() {
        viewModelScope.launch { ChatRepository.pullRefresh(getApplication()) }
    }

    fun togglePin(id: String) {
        ChatPrefsStore.setPinned(id, ChatPrefsStore.of(id).pinnedAt <= 0L)
    }

    fun setMuted(id: String, muted: Boolean) {
        viewModelScope.launch { ChatRepository.mute(getApplication(), id, muted) }
    }

    fun delete(id: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch { onDone(ChatRepository.deleteConversation(getApplication(), id)) }
    }

    fun clearLocal(id: String) = ChatRepository.clearLocalChat(id)

    fun bubble(id: String) {
        val conversation = ChatRepository.conversations.value.firstOrNull { it.id == id } ?: return
        ChatNotifier.showBubble(getApplication(), conversation)
    }

    fun respond(id: String, accept: Boolean) {
        viewModelScope.launch { ChatRepository.respond(getApplication(), id, if (accept) "accept" else "decline") }
    }

    private fun buildRows(
        list: List<Conversation>,
        presence: Map<String, Boolean>,
        typing: Map<String, String>,
        drafts: Map<String, String>,
        prefs: Map<String, ConversationPrefsEntity>,
    ): List<ChatRowUi> {
        val rows = list.map { c ->
            val group = c.kind == "group"
            val online = if (group) false else presence[c.peer] ?: presence[c.peer.lowercase()] ?: c.online
            val typist = typing[c.id]
            val draft = drafts[c.id].orEmpty().trim()
            val (preview, tone) = when {
                typist != null -> (if (group) "$typist is typing…" else "typing…") to PreviewTone.Typing
                c.request == "incoming" -> "Wants to chat with you" to PreviewTone.Request
                draft.isNotEmpty() -> draft.replace('\n', ' ') to PreviewTone.Draft
                else -> c.preview.ifEmpty { "Encrypted message" }.replace('\n', ' ') to PreviewTone.Normal
            }
            ChatRowUi(
                id = c.id,
                name = c.name(),
                peer = c.peer,
                group = group,
                online = online,
                preview = preview,
                tone = tone,
                time = c.whenLabel(),
                unread = c.unread,
                muted = c.muted,
                pinned = (prefs[c.id]?.pinnedAt ?: 0L) > 0L,
                request = c.request,
            )
        }
        val pinned = rows.filter { it.pinned }.sortedByDescending { prefs[it.id]?.pinnedAt ?: 0L }
        return if (pinned.isEmpty()) rows else pinned + rows.filterNot { it.pinned }
    }
}
