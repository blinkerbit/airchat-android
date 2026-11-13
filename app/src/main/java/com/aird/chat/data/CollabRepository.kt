package com.aird.chat.data

import android.content.Context
import com.aird.chat.model.CollabArtifact
import com.aird.chat.model.CollabFocus
import com.aird.chat.model.CollabRef
import com.aird.chat.model.CollabTag
import com.aird.chat.model.ListArtifact
import com.aird.chat.model.ListDraft
import com.aird.chat.model.ListEntry
import com.aird.chat.model.ListGroup
import com.aird.chat.model.ListReminder
import com.aird.chat.model.ListSummary
import com.aird.chat.model.PollSummary
import com.aird.chat.model.MeetArtifact
import com.aird.chat.model.MeetDraft
import com.aird.chat.model.PollArtifact
import com.aird.chat.model.PollDraft
import com.aird.chat.model.PollOption
import com.aird.chat.model.collabMentions
import com.aird.chat.model.collabToken
import com.aird.chat.model.stampStillOpen
import com.aird.session.AirdHttp
import com.aird.session.AirdResponse
import com.aird.session.Session
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger

class CollabException(message: String) : Exception(message)

data class CollabSlot(
    val artifact: CollabArtifact? = null,
    val error: String = "",
    val loading: Boolean = false,
    val busy: Boolean = false,
    val at: Long = 0L,
)

object CollabRepository {
    private val _slots = MutableStateFlow<Map<String, CollabSlot>>(emptyMap())
    val slots: StateFlow<Map<String, CollabSlot>> = _slots
    private val _refs = MutableStateFlow<List<CollabRef>>(emptyList())
    val refs: StateFlow<List<CollabRef>> = _refs
    private val _shelfFor = MutableStateFlow("")
    val shelfFor: StateFlow<String> = _shelfFor
    private val _focus = MutableStateFlow<CollabFocus?>(null)
    private val _live = MutableSharedFlow<Pair<String, String>>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val live: SharedFlow<Pair<String, String>> = _live
    val focus: StateFlow<CollabFocus?> = _focus
    private val slotLock = Any()
    private val shelfGen = AtomicInteger()

    fun open(kind: String, id: String = "", slug: String = "") {
        if (kind.isBlank() || (id.isBlank() && slug.isBlank())) return
        _focus.value = CollabFocus(kind, id, slug)
    }

    fun close() {
        _focus.value = null
    }

