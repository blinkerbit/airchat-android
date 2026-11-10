package com.aird.chat.data

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.aird.AirdConnectionService
import com.aird.ChatBubbleActivity
import com.aird.MainActivity
import com.aird.chat.model.Conversation
import com.aird.MarkAsReadReceiver
import com.aird.NotificationReplyReceiver
import com.aird.R
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

object ChatNotifier {
    private data class Note(
        val messageId: String,
        val sender: String,
        val text: String,
        val at: Long,
        val mine: Boolean,
        val mediaUri: String = "",
        val mediaMime: String = "",
    )

    private data class Meta(
        val title: String,
        val sender: String,
        val group: Boolean,
        val messageId: String,
    )

    private val memory = mutableMapOf<String, MutableList<Note>>()
    private val pendingMedia = ConcurrentHashMap<String, Pair<String, String>>()
    private val gate = Any()

    fun show(
        context: Context,
        conversationId: String,
        title: String,
        sender: String,
        preview: String,
        mine: Boolean = false,
        messageId: String = "",
        alert: Boolean = !mine,
        group: Boolean = false,
    ) {
        val text = when {
            preview.isBlank() || preview == "Encrypted message" -> "New message"
            else -> preview
        }
        val media = if (messageId.isEmpty()) null else pendingMedia.remove(messageId)
        val note = Note(
            messageId = messageId,
            sender = sender.ifEmpty { title },
            text = text,
            at = System.currentTimeMillis(),
            mine = mine,
            mediaUri = media?.first.orEmpty(),
            mediaMime = media?.second.orEmpty(),
        )
        val notes = synchronized(gate) {
            val list = notes(context, conversationId)
            val last = list.lastOrNull()
            if (messageId.isNotEmpty() && last?.messageId == messageId) list[list.lastIndex] = note
            else list.add(note)
            while (list.size > 8) list.removeAt(0)
            saveNotes(context, conversationId, list)
            saveMeta(context, conversationId, Meta(title, sender, group, messageId))
            list.toList()
        }
        publish(context, conversationId, title, sender, group, messageId, alert, notes, expand = false)
    }

