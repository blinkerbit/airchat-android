package com.aird.chat.data

import android.content.Context
import me.leolin.shortcutbadger.ShortcutBadger

object UnreadBadge {
    fun apply(context: Context, count: Int) {
        val app = context.applicationContext
        if (count > 0) {
            ShortcutBadger.applyCount(app, count.coerceAtMost(999))
        } else {
            ShortcutBadger.removeCount(app)
        }
    }
}