    suspend fun loadShelf(context: Context, convId: String) {
        val gen = shelfGen.incrementAndGet()
        if (_shelfFor.value != convId) {
            _shelfFor.value = convId
            _refs.value = emptyList()
        }
        if (convId.isBlank()) return
        val response = try {
            AirdHttp.get(context, "/api/chat/conversations/$convId/refs")
        } catch (err: Exception) {
            if (err is CancellationException) throw err
            return
        }
        if (gen != shelfGen.get()) return
        if (!response.ok) {
            _refs.value = emptyList()
            return
        }
        val array = response.json().optJSONArray("refs") ?: JSONArray()
        _refs.value = buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val kind = item.optString("kind")
                val id = item.optString("id")
                if (kind.isBlank() || id.isBlank()) continue
                add(
                    CollabRef(
                        kind = kind,
                        id = id,
                        slug = item.optString("slug"),
                        title = item.optString("title"),
                        token = item.optString("token"),
                        active = item.flag("active") ?: item.flag("live") ?: true,
                    ),
                )
            }
        }
    }

    suspend fun pinMentions(context: Context, convId: String, body: String) {
        if (convId.isBlank()) return
        val mentions = collabMentions(body).distinctBy { it.kind to it.slug }
        if (mentions.isEmpty()) return
        var changed = false
        for (mention in mentions) {
            val response = try {
                AirdHttp.send(
                    context,
                    "/api/chat/conversations/$convId/refs",
                    "POST",
                    JSONObject().put("kind", mention.kind).put("slug", mention.slug),
                )
            } catch (err: Exception) {
                if (err is CancellationException) throw err
                continue
            }
            if (response.ok) changed = true
        }
        if (changed && ChatRepository.activeId.value == convId) loadShelf(context, convId)
    }

    suspend fun ensure(context: Context, kind: String, id: String, slug: String) {
        val key = slotKey(kind, id, slug)
        val slot = _slots.value[key]
        if (slot?.loading == true) return
        if (slot?.artifact != null && System.currentTimeMillis() - slot.at < 15_000) return
        load(context, kind, id, slug)
    }

    suspend fun refresh(context: Context, kind: String, id: String, slug: String) {
        load(context, kind, id, slug)
    }

    suspend fun onRemote(context: Context, data: JSONObject) {
        if (data.optString("type") != "collab_update") return
        val kind = data.optString("kind")
        val id = data.optString("id")
        if (kind.isBlank() || id.isBlank()) return
        _live.tryEmit(kind to id)
        val here = ChatRepository.activeId.value
        val convs = data.optJSONArray("conversation_ids")
        var hereToo = false
        if (convs != null) {
            for (i in 0 until convs.length()) {
                if (convs.optString(i) == here) hereToo = true
            }
        }
        if (hereToo && here.isNotBlank()) loadShelf(context, here)
        val matching = _slots.value.values.filter { slot ->
            val art = slot.artifact
            art != null && art.type == kind && art.id == id
        }
        if (matching.isEmpty() || matching.any { it.busy }) return
        refresh(context, kind, id, "")
    }

    data class ListBoard(
        val lists: List<ListSummary>,
        val groups: List<ListGroup>,
        val reminders: List<ListReminder>,
    )

    suspend fun listBoard(context: Context, query: String = ""): ListBoard {
        val path = if (query.isBlank()) "/api/lists" else "/api/lists?q=${URLEncoder.encode(query, "UTF-8")}"
        val response = AirdHttp.get(context, path)
        if (!response.ok) throw CollabException(response.json().optString("error", "Could not load lists"))
        val body = response.json()
        return ListBoard(
            lists = jsonArray(body.optJSONArray("lists")).mapNotNull { item ->
                val id = item.optString("id")
                if (id.isBlank()) return@mapNotNull null
                val progress = item.optJSONObject("progress")
                ListSummary(
                    id = id,
                    title = item.optString("title"),
                    style = item.optString("kind").ifBlank { "todo" },
                    groupId = item.optString("group_id"),
                    done = progress?.optInt("done") ?: 0,
                    total = progress?.optInt("total") ?: 0,
                    nextDue = item.optCleanString("next_due"),
                    active = item.flag("active") ?: true,
                    organizer = item.optString("organizer"),
                    tags = collabTags(item.optJSONArray("tags")),
                    search = jsonArray(item.optJSONArray("notes")).joinToString(" ") { it.optString("title") } +
                        " " + jsonArray(item.optJSONArray("preview")).joinToString(" ") { it.optString("title") },
                )
            },
            groups = jsonArray(body.optJSONArray("groups")).mapNotNull { item ->
                val id = item.optString("id")
                if (id.isBlank()) return@mapNotNull null
                ListGroup(id, item.optString("name"))
            },
            reminders = jsonArray(body.optJSONArray("reminders")).mapNotNull { item ->
                val id = item.optString("item_id")
                val whenAt = item.optString("remind_at")
                if (id.isBlank() || whenAt.isBlank()) return@mapNotNull null
                ListReminder(item.optString("list_id"), id, item.optString("text"), item.optString("title"), whenAt)
            },
        )
    }

    suspend fun pollBoard(context: Context): List<PollSummary> {
        val response = AirdHttp.get(context, "/api/polls")
        if (!response.ok) throw CollabException(response.json().optString("error", "Could not load polls"))
        return jsonArray(response.json().optJSONArray("polls")).mapNotNull { item ->
            val id = item.optString("id")
            if (id.isBlank()) return@mapNotNull null
            PollSummary(
                id,
                item.optString("question"),
                item.optInt("total_votes"),
                item.flag("active") ?: true,
            )
        }
    }

    suspend fun createGroup(context: Context, name: String): ListGroup {
        val clean = name.trim()
        if (clean.isEmpty()) throw CollabException("Add a group name.")
        val response = AirdHttp.send(context, "/api/list-groups", "POST", JSONObject().put("name", clean.take(80)))
        if (!response.ok) throw CollabException(response.json().optString("error", "Could not create the group"))
        val body = response.json()
        return ListGroup(body.optString("id"), body.optString("name"))
    }

    suspend fun deleteGroup(context: Context, groupId: String) {
        val response = AirdHttp.send(context, "/api/list-groups/$groupId", "DELETE", JSONObject())
        if (!response.ok) throw CollabException(response.json().optString("error", "Could not remove the group"))
    }

    suspend fun renameGroup(context: Context, groupId: String, name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) throw CollabException("Add a group name.")
        val response = AirdHttp.send(
            context,
            "/api/list-groups/$groupId",
            "PATCH",
            JSONObject().put("name", clean.take(80)),
        )
        if (!response.ok) throw CollabException(response.json().optString("error", "Could not rename the group"))
    }

    suspend fun placeList(context: Context, listId: String, groupId: String) {
        val response = AirdHttp.send(
            context,
            "/api/lists/$listId/group",
            "POST",
            JSONObject().put("group_id", groupId),
        )
        if (!response.ok) throw CollabException(response.json().optString("error", "Could not move the list"))
    }

    suspend fun reorder(context: Context, list: ListArtifact, ids: List<String>) {
        val body = JSONObject().put("ids", JSONArray(ids))
        act(list) { sendDetail(context, "POST", "/api/lists/${list.id}/order", body, ::parseList) }
    }

    suspend fun shareIntoChat(context: Context, conversationId: String, kind: String, id: String) {
        val response = AirdHttp.send(
            context,
            "/api/chat/conversations/$conversationId/refs",
            "POST",
            JSONObject().put("kind", kind).put("id", id),
        )
        if (!response.ok) throw CollabException(response.json().optString("error", "Could not add it to the chat"))
    }

    private fun jsonArray(array: JSONArray?): List<JSONObject> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                add(item)
            }
        }
    }

    suspend fun createBoardPoll(context: Context, draft: PollDraft): String {
        val options = draft.options.map { it.trim() }.filter { it.isNotEmpty() }.take(12)
        if (draft.question.trim().isEmpty()) throw CollabException("Add a question.")
        if (options.size < 2) throw CollabException("Add at least two options.")
        val body = JSONObject()
            .put("question", draft.question.trim().take(400))
            .put("options", JSONArray(options))
            .put("allow_multi", draft.allowMulti)
        access(body, draft.privateShare, emptyList(), clearPublic = false)
        return createdId(context, "/api/polls", body)
    }

    suspend fun createBoardList(context: Context, draft: ListDraft): String {
        if (draft.title.trim().isEmpty()) throw CollabException("Add a title.")
        val body = JSONObject()
            .put("title", draft.title.trim().take(300))
            .put("kind", if (draft.style == "bullet") "bullet" else "todo")
            .put("items", JSONArray())
        if (draft.notes.isNotBlank()) body.put("notes", draft.notes.trim().take(4000))
        if (draft.groupId.isNotBlank()) body.put("group_id", draft.groupId)
        access(body, draft.privateShare, emptyList(), clearPublic = false)
        return createdId(context, "/api/lists", body)
    }

    private suspend fun createdId(context: Context, path: String, body: JSONObject): String {
        val created = AirdHttp.send(context, path, "POST", body)
        if (!created.ok) throw CollabException(nice(created))
        val id = created.json().optString("id")
        if (id.isBlank()) throw CollabException("Could not create")
        return id
    }

    suspend fun createPoll(context: Context, draft: PollDraft): String {
        val options = draft.options.map { it.trim() }.filter { it.isNotEmpty() }.take(12)
        if (draft.question.trim().isEmpty()) throw CollabException("Add a question.")
        if (options.size < 2) throw CollabException("Add at least two options.")
        val body = JSONObject()
            .put("question", draft.question.trim().take(400))
            .put("options", JSONArray(options))
            .put("allow_multi", draft.allowMulti)
        access(body, draft.privateShare, emptyList(), clearPublic = false)
        return create(context, "poll", "/api/polls", body)
    }

    suspend fun createList(context: Context, draft: ListDraft): String {
        if (draft.title.trim().isEmpty()) throw CollabException("Add a title.")
        val body = JSONObject()
            .put("title", draft.title.trim().take(300))
            .put("kind", if (draft.style == "bullet") "bullet" else "todo")
        val items = JSONArray()
        draft.items.map { it.trim() }.filter { it.isNotEmpty() }.take(40).forEach { line ->
            items.put(JSONObject().put("text", line.take(300)).put("labels", JSONArray()))
        }
        body.put("items", items)
        if (draft.notes.isNotBlank()) body.put("notes", draft.notes.trim().take(4000))
        if (draft.groupId.isNotBlank()) body.put("group_id", draft.groupId)
        access(body, draft.privateShare, emptyList(), clearPublic = false)
        return create(context, "list", "/api/lists", body)
    }

    suspend fun createMeet(context: Context, draft: MeetDraft): String {
        if (draft.title.trim().isEmpty()) throw CollabException("Add a title.")
        val body = JSONObject().put("title", draft.title.trim().take(300))
        access(body, draft.privateShare, emptyList(), clearPublic = false)
        return create(context, "meet", "/api/meet", body)
    }

    suspend fun updatePoll(context: Context, poll: PollArtifact, draft: PollDraft) {
        val options = draft.options.map { it.trim() }.filter { it.isNotEmpty() }.take(12)
        if (draft.question.trim().isEmpty()) throw CollabException("Add a question.")
        if (options.size < 2) throw CollabException("Add at least two options.")
        val body = JSONObject()
            .put("question", draft.question.trim().take(400))
            .put("options", JSONArray(options))
            .put("allow_multi", draft.allowMulti)
        access(body, draft.privateShare, poll.participants, clearPublic = true)
        act(poll) { sendDetail(context, "PATCH", "/api/polls/${poll.id}", body, ::parsePoll) }
        reloadShelf(context)
    }

    suspend fun updateList(context: Context, list: ListArtifact, draft: ListDraft) {
        if (draft.title.trim().isEmpty()) throw CollabException("Add a title.")
        val body = JSONObject()
            .put("title", draft.title.trim().take(300))
            .put("kind", if (draft.style == "bullet") "bullet" else "todo")
            .put("notes", draft.notes.trim().take(4000))
        access(body, draft.privateShare, list.participants, clearPublic = true)
        act(list) { sendDetail(context, "PATCH", "/api/lists/${list.id}", body, ::parseList) }
        reloadShelf(context)
    }

    suspend fun updateMeet(context: Context, meet: MeetArtifact, draft: MeetDraft) {
        if (draft.title.trim().isEmpty()) throw CollabException("Add a title.")
        val body = JSONObject().put("title", draft.title.trim().take(300))
        access(body, draft.privateShare, meet.participants, clearPublic = true)
        act(meet) { sendDetail(context, "PATCH", "/api/meet/${meet.id}", body, ::parseMeet) }
        reloadShelf(context)
    }

    suspend fun vote(context: Context, poll: PollArtifact, optionIds: List<String>) {
        if (optionIds.isEmpty()) throw CollabException("Pick at least one option.")
        val body = JSONObject().put("option_ids", JSONArray(optionIds))
        act(poll) { sendDetail(context, "POST", "/api/polls/${poll.id}/vote", body, ::parsePoll) }
    }

    suspend fun setDone(context: Context, list: ListArtifact, item: ListEntry, done: Boolean) {
        val body = JSONObject().put("done", done)
        act(list) { sendDetail(context, "PATCH", "/api/lists/${list.id}/items/${item.id}", body, ::parseList) }
    }

    suspend fun addItem(
        context: Context,
        list: ListArtifact,
        text: String,
        labels: List<String> = emptyList(),
        notes: String = "",
        dueAt: String = "",
        kind: String = "check",
    ) {
        val clean = text.trim()
        if (clean.isEmpty()) throw CollabException("Add an item.")
        val rowKind = when (kind) {
            "bullet" -> "bullet"
            "paragraph", "note", "text" -> "paragraph"
            else -> "checkbox"
        }
        val body = JSONObject()
            .put("kind", rowKind)
            .put("type", rowKind)
            .put("text", clean.take(8000))
            .put("content", clean.take(8000))
            .put("notes", notes.trim().take(4000))
            .put("labels", JSONArray(labels.map { it.trim() }.filter { it.isNotEmpty() }.take(8)))
        if (dueAt.isNotBlank() && rowKind == "checkbox") {
            body.put("due_at", dueAt)
            body.put("remind_at", dueAt)
        }
        act(list) { sendDetail(context, "POST", "/api/lists/${list.id}/items", body, ::parseList) }
    }

    suspend fun updateItem(
        context: Context,
        list: ListArtifact,
        item: ListEntry,
        text: String,
        labels: List<String>,
        notes: String = item.notes,
        dueAt: String = item.dueAt,
        kind: String = item.kind,
    ) {
        val clean = text.trim()
        if (clean.isEmpty()) throw CollabException("Add an item.")
        val rowKind = when (kind) {
            "bullet" -> "bullet"
            "paragraph", "note", "text" -> "paragraph"
            else -> "checkbox"
        }
        val body = JSONObject()
            .put("kind", rowKind)
            .put("type", rowKind)
            .put("text", clean.take(8000))
            .put("content", clean.take(8000))
            .put("notes", notes.trim().take(4000))
            .put("due_at", dueAt)
            .put("remind_at", dueAt)
            .put("labels", JSONArray(labels.map { it.trim() }.filter { it.isNotEmpty() }.take(8)))
        act(list) { sendDetail(context, "PATCH", "/api/lists/${list.id}/items/${item.id}", body, ::parseList) }
    }

    suspend fun syncList(context: Context, list: ListArtifact, ops: org.json.JSONArray, sinceHlc: String = "", actorId: String) {
        val body = JSONObject()
            .put("actor_id", actorId)
            .put("since_hlc", sinceHlc)
            .put("ops", ops)
        act(list) { sendDetail(context, "POST", "/api/lists/${list.id}/sync", body, ::parseList) }
    }

    suspend fun deleteItem(context: Context, list: ListArtifact, itemId: String) {
        act(list) { sendDetail(context, "DELETE", "/api/lists/${list.id}/items/$itemId", JSONObject(), ::parseList) }
    }

    suspend fun endMeet(context: Context, meet: MeetArtifact) {
        act(meet) {
            val response = AirdHttp.send(context, "/api/meet/${meet.id}", "DELETE", JSONObject())
            if (!response.ok) throw CollabException(nice(response))
            val detail = AirdHttp.get(context, "/api/meet/${meet.id}")
            if (!detail.ok) throw CollabException(nice(detail))
            parseMeet(detail.json())
        }
        reloadShelf(context)
    }

    private suspend fun reloadShelf(context: Context) {
        val conv = ChatRepository.activeId.value
        if (conv.isNotBlank()) loadShelf(context, conv)
    }

    private suspend fun create(context: Context, kind: String, path: String, body: JSONObject): String {
        val conv = ChatRepository.activeId.value
        if (conv.isBlank()) throw CollabException("Open a chat first.")
        val created = AirdHttp.send(context, path, "POST", body)
        if (!created.ok) throw CollabException(nice(created))
        val id = created.json().optString("id")
        val slug = created.json().optString("slug")
        if (id.isBlank()) throw CollabException("Could not create")
        val linked = try {
            AirdHttp.send(
                context,
                "/api/chat/conversations/$conv/refs",
                "POST",
                JSONObject().put("kind", kind).put("id", id),
            )
        } catch (err: Exception) {
            if (err is CancellationException) throw err
            null
        }
        val token = linked?.json()?.optJSONObject("ref")?.optString("token").orEmpty()
            .ifBlank { collabToken(kind, slug) }
        if (token.isBlank()) throw CollabException("Created, but it has no short name yet")
        load(context, kind, id, slug)
        if (ChatRepository.activeId.value == conv) loadShelf(context, conv)
        return token
    }

    private suspend fun load(context: Context, kind: String, id: String, slug: String) {
        val key = slotKey(kind, id, slug)
        val existing = _slots.value[key]
        editSlots { map ->
            map[key] = (map[key] ?: CollabSlot()).copy(loading = existing?.artifact == null, error = "")
        }
        try {
            val resolved = if (id.isNotBlank()) id else resolveSlug(context, kind, slug)
            if (resolved.isNullOrBlank()) {
                fail(key, "This ${kindLabel(kind)} is not available.", existing?.artifact)
                return
            }
            val response = AirdHttp.get(context, detailPath(kind, resolved))
            if (!response.ok) {
                fail(key, nice(response), existing?.artifact)
                return
            }
            publish(parse(kind, response.json()))
        } catch (err: Exception) {
            if (err is CancellationException) {
                editSlots { map ->
                    val slot = map[key] ?: return@editSlots
                    if (slot.artifact == null) map.remove(key) else map[key] = slot.copy(loading = false)
                }
                throw err
            }
            fail(key, "Could not load", existing?.artifact)
        }
    }

    private suspend fun resolveSlug(context: Context, kind: String, slug: String): String? {
        if (slug.isBlank()) return null
        val query = URLEncoder.encode(slug, "UTF-8")
        val response = AirdHttp.get(context, "/api/chat/collab-suggest?kind=$kind&q=$query")
        if (!response.ok) return null
        val items = response.json().optJSONArray("items") ?: return null
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val id = item.optString("id")
            if (id.isBlank()) continue
            if (item.optString("slug").equals(slug, true)) return id
        }
        return null
    }

    private suspend fun act(current: CollabArtifact, block: suspend () -> CollabArtifact) {
        markBusy(current, true)
        try {
            publish(block())
        } catch (err: CancellationException) {
            markBusy(current, false)
            throw err
        } catch (err: Exception) {
            val message = if (err is CollabException) err.message ?: "Could not save" else "Could not reach the server"
            fail(slotKey(current.type, current.id, current.slug), message, current)
            throw CollabException(message)
        }
    }

    private suspend fun sendDetail(
        context: Context,
        method: String,
        path: String,
        body: JSONObject,
        parse: (JSONObject) -> CollabArtifact,
    ): CollabArtifact {
        val response = AirdHttp.send(context, path, method, body)
        if (!response.ok) throw CollabException(nice(response))
        return parse(response.json())
    }

    private fun access(body: JSONObject, privateShare: Boolean, existing: List<String>, clearPublic: Boolean) {
        body.put("visibility", if (privateShare) "private" else "public")
        if (privateShare) body.put("participants", audience(existing))
        else if (clearPublic) body.put("participants", JSONArray())
    }

    private fun audience(existing: List<String>): JSONArray {
        val me = Session.username.value
        val conv = ChatRepository.conversations.value.firstOrNull { it.id == ChatRepository.activeId.value }
        val names = LinkedHashSet<String>()
        existing.forEach { if (it.isNotBlank()) names.add(it.trim()) }
        conv?.members?.forEach { if (it.username.isNotBlank()) names.add(it.username.trim()) }
        if (!conv?.peer.isNullOrBlank()) names.add(conv.peer.trim())
        names.removeAll { it.equals(me, true) }
        val array = JSONArray()
        names.take(100).forEach { array.put(it) }
        return array
    }

    private fun publish(artifact: CollabArtifact) {
        val slot = CollabSlot(artifact = artifact, at = System.currentTimeMillis())
        editSlots { map ->
            map[slotKey(artifact.type, artifact.id, "")] = slot
            if (artifact.slug.isNotBlank()) map[slotKey(artifact.type, "", artifact.slug)] = slot
        }
    }

    private fun markBusy(artifact: CollabArtifact, busy: Boolean) {
        editSlots { map ->
            keys(artifact.type, artifact.id, artifact.slug).forEach { key ->
                val slot = map[key] ?: return@forEach
                map[key] = slot.copy(busy = busy, error = if (busy) "" else slot.error)
            }
        }
    }

    private fun fail(key: String, message: String, keep: CollabArtifact?) {
        editSlots { map ->
            val current = map[key]
            val artifact = keep ?: current?.artifact
            val slot = CollabSlot(artifact = artifact, error = message, at = current?.at ?: 0L)
            map[key] = slot
            if (artifact != null) {
                map[slotKey(artifact.type, artifact.id, "")] = slot
                if (artifact.slug.isNotBlank()) map[slotKey(artifact.type, "", artifact.slug)] = slot
            }
        }
    }

    private fun editSlots(block: (MutableMap<String, CollabSlot>) -> Unit) {
        synchronized(slotLock) {
            val map = _slots.value.toMutableMap()
            block(map)
            _slots.value = map
        }
    }

    private fun keys(kind: String, id: String, slug: String) = buildList {
        if (id.isNotBlank()) add(slotKey(kind, id, ""))
        if (slug.isNotBlank()) add(slotKey(kind, "", slug))
    }

    internal fun slotKey(kind: String, id: String, slug: String): String =
        if (id.isNotBlank()) "$kind:$id" else "$kind:@${slug.lowercase()}"

    private fun detailPath(kind: String, id: String): String = when (kind) {
        "list" -> "/api/lists/$id"
        "meet" -> "/api/meet/$id"
        else -> "/api/polls/$id"
    }

    private fun parse(kind: String, json: JSONObject): CollabArtifact = when (kind) {
        "list" -> parseList(json)
        "meet" -> parseMeet(json)
        else -> parsePoll(json)
    }

    private fun parsePoll(json: JSONObject): PollArtifact {
        val options = json.optJSONArray("options") ?: JSONArray()
        val parsed = buildList {
            for (i in 0 until options.length()) {
                val item = options.optJSONObject(i) ?: continue
                val id = item.optString("id")
                if (id.isBlank()) continue
                add(PollOption(id, item.optString("label"), item.optInt("votes"), item.bool("mine")))
            }
        }
        return PollArtifact(
            id = json.optString("id").ifBlank { throw CollabException("Could not read that poll") },
            slug = json.optString("slug"),
            organizer = json.optString("organizer"),
            visibility = json.optString("visibility").ifBlank { "public" },
            participants = strings(json.optJSONArray("participants")),
            question = json.optString("question"),
            allowMulti = json.bool("allow_multi"),
            totalVotes = json.optInt("total_votes").takeIf { json.has("total_votes") } ?: parsed.sumOf { it.votes },
            options = parsed,
            active = json.flag("active") ?: stampStillOpen(json.optString("closes_at")),
        )
    }

    private fun parseList(json: JSONObject): ListArtifact {
        val itemsJson = json.optJSONArray("items") ?: JSONArray()
        val items = buildList {
            for (i in 0 until itemsJson.length()) {
                val item = itemsJson.optJSONObject(i) ?: continue
                val id = item.optString("id")
                if (id.isBlank()) continue
                val rawKind = item.optString("type").ifBlank { item.optString("kind") }.lowercase()
                val rowKind = when (rawKind) {
                    "bullet" -> "bullet"
                    "paragraph", "note", "text" -> "paragraph"
                    else -> "checkbox"
                }
                add(
                    ListEntry(
                        id,
                        item.optString("content").ifBlank { item.optString("text") },
                        item.bool("done") || item.bool("isChecked"),
                        strings(item.optJSONArray("tags") ?: item.optJSONArray("labels")),
                        item.optCleanString("notes"),
                        item.optCleanString("due_at"),
                        item.optCleanString("remind_at"),
                        rowKind,
                        item.optInt("depth"),
                        item.optString("order").ifBlank { item.optString("order_key") },
                    ),
                )
            }
        }
        val progress = json.optJSONObject("progress")
        val done = progress?.optInt("done") ?: items.count { it.done }
        val total = progress?.optInt("total") ?: items.size
        val style = json.optString("kind").ifBlank { "todo" }
        val finished = style != "bullet" && total > 0 && done >= total
        return ListArtifact(
            id = json.optString("id").ifBlank { throw CollabException("Could not read that list") },
            slug = json.optString("slug"),
            organizer = json.optString("organizer"),
            visibility = json.optString("visibility").ifBlank { "public" },
            participants = strings(json.optJSONArray("participants")),
            title = json.optString("title"),
            style = style,
            notes = json.optString("notes"),
            groupId = json.optString("group_id"),
            items = items,
            done = done,
            total = total,
            tags = collabTags(json.optJSONArray("tags")),
            active = json.flag("active") ?: !finished,
        )
    }

    private fun parseMeet(json: JSONObject): MeetArtifact {
        val slug = json.optString("slug")
        val live = json.bool("live")
        return MeetArtifact(
            id = json.optString("id").ifBlank { throw CollabException("Could not read that meeting") },
            slug = slug,
            organizer = json.optString("organizer"),
            visibility = json.optString("visibility").ifBlank { "public" },
            participants = strings(json.optJSONArray("participants")),
            title = json.optString("title"),
            live = live,
            expiresAt = json.optString("expires_at"),
            joinPath = json.optString("join_path").ifBlank { if (slug.isBlank()) "" else "/meet/$slug" },
            active = json.flag("active") ?: live,
        )
    }

    private fun collabTags(array: JSONArray?): List<CollabTag> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i)
                if (obj != null) {
                    val name = obj.optString("name").ifBlank { obj.optString("display_name") }
                    if (name.isBlank()) continue
                    add(CollabTag(name, obj.optString("color"), obj.optString("display_name").ifBlank { name }))
                } else {
                    val name = array.optString(i)
                    if (name.isNotBlank()) add(CollabTag(name, "", name))
                }
            }
        }
    }

    private fun strings(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val value = array.optString(i).ifBlank { array.optJSONObject(i)?.optString("username").orEmpty() }
                if (value.isNotBlank()) add(value)
            }
        }
    }

    private fun JSONObject.flag(name: String): Boolean? =
        if (has(name) && !isNull(name)) bool(name) else null

    private fun JSONObject.bool(name: String): Boolean = when (val value = opt(name)) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> value.equals("true", true) || value == "1"
        else -> false
    }

    private fun nice(response: AirdResponse): String {
        val raw = response.json().optString("error").trim()
        if (raw.equals("Forbidden", true)) return "You do not have access"
        if (raw.isNotBlank() && raw.length < 160 && '<' !in raw) return raw
        return when (response.code) {
            404 -> "That feature is turned off"
            403 -> "You do not have access"
            else -> "Could not save"
        }
    }

    private fun kindLabel(kind: String) = when (kind) {
        "poll" -> "poll"
        "meet" -> "meeting"
        else -> "list"
    }
}

private fun JSONObject.optCleanString(key: String): String {
    if (isNull(key)) return ""
    val value = optString(key).trim()
    return if (value.equals("null", ignoreCase = true)) "" else value
}
