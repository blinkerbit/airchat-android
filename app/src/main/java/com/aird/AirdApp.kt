package com.aird

import android.app.Application
import androidx.emoji2.text.DefaultEmojiCompatConfig
import androidx.emoji2.text.EmojiCompat

class AirdApp : Application() {
    override fun onCreate() {
        super.onCreate()
        com.aird.theme.ThemeStore.load(this)
        val config = DefaultEmojiCompatConfig.create(this)
        if (config != null) {
            EmojiCompat.init(config)
        }
    }
}
