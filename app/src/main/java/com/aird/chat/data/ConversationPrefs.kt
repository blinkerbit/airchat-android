package com.aird.chat.data

import android.content.Context

/** Per-conversation local preferences (sound, vibration, etc.) */
object ConversationPrefs {
    private const val PREFS = "aird_conv_prefs"

    fun isSoundEnabled(context: Context, conversationId: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("sound_$conversationId", true)

    fun setSoundEnabled(context: Context, conversationId: String, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean("sound_$conversationId", enabled).apply()
    }
}

