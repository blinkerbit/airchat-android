package com.aird

import android.app.Application
import androidx.emoji2.text.DefaultEmojiCompatConfig
import androidx.emoji2.text.EmojiCompat
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.aird.data.net.AirdImages

class AirdApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        com.aird.theme.ThemeStore.load(this)
        val config = DefaultEmojiCompatConfig.create(this)
        if (config != null) {
            EmojiCompat.init(config)
        }
    }

    override fun newImageLoader(): ImageLoader = AirdImages.loader(this)
}
