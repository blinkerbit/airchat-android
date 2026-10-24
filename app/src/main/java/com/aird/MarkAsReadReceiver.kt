package com.aird

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aird.session.AirdSocket
import org.json.JSONObject

class MarkAsReadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val conversationId = intent.getStringExtra(KEY_CONVERSATION_ID) ?: return
        context.getSystemService(NotificationManager::class.java).cancel(conversationId.hashCode())
        val messageId = intent.getStringExtra(KEY_MESSAGE_ID).orEmpty()
        if (messageId.isEmpty()) return
        AirdSocket.send(
            JSONObject()
                .put("type", "chat_read")
                .put("conversation_id", conversationId)
                .put("message_id", messageId)
                .put("decrypted", true)
                .toString(),
        )
    }

    companion object {
        const val KEY_CONVERSATION_ID = "key_conversation_id"
        const val KEY_MESSAGE_ID = "message_id"
    }
}
