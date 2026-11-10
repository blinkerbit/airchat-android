package com.aird

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import com.aird.chat.ui.ChatBubbleHost
import com.aird.theme.AirdPalettes
import com.aird.theme.ThemeStore
import com.aird.ui.theme.AirdTheme

class ChatBubbleActivity : ComponentActivity() {
    private val conversationId = mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        conversationId.value = intent.getStringExtra("conversation_id").orEmpty()
        setContent { BubbleRoot(conversationId.value) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        conversationId.value = intent.getStringExtra("conversation_id").orEmpty()
    }
}

@Composable
private fun BubbleRoot(conversationId: String) {
    val themeId by ThemeStore.id.collectAsState()
    AirdTheme(AirdPalettes.byId(themeId)) {
        ChatBubbleHost(conversationId)
    }
}
