package com.aird

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aird.chat.data.ChatNotifier
import com.aird.chat.data.ChatRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MarkAsReadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val conversationId = intent.getStringExtra(KEY_CONVERSATION_ID) ?: return
        ChatNotifier.cancel(context, conversationId)
        val messageId = intent.getStringExtra(KEY_MESSAGE_ID).orEmpty()
        if (messageId.isEmpty()) return
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ChatRepository.sendRead(app, conversationId, messageId, decrypted = true)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val KEY_CONVERSATION_ID = "key_conversation_id"
        const val KEY_MESSAGE_ID = "message_id"
    }
}
