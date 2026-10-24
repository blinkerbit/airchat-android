package com.aird

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.aird.chat.data.ChatRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NotificationReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val conversationId = intent.getStringExtra(KEY_CONVERSATION_ID) ?: return
        val replyText = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT_REPLY)?.toString()?.trim().orEmpty()
        if (replyText.isEmpty()) return
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ChatRepository.sendFromNotification(app, conversationId, replyText)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val KEY_TEXT_REPLY = "key_text_reply"
        const val KEY_CONVERSATION_ID = "key_conversation_id"
    }
}
