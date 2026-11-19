package com.aird.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.aird.InternalLaunch
import com.aird.MainActivity

class UnreadChatsWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val rows = WidgetStore.readUnread(context)
        provideContent {
            GlanceTheme {
                UnreadContent(context, rows)
            }
        }
    }
}

@Composable
private fun UnreadContent(context: Context, rows: List<UnreadChatRow>) {
    Column(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(GlanceTheme.colors.widgetBackground)
            .padding(12.dp),
    ) {
        Text(
            "Unread chats",
            style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 14.sp, color = GlanceTheme.colors.onSurface),
        )
        Spacer(GlanceModifier.height(8.dp))
        if (rows.isEmpty()) {
            Text(
                "All caught up",
                style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onSurfaceVariant),
            )
        } else {
            rows.forEach { row ->
                val open = InternalLaunch.stamp(
                    context,
                    Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        putExtra("action", "open_chat")
                        putExtra("conversation_id", row.id)
                    },
                )
                Column(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .clickable(actionStartActivity(open))
                        .padding(vertical = 6.dp),
                ) {
                    Text(
                        "${row.title} · ${row.unread}",
                        style = TextStyle(fontWeight = FontWeight.Medium, fontSize = 13.sp, color = GlanceTheme.colors.onSurface),
                        maxLines = 1,
                    )
                    Text(
                        row.preview.ifBlank { "New messages" },
                        style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

class UnreadChatsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = UnreadChatsWidget()
}
