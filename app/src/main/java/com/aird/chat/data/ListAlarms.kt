package com.aird.chat.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.aird.ListReminderReceiver
import com.aird.chat.model.ListReminder
import java.time.Instant

object ListAlarms {
    private const val PREFS = "aird_list_alarms"
    private const val KEY = "ids"

    fun sync(context: Context, reminders: List<ListReminder>) {
        val app = context.applicationContext
        val alarms = app.getSystemService(AlarmManager::class.java) ?: return
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = prefs.getStringSet(KEY, emptySet()).orEmpty()
        previous.forEach { id -> alarms.cancel(pending(app, id, "", "")) }
        val next = reminders.take(30)
        val kept = mutableSetOf<String>()
        val now = System.currentTimeMillis()
        next.forEach { reminder ->
            try {
                val whenMs = Instant.parse(reminder.remindAt).toEpochMilli()
                if (whenMs <= now) return@forEach
                val id = reminder.itemId
                alarms.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    whenMs,
                    pending(app, id, reminder.title, reminder.text),
                )
                kept.add(id)
            } catch (_: Exception) {
            }
        }
        prefs.edit().putStringSet(KEY, kept).apply()
    }

    private fun pending(context: Context, itemId: String, title: String, text: String): PendingIntent {
        val intent = Intent(context, ListReminderReceiver::class.java)
            .putExtra("item_id", itemId)
            .putExtra("title", title)
            .putExtra("text", text)
        return PendingIntent.getBroadcast(
            context,
            itemId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
