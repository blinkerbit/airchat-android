package com.aird.feature.thread

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aird.app.ShellState
import com.aird.chat.data.ChatRepository
import com.aird.chat.model.Conversation
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class ThreadHeader(
    val id: String,
    val name: String,
    val peer: String,
    val group: Boolean,
    val online: Boolean,
    val muted: Boolean,
    val request: String,
    val members: Int,
    val onlineMembers: Int,
    val typing: String,
    val known: Boolean,
) {
    val status: String
        get() = when {
            typing.isNotEmpty() -> if (group) "$typing is typing…" else "typing…"
            group -> buildString {
                append(if (members == 1) "1 member" else "$members members")
                if (onlineMembers > 0) append(" · $onlineMembers online")
            }
            online -> "Online"
            else -> "End-to-end encrypted"
        }
}

class ThreadViewModel(
    application: Application,
    val id: String,
    private val ownsThread: Boolean = true,
) : AndroidViewModel(application) {
    val header: StateFlow<ThreadHeader> = combine(
        ChatRepository.conversations,
        ChatRepository.presence,
        ChatRepository.typingIn,
    ) { list, presence, typing -> headerOf(list.firstOrNull { it.id == id }, presence, typing[id].orEmpty()) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            headerOf(ChatRepository.conversations.value.firstOrNull { it.id == id }, ChatRepository.presence.value, ""),
        )

    /** True once the repository's open thread is this one. */
    val ready: StateFlow<Boolean> = ChatRepository.activeId.map { it == id }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ChatRepository.activeId.value == id)

    fun ensureOpen() {
        if (ChatRepository.activeId.value == id) return
        viewModelScope.launch { ChatRepository.open(getApplication(), id) }
    }

    fun close() = ChatRepository.leave(getApplication(), id)

    override fun onCleared() {
        if (ownsThread && !ShellState.twoPane) ChatRepository.leave(getApplication(), id)
    }

    private fun headerOf(c: Conversation?, presence: Map<String, Boolean>, typing: String): ThreadHeader {
        fun online(name: String, fallback: Boolean) = presence[name] ?: presence[name.lowercase()] ?: fallback
        val group = c?.kind == "group"
        return ThreadHeader(
            id = id,
            name = c?.name() ?: "Chat",
            peer = c?.peer.orEmpty(),
            group = group,
            online = c != null && !group && online(c.peer, c.online),
            muted = c?.muted == true,
            request = c?.request.orEmpty(),
            members = c?.members?.size ?: 0,
            onlineMembers = c?.members?.count { online(it.username, it.online) } ?: 0,
            typing = typing,
            known = c != null,
        )
    }
}
