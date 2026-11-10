package com.aird

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aird.call.CallController

class IncomingCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_ACCEPT -> {
                CallController.restoreIncoming(
                    context,
                    intent.getStringExtra(KEY_CONVERSATION).orEmpty(),
                    intent.getStringExtra(KEY_CALL).orEmpty(),
                    intent.getStringExtra(KEY_MODE).orEmpty(),
                    intent.getStringExtra(KEY_FROM).orEmpty(),
                )
                CallController.accept(context)
                val open = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("action", "accept_call")
                    putExtra("conversation_id", intent.getStringExtra(KEY_CONVERSATION).orEmpty())
                    putExtra("call_id", intent.getStringExtra(KEY_CALL).orEmpty())
                    putExtra("call_mode", intent.getStringExtra(KEY_MODE).orEmpty())
                    putExtra("call_from", intent.getStringExtra(KEY_FROM).orEmpty())
                }
                openFromBackground(context, open)
            }
            ACTION_DECLINE -> {
                CallController.restoreIncoming(
                    context,
                    intent.getStringExtra(KEY_CONVERSATION).orEmpty(),
                    intent.getStringExtra(KEY_CALL).orEmpty(),
                    intent.getStringExtra(KEY_MODE).orEmpty(),
                    intent.getStringExtra(KEY_FROM).orEmpty(),
                )
                CallController.decline(context)
            }
            ACTION_HANGUP -> CallController.hangUp(context)
            ACTION_MUTE -> CallController.toggleMute()
            ACTION_EXPAND -> {
                CallController.expand()
                val open = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("action", "expand_call")
                }
                openFromBackground(context, open)
            }
        }
    }

    companion object {
        const val ACTION_ACCEPT = "com.aird.ACTION_ACCEPT_CALL"
        const val ACTION_DECLINE = "com.aird.ACTION_DECLINE_CALL"
        const val ACTION_HANGUP = "com.aird.ACTION_HANGUP_CALL"
        const val ACTION_MUTE = "com.aird.ACTION_MUTE_CALL"
        const val ACTION_EXPAND = "com.aird.ACTION_EXPAND_CALL"
        const val ACTION_HIDE = "com.aird.ACTION_HIDE_CALL"
        const val KEY_CONVERSATION = "key_conversation_id"
        const val KEY_CALL = "key_call_id"
        const val KEY_MODE = "key_call_mode"
        const val KEY_FROM = "key_call_from"

        fun openFromBackground(context: Context, intent: Intent) {
            try {
                context.startActivity(intent)
            } catch (_: Exception) {
            }
        }
    }
}
