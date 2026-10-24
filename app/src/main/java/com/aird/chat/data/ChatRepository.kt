package com.aird.chat.data

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.OpenableColumns
import com.aird.AppForeground
import com.aird.chat.model.ChatAttachment
import com.aird.chat.model.ChatMessage
import com.aird.chat.model.ChatPin
import com.aird.chat.model.Conversation
import com.aird.chat.model.Member
import com.aird.chat.model.Reaction
import com.aird.chat.model.ReadReceipt
import com.aird.chat.model.nowIso
import com.aird.session.AirdHttp
import com.aird.session.AirdSocket
import com.aird.session.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

object ChatRepository {
    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _activeId = MutableStateFlow("")
    val activeId: StateFlow<String> = _activeId

    private val _error = MutableStateFlow("")
    val error: StateFlow<String> = _error

    private val _typing = MutableStateFlow("")
    val typing: StateFlow<String> = _typing

    private val _presence = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val presence: StateFlow<Map<String, Boolean>> = _presence

    private val _receipts = MutableStateFlow<List<ReadReceipt>>(emptyList())
    val receipts: StateFlow<List<ReadReceipt>> = _receipts

    private val _pins = MutableStateFlow<List<ChatPin>>(emptyList())
    val pins: StateFlow<List<ChatPin>> = _pins

    private val _uploading = MutableStateFlow(false)
    val uploading: StateFlow<Boolean> = _uploading

    private val _loadingMore = MutableStateFlow(false)
    val loadingMore: StateFlow<Boolean> = _loadingMore

    private val _hasMore = MutableStateFlow(true)
    val hasMore: StateFlow<Boolean> = _hasMore

    private val _pendingOpen = MutableStateFlow("")
    val pendingOpen: StateFlow<String> = _pendingOpen

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Any()
    private val mentionPattern = Regex("@([A-Za-z0-9_.\\-@]+)")
    private var lastTyping = 0L
    private var typingClear: kotlinx.coroutines.Job? = null

    fun requestOpen(conversationId: String) {
        _pendingOpen.value = conversationId
    }

    fun consumePending() {
        _pendingOpen.value = ""
    }

    suspend fun prepare(context: Context) {
        val me = Session.username.value
        if (me.isEmpty()) return
        try {
            ChatE2E.prepare(context, me)
        } catch (err: Exception) {
            _error.value = err.message ?: "Encryption is not ready"
        }
    }

