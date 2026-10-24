package com.aird.chat.data

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.aird.AirdConnectionService
import com.aird.MainActivity
import com.aird.MarkAsReadReceiver
import com.aird.NotificationReplyReceiver

object ChatNotifier {
    private data class Note(val sender: String, val text: String, val at: Long, val mine: Boolean)

    private val history = mutableMapOf<String, MutableList<Note>>()

    fun show(
        context: Context,
        conversationId: String,
        title: String,
        sender: String,
        preview: String,
        mine: Boolean = false,
        messageId: String = "",
    ) {
        val text = when {
            preview.isBlank() || preview == "Encrypted message" -> "New message"
            else -> preview
        }
        val notes = synchronized(history) {
            val list = history.getOrPut(conversationId) { mutableListOf() }
            list.add(Note(sender.ifEmpty { title }, text, System.currentTimeMillis(), mine))
            if (list.size > 8) list.removeAt(0)
            list.toList()
        }
        val app = context.applicationContext
        val label = title.ifEmpty { sender.ifEmpty { "Aird Chat" } }
        val icon = avatar(label)
        val conversation = Person.Builder().setName(label).setKey(conversationId).setIcon(icon).setImportant(true).build()
        val me = Person.Builder().setName("Me").setImportant(true).build()
        val style = NotificationCompat.MessagingStyle(me).setConversationTitle(label)
        notes.forEach { note ->
            val who = if (note.mine) null else Person.Builder().setName(note.sender).setIcon(icon).build()
            style.addMessage(note.text, note.at, who)
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
        val reply = RemoteInput.Builder(NotificationReplyReceiver.KEY_TEXT_REPLY).setLabel("Reply").build()
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
            .setAllowGeneratedReplies(false)
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
        val builder = NotificationCompat.Builder(app, AirdConnectionService.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setStyle(style)
            .setContentIntent(content)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVibrate(longArrayOf(0, 250, 250, 250))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .addAction(replyAction)
            .addAction(android.R.drawable.ic_menu_view, "Mark read", readPending)
            .addPerson(conversation)
        publishShortcut(app, conversationId, label, icon, open, content, builder)
        app.getSystemService(NotificationManager::class.java).notify(conversationId.hashCode(), builder.build())
    }

    fun cancel(context: Context, conversationId: String) {
        if (conversationId.isEmpty()) return
        context.getSystemService(NotificationManager::class.java).cancel(conversationId.hashCode())
    }

    private fun publishShortcut(
        context: Context,
        conversationId: String,
        label: String,
        icon: IconCompat,
        open: Intent,
        bubbleIntent: PendingIntent,
        builder: NotificationCompat.Builder,
    ) {
        try {
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
                    .setAutoExpandBubble(false)
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
        paint.color = Color.parseColor("#1F6F5B")
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 64f
        val letter = name.ifEmpty { "?" }.take(1).uppercase()
        canvas.drawText(letter, size / 2f, size / 2f + 22f, paint)
        return IconCompat.createWithBitmap(bitmap)
    }
}
