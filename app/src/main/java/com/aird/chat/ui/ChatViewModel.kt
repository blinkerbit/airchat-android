package com.aird.chat.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aird.chat.data.ChatRepository
import com.aird.chat.model.ChatMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    val conversations = ChatRepository.conversations
    val messages = ChatRepository.messages
    val activeId = ChatRepository.activeId
    val error = ChatRepository.error
    val typing = ChatRepository.typing
    val presence = ChatRepository.presence
    val receipts = ChatRepository.receipts
    val pins = ChatRepository.pins
    val uploading = ChatRepository.uploading
    val loadingMore = ChatRepository.loadingMore
    val hasMore = ChatRepository.hasMore
    val people = MutableStateFlow<List<String>>(emptyList())

    fun refresh() {
        viewModelScope.launch { ChatRepository.refresh(getApplication()) }
    }

    fun open(conversationId: String) {
        viewModelScope.launch { ChatRepository.open(getApplication(), conversationId) }
    }

    fun loadMore(onLoaded: (Int) -> Unit = {}) {
        viewModelScope.launch {
            val count = ChatRepository.loadMore(getApplication())
            onLoaded(count)
        }
    }

    fun send(text: String, replyToId: String?, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch { onResult(ChatRepository.send(getApplication(), text, replyToId)) }
    }

    fun retry(message: ChatMessage) {
        viewModelScope.launch { ChatRepository.retry(getApplication(), message) }
    }

    fun respond(action: String) {
        val id = ChatRepository.activeId.value
        if (id.isEmpty()) return
        viewModelScope.launch { ChatRepository.respond(getApplication(), id, action) }
    }

    fun attach(uris: List<Uri>, caption: String, replyToId: String?) {
        viewModelScope.launch { ChatRepository.attach(getApplication(), uris, caption, replyToId) }
    }

    fun attachPaths(paths: List<String>, caption: String, replyToId: String?) {
        viewModelScope.launch { ChatRepository.attachPaths(getApplication(), paths, caption, replyToId) }
    }

    fun saveCopy(message: ChatMessage, index: Int, destDir: String) {
        viewModelScope.launch { ChatRepository.saveCopy(getApplication(), message, index, destDir) }
    }

    fun attachBytes(name: String, mime: String, bytes: ByteArray, caption: String, replyToId: String?) {
        viewModelScope.launch { ChatRepository.attachBytes(getApplication(), name, mime, bytes, caption, replyToId) }
    }

    fun edit(message: ChatMessage, text: String) {
        viewModelScope.launch { ChatRepository.edit(getApplication(), message, text) }
    }

    fun delete(message: ChatMessage) {
        viewModelScope.launch { ChatRepository.delete(getApplication(), message) }
    }

    fun react(message: ChatMessage, emoji: String) {
        viewModelScope.launch { ChatRepository.react(getApplication(), message, emoji) }
    }

    fun pin(message: ChatMessage) {
        viewModelScope.launch { ChatRepository.pin(getApplication(), message) }
    }

    fun forward(message: ChatMessage, targets: List<String>) {
        viewModelScope.launch { ChatRepository.forward(getApplication(), message, targets) }
    }

    fun mute(muted: Boolean) {
        val id = ChatRepository.activeId.value
        if (id.isEmpty()) return
        viewModelScope.launch { ChatRepository.mute(getApplication(), id, muted) }
    }

    fun lookup(query: String) {
        viewModelScope.launch { people.value = ChatRepository.searchUsers(getApplication(), query) }
    }

    fun createDm(username: String) {
        viewModelScope.launch { ChatRepository.createDm(getApplication(), username) }
    }

    fun createGroup(title: String, members: List<String>) {
        viewModelScope.launch { ChatRepository.createGroup(getApplication(), title, members) }
    }

    fun typing() = ChatRepository.typing()

    fun back() = ChatRepository.close()
}