    suspend fun refresh(context: Context, quiet: Boolean = false) {
        val response = try {
            AirdHttp.get(context, "/api/chat/conversations")
        } catch (err: Exception) {
            if (!quiet) _error.value = err.message ?: "Could not load conversations"
            return
        }
        if (!response.ok) {
            if (!quiet) _error.value = "Could not load conversations"
            return
        }
        val array = response.json().optJSONArray("conversations") ?: return
        val items = buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                add(toConversation(context, item))
            }
        }
        _conversations.value = items
        val presenceMap = mutableMapOf<String, Boolean>()
        items.forEach { conv ->
            if (conv.peer.isNotEmpty()) {
                presenceMap[conv.peer] = conv.online
                presenceMap[conv.peer.lowercase()] = conv.online
            }
            conv.members.forEach { m ->
                presenceMap[m.username] = m.online
                presenceMap[m.username.lowercase()] = m.online
            }
        }
        _presence.value = _presence.value + presenceMap
        _error.value = ""
    }

    suspend fun searchUsers(context: Context, query: String): List<String> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val response = AirdHttp.get(context, "/api/users/search?q=${URLEncoder.encode(q, "UTF-8")}")
        if (!response.ok) return emptyList()
        val array = response.json().optJSONArray("users") ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val name = array.optJSONObject(i)?.optString("username").orEmpty()
                if (name.isNotEmpty()) add(name)
            }
        }
    }

    suspend fun createDm(context: Context, username: String): Boolean {
        val response = AirdHttp.send(context, "/api/chat/conversations", "POST", JSONObject().put("username", username.trim()))
        if (!response.ok) {
            _error.value = response.json().optString("error", "Could not start the chat")
            return false
        }
        val id = response.json().optJSONObject("conversation")?.optString("id").orEmpty()
        if (id.isEmpty()) return false
        setupAndOpen(context, id)
        return true
    }

    suspend fun createGroup(context: Context, title: String, members: List<String>): Boolean {
        val response = AirdHttp.send(
            context,
            "/api/chat/conversations",
            "POST",
            JSONObject().put("title", title.trim()).put("members", JSONArray(members)),
        )
        if (!response.ok) {
            _error.value = response.json().optString("error", "Could not create the group")
            return false
        }
        val id = response.json().optJSONObject("conversation")?.optString("id").orEmpty()
        if (id.isEmpty()) return false
        setupAndOpen(context, id)
        return true
    }

    suspend fun open(context: Context, conversationId: String) {
        _activeId.value = conversationId
        _error.value = ""
        _typing.value = ""
        _hasMore.value = true
        _loadingMore.value = false
        ChatNotifier.cancel(context, conversationId)
        val response = AirdHttp.get(context, "/api/chat/conversations/$conversationId/messages?limit=80")
        if (!response.ok) {
            _error.value = "Could not open this conversation"
            return
        }
        val root = response.json()
        val array = root.optJSONArray("messages")
        val me = Session.username.value
        val items = buildList {
            if (array != null) {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    add(toMessage(context, item, me))
                }
            }
        }
        _messages.value = items
        _receipts.value = receiptsOf(root.optJSONArray("receipts"))
        _pins.value = pinsOf(root.optJSONArray("pins"))
        if (items.any { it.e2e && !it.decryptOk }) {
            AirdSocket.send(JSONObject().put("type", "chat_e2e_need_key").put("conversation_id", conversationId).toString())
        }
        markRead(items.lastOrNull { !it.pending })
    }

    suspend fun loadMore(context: Context): Int {
        val convId = _activeId.value
        if (convId.isEmpty() || _loadingMore.value || !_hasMore.value) return 0

        val oldest = _messages.value.firstOrNull { !it.pending && !it.id.startsWith("local-") } ?: return 0

        _loadingMore.value = true
        try {
            val response = AirdHttp.get(context, "/api/chat/conversations/$convId/messages?before_id=${oldest.id}&limit=50")
            if (!response.ok) {
                _loadingMore.value = false
                return 0
            }
            val root = response.json()
            val array = root.optJSONArray("messages") ?: JSONArray()
            if (array.length() == 0) {
                _hasMore.value = false
                _loadingMore.value = false
                return 0
            }
            val me = Session.username.value
            val olderItems = buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    add(toMessage(context, item, me))
                }
            }
            if (olderItems.isEmpty()) {
                _hasMore.value = false
            } else {
                updateMessages { current ->
                    val existingIds = current.map { it.id }.toSet()
                    val filteredOlder = olderItems.filterNot { it.id in existingIds }
                    if (filteredOlder.isEmpty()) {
                        _hasMore.value = false
                        current
                    } else {
                        filteredOlder + current
                    }
                }
            }
            return olderItems.size
        } catch (_: Exception) {
            return 0
        } finally {
            _loadingMore.value = false
        }
    }

    fun close() {
        _activeId.value = ""
        _messages.value = emptyList()
        _receipts.value = emptyList()
        _pins.value = emptyList()
        _typing.value = ""
    }

    fun typing() {
        val convId = _activeId.value
        if (convId.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastTyping < 2000) return
        lastTyping = now
        AirdSocket.send(JSONObject().put("type", "chat_typing").put("conversation_id", convId).toString())
    }

    suspend fun send(context: Context, text: String, replyToId: String?): Boolean {
        val convId = _activeId.value
        val body = text.trim()
        if (convId.isEmpty() || body.isEmpty()) return false
        return deliverText(context, convId, body, replyToId, optimistic = true)
    }

    suspend fun retry(context: Context, message: ChatMessage): Boolean {
        if (!message.failed) return false
        updateMessages { current ->
            current.map { item ->
                if (item.id == message.id) item.copy(pending = true, failed = false) else item
            }
        }
        return deliverText(
            context,
            message.conversationId,
            message.body,
            message.replyToId.ifBlank { null },
            optimistic = false,
            existingLocalId = message.id,
        )
    }

    suspend fun respond(context: Context, conversationId: String, action: String): Boolean {
        val response = AirdHttp.send(
            context,
            "/api/chat/conversations/$conversationId/request",
            "POST",
            JSONObject().put("action", action),
        )
        if (!response.ok) {
            _error.value = response.json().optString("error", "Could not update the request")
            return false
        }
        if (action == "decline") {
            close()
            refresh(context, quiet = true)
            return true
        }
        refresh(context, quiet = true)
        open(context, conversationId)
        return true
    }

    fun clear() {
        _conversations.value = emptyList()
        _messages.value = emptyList()
        _activeId.value = ""
        _error.value = ""
        _typing.value = ""
        _presence.value = emptyMap()
        _receipts.value = emptyList()
        _pins.value = emptyList()
        _uploading.value = false
        _loadingMore.value = false
        _hasMore.value = true
        _pendingOpen.value = ""
    }

    suspend fun sendFromNotification(context: Context, conversationId: String, text: String) {
        val body = text.trim()
        if (conversationId.isEmpty() || body.isEmpty()) return
        if (Session.username.value.isEmpty()) Session.probe(context)
        prepare(context)
        val sent = deliverText(context, conversationId, body, null, optimistic = conversationId == _activeId.value)
        if (!sent) _error.value = "Reply was not sent"
        else {
            val title = _conversations.value.firstOrNull { it.id == conversationId }?.name() ?: "Aird Chat"
            ChatNotifier.show(context, conversationId, title, Session.username.value, body, mine = true)
        }
    }

    suspend fun attach(context: Context, uris: List<Uri>, caption: String, replyToId: String?) {
        val convId = _activeId.value
        if (convId.isEmpty() || uris.isEmpty()) return
        val files = uris.take(10).mapNotNull { uri -> readUri(context, uri) }
        if (uris.size > 10) _error.value = "Up to 10 files"
        if (files.isEmpty()) return
        uploadFiles(context, convId, files, emptyList(), caption.trim(), replyToId)
    }

    suspend fun attachPaths(context: Context, paths: List<String>, caption: String, replyToId: String?) {
        val convId = _activeId.value
        val chosen = paths.map { it.trim().trimStart('/') }.filter { it.isNotEmpty() }.distinct().take(10)
        if (convId.isEmpty() || chosen.isEmpty()) return
        uploadFiles(context, convId, emptyList(), chosen, caption.trim(), replyToId)
    }

    suspend fun attachBytes(context: Context, name: String, mime: String, bytes: ByteArray, caption: String, replyToId: String?) {
        val convId = _activeId.value
        if (convId.isEmpty() || bytes.isEmpty()) return
        val type = mime.ifBlank { "application/octet-stream" }.toMediaType()
        uploadFiles(
            context,
            convId,
            listOf(UploadPart(safeName(name, mime), mime, bytes.toRequestBody(type))),
            emptyList(),
            caption.trim(),
            replyToId,
        )
    }

    suspend fun saveCopy(context: Context, message: ChatMessage, index: Int, destDir: String) {
        val response = try {
            AirdHttp.send(
                context,
                "/api/chat/conversations/${message.conversationId}/messages/${message.id}/save-copy?i=$index",
                "POST",
                JSONObject().put("dest_dir", destDir),
            )
        } catch (err: Exception) {
            _error.value = err.message ?: "Could not save a copy"
            return
        }
        if (!response.ok) {
            _error.value = response.json().optString("error", "Could not save a copy")
            return
        }
        if (response.json().optBoolean("already_owner")) {
            _error.value = "You already have this file"
            return
        }
        _error.value = ""
    }

    suspend fun listFiles(context: Context, path: String): List<AirdEntry> {
        val clean = path.trim().trim('/')
        val encoded = if (clean.isEmpty()) {
            ""
        } else {
            clean.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        }
        val response = try {
            AirdHttp.get(context, "/api/files/$encoded")
        } catch (_: Exception) {
            return emptyList()
        }
        if (!response.ok) return emptyList()
        val files = response.json().optJSONArray("files") ?: return emptyList()
        return buildList {
            for (i in 0 until files.length()) {
                val item = files.optJSONObject(i) ?: continue
                val name = item.optString("name")
                if (name.isEmpty()) continue
                val child = if (clean.isEmpty()) name else "$clean/$name"
                add(AirdEntry(name, child, item.optBoolean("is_dir")))
            }
        }
    }

    suspend fun edit(context: Context, message: ChatMessage, text: String) {
        val body = text.trim()
        val convId = message.conversationId
        if (body.isEmpty() || !message.mine) {
            _error.value = "You can only change your own messages"
            return
        }
        val names = mentionsIn(body, convId)
        val payload = if (message.e2e) {
            val sealed = ChatE2E.encrypt(context, convId, body) ?: run {
                _error.value = "This conversation is still locked"
                return
            }
            JSONObject().put("e2e", sealed).put("mentions", JSONArray(names))
        } else {
            JSONObject().put("body", body).put("mentions", JSONArray(names))
        }
        val response = AirdHttp.send(context, "/api/chat/conversations/$convId/messages/${message.id}", "PATCH", payload)
        if (!response.ok) {
            _error.value = response.json().optString("error", "Could not edit")
            return
        }
        val updated = response.json().optJSONObject("message")
        if (updated != null) replaceMessage(toMessage(context, updated, Session.username.value))
    }

    suspend fun delete(context: Context, message: ChatMessage) {
        if (!message.mine) {
            _error.value = "You can only change your own messages"
            return
        }
        if (message.id.startsWith("local-")) {
            updateMessages { it.filterNot { item -> item.id == message.id } }
            return
        }
        val response = AirdHttp.send(
            context,
            "/api/chat/conversations/${message.conversationId}/messages/${message.id}",
            "DELETE",
            JSONObject(),
        )
        if (!response.ok) {
            _error.value = response.json().optString("error", "Could not delete")
            return
        }
        updateMessages { it.filterNot { item -> item.id == message.id } }
    }

    suspend fun react(context: Context, message: ChatMessage, emoji: String) {
        val response = AirdHttp.send(
            context,
            "/api/chat/conversations/${message.conversationId}/messages/${message.id}/reactions",
            "POST",
            JSONObject().put("emoji", emoji),
        )
        if (!response.ok) {
            _error.value = response.json().optString("error", "Could not react")
            return
        }
        applyReactions(message.id, response.json().optJSONArray("reactions"))
    }

    suspend fun pin(context: Context, message: ChatMessage) {
        val pinned = _pins.value.any { it.messageId == message.id }
        val path = "/api/chat/conversations/${message.conversationId}/messages/${message.id}/pin"
        val response = if (pinned) {
            AirdHttp.send(context, path, "DELETE", JSONObject())
        } else {
            AirdHttp.send(context, path, "POST", JSONObject())
        }
        if (!response.ok) {
            _error.value = response.json().optString("error", "Could not pin")
            return
        }
        _pins.value = pinsOf(response.json().optJSONArray("pins"))
    }

    suspend fun forward(context: Context, message: ChatMessage, targets: List<String>) {
        if (targets.isEmpty()) return
        if (message.e2e) {
            if (!message.decryptOk || message.body == "Encrypted message") {
                _error.value = "Unlock the message before forwarding it"
                return
            }
            targets.forEach { target ->
                if (message.attachments.isEmpty()) {
                    deliverText(context, target, message.body, null, optimistic = target == _activeId.value)
                } else {
                    val files = message.attachments.mapNotNull { attachment ->
                        val bytes = downloadBytes(context, attachment.url) ?: return@mapNotNull null
                        val mime = kindMime(attachment.mediaKind)
                        UploadPart(safeName(attachment.name, mime), mime, bytes.toRequestBody(mime.toMediaType()))
                    }
                    if (files.isEmpty()) {
                        _error.value = "Could not forward the file"
                    } else {
                        uploadFiles(context, target, files, emptyList(), message.body, null)
                    }
                }
            }
            return
        }
        val response = AirdHttp.send(
            context,
            "/api/chat/conversations/${message.conversationId}/messages/${message.id}/forward",
            "POST",
            JSONObject().put("conversation_ids", JSONArray(targets)),
        )
        if (!response.ok) _error.value = response.json().optString("error", "Could not forward")
    }

    suspend fun mute(context: Context, conversationId: String, muted: Boolean) {
        val response = AirdHttp.send(
            context,
            "/api/chat/conversations/$conversationId/mute",
            "POST",
            JSONObject().put("muted", muted),
        )
        if (!response.ok) {
            _error.value = response.json().optString("error", "Could not update mute")
            return
        }
        _conversations.value = _conversations.value.map { item ->
            if (item.id == conversationId) item.copy(muted = muted) else item
        }
    }

    fun onEvent(context: Context, raw: String) {
        val data = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return
        }
        when (data.optString("type")) {
            "chat_message" -> {
                val message = data.optJSONObject("message") ?: return
                val convId = message.optString("conversation_id")
                scope.launch {
                    val item = toMessage(context, message, Session.username.value)
                    if (convId == _activeId.value) {
                        updateMessages { current ->
                            val withoutPending = if (item.mine) {
                                current.filterNot { it.pending && it.body.trim() == item.body.trim() }
                            } else current

                            val exists = withoutPending.any { it.id == item.id }
                            if (exists) {
                                withoutPending.map { if (it.id == item.id) item else it }
                            } else {
                                withoutPending + item
                            }
                        }
                        if (AppForeground.resumed()) markRead(item)
                    }
                    refresh(context, quiet = true)
                }
            }
            "chat_notify" -> {
                val convId = data.optString("conversation_id")
                if (convId.isEmpty()) return
                if (data.optString("sender").equals(Session.username.value, true)) return
                val looking = AppForeground.resumed() && convId == _activeId.value
                if (looking) return
                val sender = data.optString("sender", "Aird")
                val title = _conversations.value.firstOrNull { it.id == convId }?.name() ?: sender
                ChatNotifier.show(context, convId, title, sender, data.optString("preview", "New message"))
                scope.launch { refresh(context, quiet = true) }
            }
            "chat_message_edited" -> {
                val message = data.optJSONObject("message") ?: return
                if (message.optString("conversation_id") != _activeId.value) return
                scope.launch { replaceMessage(toMessage(context, message, Session.username.value)) }
            }
            "chat_message_deleted" -> {
                if (data.optString("conversation_id") != _activeId.value) return
                val id = data.optString("message_id")
                updateMessages { it.filterNot { item -> item.id == id } }
            }
            "chat_reaction" -> {
                if (data.optString("conversation_id") != _activeId.value) return
                applyReactions(data.optString("message_id"), data.optJSONArray("reactions"))
            }
            "chat_receipt" -> {
                if (data.optString("conversation_id") != _activeId.value) return
                val username = data.optString("username")
                val next = _receipts.value.filterNot { it.username.equals(username, true) } + ReadReceipt(
                    username = username,
                    lastReadId = data.optString("last_read_id"),
                    lastReadAt = data.optString("last_read_at"),
                    decrypted = data.optBoolean("decrypted"),
                )
                _receipts.value = next
            }
            "chat_pin", "chat_unpin" -> {
                if (data.optString("conversation_id") != _activeId.value) return
                _pins.value = pinsOf(data.optJSONArray("pins"))
            }
            "chat_typing" -> {
                val convId = data.optString("conversation_id")
                val username = data.optString("username")
                if (convId != _activeId.value || username.equals(Session.username.value, true)) return
                _typing.value = username
                typingClear?.cancel()
                typingClear = scope.launch {
                    kotlinx.coroutines.delay(1500)
                    if (_typing.value == username) _typing.value = ""
                }
            }
            "chat_presence" -> {
                val username = data.optString("username")
                val isOnline = data.optBoolean("online")
                if (username.isNotEmpty()) {
                    _presence.value = _presence.value + (username to isOnline) + (username.lowercase() to isOnline)
                }
            }
            "chat_e2e_need_key" -> {
                val convId = data.optString("conversation_id")
                if (convId.isEmpty() || data.optString("username") == Session.username.value) return
                scope.launch { ChatE2E.encrypt(context, convId, "") }
            }
            "chat_e2e_key_ready" -> {
                val convId = data.optString("conversation_id")
                if (convId.isNotEmpty() && convId == _activeId.value) {
                    scope.launch { open(context, convId) }
                }
            }
            "error" -> _error.value = data.optString("message")
        }
    }

    private suspend fun setupAndOpen(context: Context, id: String) {
        try {
            if (ChatE2E.encrypt(context, id, "") == null) {
                AirdSocket.send(JSONObject().put("type", "chat_e2e_need_key").put("conversation_id", id).toString())
            }
        } catch (_: Exception) {
        }
        refresh(context, quiet = true)
        open(context, id)
    }

    private suspend fun deliverText(
        context: Context,
        convId: String,
        body: String,
        replyToId: String?,
        optimistic: Boolean,
        existingLocalId: String? = null,
    ): Boolean {
        val sealed = ChatE2E.encrypt(context, convId, body)
        if (sealed == null) {
            _error.value = "This conversation is still locked"
            AirdSocket.send(JSONObject().put("type", "chat_e2e_need_key").put("conversation_id", convId).toString())
            if (existingLocalId != null) markFailed(existingLocalId)
            return false
        }
        val names = mentionsIn(body, convId)
        val localId = if (optimistic) {
            val id = "local-${System.currentTimeMillis()}"
            updateMessages {
                it + ChatMessage(
                    id = id,
                    conversationId = convId,
                    sender = Session.username.value,
                    body = body,
                    createdAt = nowIso(),
                    mine = true,
                    pending = true,
                    e2e = true,
                    decryptOk = true,
                    replyToId = replyToId.orEmpty(),
                    mentions = names,
                )
            }
            id
        } else {
            existingLocalId
        }
        val socket = JSONObject()
            .put("type", "chat_send")
            .put("conversation_id", convId)
            .put("e2e", sealed)
            .put("mentions", JSONArray(names))
        if (!replyToId.isNullOrEmpty()) socket.put("reply_to_id", replyToId)
        if (AirdSocket.send(socket.toString()) && awaitEcho(localId)) return true
        val posted = postText(context, convId, body, replyToId, sealed, names, localId)
        if (!posted && localId != null) markFailed(localId)
        return posted
    }

    private suspend fun awaitEcho(localId: String?): Boolean {
        if (localId == null) return true
        repeat(15) {
            kotlinx.coroutines.delay(100)
            val row = _messages.value.firstOrNull { it.id == localId }
            if (row == null || !row.pending) return true
        }
        return false
    }

    private suspend fun postText(
        context: Context,
        convId: String,
        body: String,
        replyToId: String?,
        sealed: JSONObject,
        names: List<String>,
        localId: String?,
    ): Boolean {
        val http = JSONObject()
            .put("type", "text")
            .put("e2e", sealed)
            .put("mentions", JSONArray(names))
        if (!replyToId.isNullOrEmpty()) http.put("reply_to_id", replyToId)
        val response = try {
            AirdHttp.send(context, "/api/chat/conversations/$convId/messages", "POST", http)
        } catch (err: Exception) {
            _error.value = err.message ?: "Not connected"
            return false
        }
        if (!response.ok) {
            _error.value = response.json().optString("error", "Not connected")
            return false
        }
        val saved = response.json().optJSONObject("message")
        if (saved != null && convId == _activeId.value) {
            val item = toMessage(context, saved, Session.username.value)
            updateMessages { current ->
                val already = current.any { it.id == item.id }
                val echoed = localId != null &&
                    current.none { it.id == localId && it.pending } &&
                    current.any { !it.pending && it.mine && it.body.trim() == body.trim() }
                val without = if (localId == null) current else current.filterNot { it.id == localId }
                if (already || echoed) without else without + item
            }
        } else if (localId != null) {
            updateMessages { current -> current.filterNot { it.id == localId } }
        }
        return true
    }

    private fun markFailed(localId: String) {
        updateMessages { current ->
            current.map { item ->
                if (item.id == localId) item.copy(pending = false, failed = true) else item
            }
        }
    }

    private suspend fun uploadFiles(
        context: Context,
        convId: String,
        files: List<UploadPart>,
        paths: List<String>,
        caption: String,
        replyToId: String?,
    ) {
        _uploading.value = true
        try {
            val sealed = ChatE2E.encrypt(context, convId, caption)
            if (sealed == null) {
                _error.value = "This conversation is still locked"
                return
            }
            val names = mentionsIn(caption, convId)
            val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            files.forEach { file ->
                multipart.addFormDataPart("file", file.name, file.body)
            }
            paths.forEach { path -> multipart.addFormDataPart("source_path", path) }
            multipart.addFormDataPart("e2e", sealed.toString())
            multipart.addFormDataPart("mentions", JSONArray(names).toString())
            if (!replyToId.isNullOrEmpty()) multipart.addFormDataPart("reply_to_id", replyToId)
            val response = try {
                AirdHttp.upload(context, "/api/chat/conversations/$convId/attach", multipart.build())
            } catch (err: Exception) {
                _error.value = err.message ?: "Could not send the file"
                return
            }
            if (!response.ok) {
                _error.value = response.json().optString("error", "Could not send the file")
                return
            }
            val saved = response.json().optJSONObject("message")
            if (saved != null && convId == _activeId.value) {
                val item = toMessage(context, saved, Session.username.value)
                updateMessages { current -> if (current.any { it.id == item.id }) current else current + item }
            }
            refresh(context, quiet = true)
        } finally {
            _uploading.value = false
        }
    }

    private fun markRead(message: ChatMessage?) {
        val convId = _activeId.value
        if (message == null || convId.isEmpty() || message.pending || message.id.startsWith("local-")) return
        val decrypted = !message.e2e || message.decryptOk
        AirdSocket.send(
            JSONObject()
                .put("type", "chat_read")
                .put("conversation_id", convId)
                .put("message_id", message.id)
                .put("decrypted", decrypted)
                .toString(),
        )
    }

    private suspend fun toConversation(context: Context, item: JSONObject): Conversation {
        val id = item.optString("id")
        var preview = plain(item.optString("last_preview"))
        val sealed = item.optJSONObject("last_e2e")
        if (sealed != null) preview = ChatE2E.decrypt(context, id, sealed) ?: "Encrypted message"
        val members = buildList {
            val array = item.optJSONArray("members") ?: JSONArray()
            for (i in 0 until array.length()) {
                val member = array.optJSONObject(i) ?: continue
                val username = member.optString("username")
                if (username.isEmpty()) continue
                add(Member(username, member.optBoolean("online")))
            }
        }
        return Conversation(
            id = id,
            title = item.optString("title"),
            peer = item.optString("peer_username"),
            preview = preview,
            unread = item.optInt("unread"),
            updatedAt = item.optString("updated_at"),
            kind = item.optString("kind", "dm"),
            muted = item.optBoolean("muted"),
            members = members,
            online = item.optBoolean("online"),
            request = item.optString("request"),
        )
    }

    private suspend fun toMessage(context: Context, item: JSONObject, me: String): ChatMessage {
        val meta = item.optJSONObject("metadata")
        val sealed = meta?.optJSONObject("e2e")
        val e2e = sealed != null || item.optBoolean("e2e")
        val decrypted = if (sealed != null) ChatE2E.decrypt(context, item.optString("conversation_id"), sealed) else null
        val body = when {
            sealed != null -> decrypted ?: "Encrypted message"
            else -> plain(item.optString("body"))
        }
        val sender = item.optString("sender_username")
        return ChatMessage(
            id = item.optString("id"),
            conversationId = item.optString("conversation_id"),
            sender = sender,
            body = body,
            createdAt = item.optString("created_at"),
            mine = sender.equals(me, true),
            e2e = e2e,
            decryptOk = sealed == null || decrypted != null,
            replyToId = cleanReplyId(item.optString("reply_to_id")),
            mentions = strings(item.optJSONArray("mentions") ?: meta?.optJSONArray("mentions")),
            attachments = attachmentsOf(item),
            reactions = reactionsOf(item.optJSONArray("reactions")),
            forwarded = meta?.optJSONObject("forwarded_from") != null || item.optJSONObject("forwarded_from") != null,
            edited = messageWasEdited(item),
        )
    }

    private fun cleanReplyId(raw: String): String {
        val id = raw.trim()
        if (id.isEmpty() || id.equals("null", true)) return ""
        return id
    }

    private fun messageWasEdited(item: JSONObject): Boolean {
        if (item.optBoolean("edited")) return true
        if (item.isNull("edited_at")) return false
        val stamp = item.opt("edited_at")?.toString()?.trim().orEmpty()
        return stamp.isNotEmpty() && !stamp.equals("null", ignoreCase = true)
    }

    private fun attachmentsOf(item: JSONObject): List<ChatAttachment> {
        val meta = item.optJSONObject("metadata") ?: return emptyList()
        val conv = item.optString("conversation_id")
        val id = item.optString("id")
        val base = item.optString("file_url").ifEmpty { "/api/chat/conversations/$conv/messages/$id/file" }
        val array = meta.optJSONArray("attachments")
        if (array == null || array.length() == 0) {
            if (meta.optString("owner_rel").isEmpty() && item.optString("file_url").isEmpty()) return emptyList()
            return listOf(attachment(meta, base, 0))
        }
        return buildList {
            for (i in 0 until array.length()) {
                val entry = array.optJSONObject(i) ?: continue
                val url = if (i == 0) base else "$base?i=$i"
                add(attachment(entry, url, i))
            }
        }
    }

    private fun attachment(entry: JSONObject, url: String, index: Int): ChatAttachment {
        return ChatAttachment(
            name = entry.optString("original_name").ifEmpty { "File" },
            url = url,
            mediaKind = entry.optString("media_kind").ifEmpty { "file" },
            index = index,
        )
    }

    private fun reactionsOf(array: JSONArray?): List<Reaction> {
        if (array == null) return emptyList()
        val grouped = linkedMapOf<String, MutableList<String>>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val emoji = item.optString("emoji")
            if (emoji.isEmpty()) continue
            grouped.getOrPut(emoji) { mutableListOf() }.add(item.optString("username"))
        }
        return grouped.map { (emoji, users) -> Reaction(emoji, users) }
    }

    private fun receiptsOf(array: JSONArray?): List<ReadReceipt> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                add(
                    ReadReceipt(
                        username = item.optString("username"),
                        lastReadId = item.optString("last_read_id"),
                        lastReadAt = item.optString("last_read_at"),
                        decrypted = item.optBoolean("decrypted") || item.optInt("decrypted") == 1,
                    ),
                )
            }
        }
    }

    private fun pinsOf(array: JSONArray?): List<ChatPin> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("message_id").ifEmpty { item.optString("id") }
                if (id.isEmpty()) continue
                add(ChatPin(id, plain(item.optString("preview"))))
            }
        }
    }

    private fun applyReactions(messageId: String, array: JSONArray?) {
        val reactions = reactionsOf(array)
        updateMessages { current ->
            current.map { if (it.id == messageId) it.copy(reactions = reactions) else it }
        }
    }

    private suspend fun replaceMessage(message: ChatMessage) {
        updateMessages { current ->
            if (current.any { it.id == message.id }) current.map { if (it.id == message.id) message else it }
            else current + message
        }
    }

    private fun mentionsIn(text: String, convId: String): List<String> {
        val members = _conversations.value.firstOrNull { it.id == convId }?.members.orEmpty()
            .map { it.username }
            .filterNot { it.equals(Session.username.value, true) }
            .toSet()
        if (members.isEmpty()) return emptyList()
        return mentionPattern.findAll(text).map { it.groupValues[1] }.filter { it in members }.distinct().toList()
    }

    private fun updateMessages(block: (List<ChatMessage>) -> List<ChatMessage>) {
        synchronized(gate) {
            _messages.value = block(_messages.value)
        }
    }

    private fun strings(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val value = array.optString(i)
                if (value.isNotEmpty()) add(value)
            }
        }
    }

    private fun readUri(context: Context, uri: Uri): UploadPart? {
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val name = queryName(context, uri).ifBlank { "file" }
        val length = querySize(context, uri)
        val body = object : RequestBody() {
            override fun contentType() = mime.toMediaType()
            override fun contentLength(): Long = length
            override fun writeTo(sink: BufferedSink) {
                val input = context.contentResolver.openInputStream(uri) ?: error("Could not read the file")
                input.use { sink.writeAll(it.source()) }
            }
        }
        return UploadPart(safeName(name, mime), mime, body)
    }

    private suspend fun downloadBytes(context: Context, path: String): ByteArray? = try {
        withContext(Dispatchers.IO) {
            val request = okhttp3.Request.Builder().url(AirdHttp.absolute(context, path)).build()
            AirdHttp.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body.bytes()
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun querySize(context: Context, uri: Uri): Long {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && !cursor.isNull(index)) return cursor.getLong(index)
            }
        }
        return -1L
    }

    private fun queryName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index).orEmpty()
            }
        }
        return uri.lastPathSegment.orEmpty()
    }

    private fun safeName(raw: String, mime: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\').replace("..", "")
        val cleaned = base.filter { it.isLetterOrDigit() || it in "._- " }.take(80).trim().ifBlank { "file" }
        val gif = mime.equals("image/gif", true) || cleaned.endsWith(".gif", true)
        return if (gif && !cleaned.endsWith(".gif", true)) "$cleaned.gif" else cleaned
    }

    private fun kindMime(kind: String): String = when (kind) {
        "gif" -> "image/gif"
        "image" -> "image/jpeg"
        else -> "application/octet-stream"
    }

    private fun plain(raw: String): String {
        if (raw.isBlank()) return ""
        val unescaped = raw
            .replace("&nbsp;", " ")
            .replace("&nbsp", " ")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&#x27;", "'")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                android.text.Html.fromHtml(unescaped, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()
            } else {
                @Suppress("DEPRECATION")
                android.text.Html.fromHtml(unescaped).toString().trim()
            }
        } catch (_: Exception) {
            unescaped.replace(Regex("<[^>]+>"), "").trim()
        }
    }

    private data class UploadPart(val name: String, val mime: String, val body: RequestBody)
}

data class AirdEntry(val name: String, val path: String, val dir: Boolean)
