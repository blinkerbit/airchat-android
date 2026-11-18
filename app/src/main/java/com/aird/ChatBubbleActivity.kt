package com.aird

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.aird.chat.ui.ChatBubbleHost
import com.aird.designsystem.theme.AirdTheme

class ChatBubbleActivity : ComponentActivity() {
    private val conversationId = mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        conversationId.value = intent.getStringExtra("conversation_id").orEmpty()
        setContent { AirdTheme { ChatBubbleHost(conversationId.value) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        conversationId.value = intent.getStringExtra("conversation_id").orEmpty()
    }
}
