package com.aird

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.aird.AirdConnectionService

class ListReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra("title").orEmpty().ifBlank { "List" }
        val text = intent.getStringExtra("text").orEmpty().ifBlank { "An item is due" }
        val open = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("action", "open_lists")
        }
        val content = PendingIntent.getActivity(
            context,
            text.hashCode(),
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val note = NotificationCompat.Builder(context, AirdConnectionService.CHANNEL_ID)
            .setSmallIcon(com.aird.R.drawable.ic_stat_call)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(content)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(("list:" + intent.getStringExtra("item_id").orEmpty()).hashCode(), note)
    }
}