    fun showBubble(context: Context, conversation: Conversation) {
        val app = context.applicationContext
        if (conversation.id.isEmpty()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Toast.makeText(app, "Chat bubbles need Android 11", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val allowed = app.getSystemService(NotificationManager::class.java)?.areBubblesAllowed() == true
            if (!allowed) {
                val settings = Intent(Settings.ACTION_APP_NOTIFICATION_BUBBLE_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(settings)
                } catch (_: Exception) {
                    Toast.makeText(app, "Allow chat bubbles for Aird in notification settings", Toast.LENGTH_LONG).show()
                }
                return
            }
        }
        val notes = synchronized(gate) {
            notes(app, conversation.id).toList().ifEmpty {
                listOf(
                    Note(
                        messageId = "",
                        sender = conversation.name(),
                        text = conversation.preview.ifBlank { "Chat" },
                        at = System.currentTimeMillis(),
                        mine = false,
                    ),
                )
            }
        }
        publish(
            app,
            conversation.id,
            conversation.name(),
            conversation.peer.ifEmpty { conversation.name() },
            conversation.kind == "group",
            notes.last().messageId,
            alert = false,
            notes = notes,
            expand = true,
        )
    }

    fun attachMedia(context: Context, conversationId: String, messageId: String, uri: Uri, mime: String) {
        if (conversationId.isEmpty() || messageId.isEmpty() || mime.isBlank()) return
        val stored = uri.toString()
        val notes = synchronized(gate) {
            val list = notes(context, conversationId)
            val index = list.indexOfLast { it.messageId == messageId }
            if (index < 0) {
                pendingMedia[messageId] = stored to mime
                return
            }
            list[index] = list[index].copy(mediaUri = stored, mediaMime = mime)
            saveNotes(context, conversationId, list)
            list.toList()
        }
        val meta = synchronized(gate) { loadMeta(context, conversationId) } ?: return
        publish(context, conversationId, meta.title, meta.sender, meta.group, meta.messageId, false, notes, expand = false)
    }

    fun cancel(context: Context, conversationId: String) {
        if (conversationId.isEmpty()) return
        val app = context.applicationContext
        synchronized(gate) {
            memory.remove(conversationId)
            notesPrefs(app).edit().remove(conversationId).remove(metaKey(conversationId)).apply()
        }
        app.getSystemService(NotificationManager::class.java).cancel(conversationId.hashCode())
        try {
            ShortcutManagerCompat.removeDynamicShortcuts(app, listOf(conversationId))
        } catch (_: Exception) {
        }
    }

    private const val LEDGER = "aird_notify_ledger"
    private const val NOTES = "aird_chat_notes"

    private fun ledger(context: Context) =
        context.applicationContext.getSharedPreferences(LEDGER, Context.MODE_PRIVATE)

    private fun notesPrefs(context: Context) =
        context.applicationContext.getSharedPreferences(NOTES, Context.MODE_PRIVATE)

    private fun metaKey(conversationId: String) = "$conversationId|meta"

    /** True once we have recorded where every chat stood at least once. */
    fun hasLedger(context: Context): Boolean = ledger(context).all.isNotEmpty()

    /** The `updated_at` of a chat the last time we alerted for it or looked at it. */
    fun seen(context: Context, conversationId: String): String? =
        ledger(context).getString(conversationId, null)

    fun remember(context: Context, stamps: List<Pair<String, String>>) {
        val prefs = ledger(context)
        val edit = prefs.edit()
        var changed = false
        stamps.forEach { (id, at) ->
            if (id.isNotEmpty() && prefs.getString(id, null) != at) {
                edit.putString(id, at)
                changed = true
            }
        }
        if (changed) edit.apply()
    }

    fun forget(context: Context) {
        val app = context.applicationContext
        ledger(app).edit().clear().apply()
        val prefs = notesPrefs(app)
        val manager = app.getSystemService(NotificationManager::class.java)
        prefs.all.keys.filter { !it.endsWith("|meta") }.forEach { manager.cancel(it.hashCode()) }
        prefs.edit().clear().apply()
        synchronized(gate) { memory.clear() }
        pendingMedia.clear()
        try {
            ShortcutManagerCompat.removeAllDynamicShortcuts(app)
        } catch (_: Exception) {
        }
    }

    private fun notes(context: Context, conversationId: String): MutableList<Note> =
        memory.getOrPut(conversationId) { readNotes(context, conversationId) }

    private fun readNotes(context: Context, conversationId: String): MutableList<Note> {
        val raw = notesPrefs(context).getString(conversationId, null) ?: return mutableListOf()
        return try {
            val array = JSONArray(raw)
            MutableList(array.length()) { index ->
                val item = array.getJSONObject(index)
                Note(
                    messageId = item.optString("id"),
                    sender = item.optString("who"),
                    text = item.optString("text"),
                    at = item.optLong("at"),
                    mine = item.optBoolean("mine"),
                    mediaUri = item.optString("uri"),
                    mediaMime = item.optString("mime"),
                )
            }
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun saveNotes(context: Context, conversationId: String, notes: List<Note>) {
        val array = JSONArray()
        notes.forEach { note ->
            array.put(
                JSONObject()
                    .put("id", note.messageId)
                    .put("who", note.sender)
                    .put("text", note.text)
                    .put("at", note.at)
                    .put("mine", note.mine)
                    .put("uri", note.mediaUri)
                    .put("mime", note.mediaMime),
            )
        }
        notesPrefs(context).edit().putString(conversationId, array.toString()).apply()
    }

    private fun saveMeta(context: Context, conversationId: String, meta: Meta) {
        val json = JSONObject()
            .put("title", meta.title)
            .put("sender", meta.sender)
            .put("group", meta.group)
            .put("messageId", meta.messageId)
        notesPrefs(context).edit().putString(metaKey(conversationId), json.toString()).apply()
    }

    private fun loadMeta(context: Context, conversationId: String): Meta? {
        val raw = notesPrefs(context).getString(metaKey(conversationId), null) ?: return null
        return try {
            val json = JSONObject(raw)
            Meta(json.optString("title"), json.optString("sender"), json.optBoolean("group"), json.optString("messageId"))
        } catch (_: Exception) {
            null
        }
    }

    private fun publish(
        context: Context,
        conversationId: String,
        title: String,
        sender: String,
        group: Boolean,
        messageId: String,
        alert: Boolean,
        notes: List<Note>,
        expand: Boolean,
    ) {
        val app = context.applicationContext
        val label = title.ifEmpty { sender.ifEmpty { "Aird Chat" } }
        val icon = avatar(label)
        val conversation = Person.Builder().setName(label).setKey(conversationId).setIcon(icon).setImportant(true).build()
        val me = Person.Builder().setName("Me").setImportant(true).build()
        val style = NotificationCompat.MessagingStyle(me).setGroupConversation(group)
        if (group) style.setConversationTitle(label)
        notes.forEach { note ->
            val who = if (note.mine) null else Person.Builder().setName(note.sender).setIcon(icon).build()
            val message = NotificationCompat.MessagingStyle.Message(note.text, note.at, who)
            mediaUri(app, note)?.let { uri -> message.setData(note.mediaMime, uri) }
            style.addMessage(message)
        }
        val open = Intent(app, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("action", "open_chat")
            putExtra("conversation_id", conversationId)
        }
        val content = PendingIntent.getActivity(
            app,
            conversationId.hashCode(),
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag(),
        )
        val bubbleOpen = Intent(app, ChatBubbleActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            flags = Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            putExtra("conversation_id", conversationId)
            data = Uri.parse("aird://bubble/$conversationId")
        }
        val bubblePending = PendingIntent.getActivity(
            app,
            ("bubble:$conversationId").hashCode(),
            bubbleOpen,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val reply = RemoteInput.Builder(NotificationReplyReceiver.KEY_TEXT_REPLY)
            .setLabel("Reply")
            .build()
        val replyPending = PendingIntent.getBroadcast(
            app,
            ("reply:$conversationId").hashCode(),
            Intent(app, NotificationReplyReceiver::class.java).apply {
                putExtra(NotificationReplyReceiver.KEY_CONVERSATION_ID, conversationId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag(),
        )
        val replyAction = NotificationCompat.Action.Builder(android.R.drawable.ic_menu_send, "Reply", replyPending)
            .addRemoteInput(reply)
            .setAllowGeneratedReplies(true)
            .build()
        val readPending = PendingIntent.getBroadcast(
            app,
            ("read:$conversationId").hashCode(),
            Intent(app, MarkAsReadReceiver::class.java).apply {
                putExtra(MarkAsReadReceiver.KEY_CONVERSATION_ID, conversationId)
                putExtra(MarkAsReadReceiver.KEY_MESSAGE_ID, messageId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val sound = ConversationPrefs.isSoundEnabled(app, conversationId)
        val shouldAlert = alert && sound
        val hidden = NotificationCompat.Builder(app, AirdConnectionService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_chat)
            .setContentTitle("Aird Chat")
            .setContentText("New message")
            .build()
        val builder = NotificationCompat.Builder(app, AirdConnectionService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_chat)
            .setStyle(style)
            .setContentIntent(content)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(if (shouldAlert) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(hidden)
            .setSilent(!shouldAlert)
            .setOnlyAlertOnce(!shouldAlert)
            .setAutoCancel(true)
            .addAction(replyAction)
            .addAction(android.R.drawable.ic_menu_view, "Mark read", readPending)
            .addPerson(conversation)
        publishShortcut(app, conversationId, label, icon, open, bubblePending, builder, expand)
        app.getSystemService(NotificationManager::class.java).notify(conversationId.hashCode(), builder.build())
    }

    private fun mediaUri(context: Context, note: Note): Uri? {
        if (note.mediaUri.isBlank() || note.mediaMime.isBlank()) return null
        val uri = try {
            Uri.parse(note.mediaUri)
        } catch (_: Exception) {
            return null
        }
        if (uri.scheme != "content") return null
        val readable = try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        } catch (_: Exception) {
            false
        }
        if (!readable) return null
        try {
            context.grantUriPermission("com.android.systemui", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
        return uri
    }

    private fun publishShortcut(
        context: Context,
        conversationId: String,
        label: String,
        icon: IconCompat,
        open: Intent,
        bubbleIntent: PendingIntent,
        builder: NotificationCompat.Builder,
        autoExpand: Boolean,
    ) {
        try {
            val existing = ShortcutManagerCompat.getDynamicShortcuts(context)
            if (existing.none { it.id == conversationId } && existing.size >= 4) {
                ShortcutManagerCompat.removeDynamicShortcuts(context, listOf(existing.first().id))
            }
            val shortcut = ShortcutInfoCompat.Builder(context, conversationId)
                .setShortLabel(label.take(30))
                .setLongLived(true)
                .setIcon(icon)
                .setIntent(open)
                .setPerson(Person.Builder().setName(label).setKey(conversationId).setIcon(icon).setImportant(true).build())
                .setCategories(setOf("android.shortcut.conversation"))
                .build()
            ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
            builder.setShortcutId(conversationId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bubble = NotificationCompat.BubbleMetadata.Builder(bubbleIntent, icon)
                    .setDesiredHeight(600)
                    .setAutoExpandBubble(autoExpand)
                    .setSuppressNotification(false)
                    .build()
                builder.setBubbleMetadata(bubble)
            }
        } catch (_: Exception) {
        }
    }

    private fun mutableFlag(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
    }

    private fun avatar(name: String): IconCompat {
        val size = 128
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val palette = listOf(0xFF1E88E5, 0xFF43A047, 0xFFE53935, 0xFF8E24AA, 0xFFFB8C00, 0xFF00ACC1, 0xFFD81B60, 0xFF3949AB)
        paint.color = palette[kotlin.math.abs(name.hashCode()) % palette.size].toInt()
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 64f
        val letter = name.ifEmpty { "?" }.take(1).uppercase()
        canvas.drawText(letter, size / 2f, size / 2f + 22f, paint)
        return IconCompat.createWithBitmap(bitmap)
    }
}
