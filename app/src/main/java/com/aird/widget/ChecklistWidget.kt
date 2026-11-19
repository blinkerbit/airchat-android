package com.aird.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.CheckBox
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.aird.chat.data.CollabRepository
import com.aird.chat.model.ListArtifact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val ItemIdKey = ActionParameters.Key<String>("item_id")
private val DoneKey = ActionParameters.Key<Boolean>("done")

class ChecklistWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        WidgetUpdater.refreshChecklist(context)
        val title = WidgetStore.checklistTitle(context)
        val items = WidgetStore.readChecklistItems(context)
        provideContent {
            GlanceTheme {
                ChecklistContent(title, items)
            }
        }
    }
}

@Composable
private fun ChecklistContent(title: String, items: List<Triple<String, String, Boolean>>) {
    Column(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(GlanceTheme.colors.widgetBackground)
            .padding(12.dp),
    ) {
        Text(
            title.ifBlank { "Checklist" },
            style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 14.sp, color = GlanceTheme.colors.onSurface),
        )
        Spacer(GlanceModifier.height(8.dp))
        if (items.isEmpty()) {
            Text(
                "No list yet — open Aird and create one",
                style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onSurfaceVariant),
            )
        } else {
            items.forEach { (itemId, text, done) ->
                Row(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .clickable(
                            actionRunCallback<ToggleChecklistItemAction>(
                                actionParametersOf(ItemIdKey to itemId, DoneKey to !done),
                            ),
                        )
                        .padding(vertical = 4.dp),
                ) {
                    CheckBox(
                        checked = done,
                        onCheckedChange = actionRunCallback<ToggleChecklistItemAction>(
                            actionParametersOf(ItemIdKey to itemId, DoneKey to !done),
                        ),
                        text = text,
                    )
                }
            }
        }
    }
}

class ToggleChecklistItemAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val itemId = parameters[ItemIdKey].orEmpty()
        val done = parameters[DoneKey] ?: return
        if (itemId.isBlank()) return
        withContext(Dispatchers.IO) {
            val listId = WidgetStore.checklistListId(context)
            if (listId.isBlank()) return@withContext
            CollabRepository.ensure(context, "list", listId, "")
            val list = CollabRepository.slots.value.values
                .mapNotNull { it.artifact as? ListArtifact }
                .firstOrNull { it.id == listId }
                ?: return@withContext
            val item = list.items.firstOrNull { it.id == itemId } ?: return@withContext
            runCatching { CollabRepository.setDone(context, list, item, done) }
            WidgetUpdater.refreshChecklist(context)
        }
        ChecklistWidget().update(context, glanceId)
    }
}

class ChecklistWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ChecklistWidget()
}
