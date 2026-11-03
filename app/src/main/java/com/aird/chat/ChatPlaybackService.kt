package com.aird.chat

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import com.aird.MainActivity
import com.aird.session.AirdHttp

class ChatPlaybackService : MediaSessionService() {
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val source = OkHttpDataSource.Factory(AirdHttp.client)
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(source))
            .build()
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            true,
        )
        player.setHandleAudioBecomingNoisy(true)
        this.player = player
        val open = PendingIntent.getActivity(
            this,
            3,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        mediaSession = MediaSession.Builder(this, player).setSessionActivity(open).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (player?.isPlaying != true) stopSelf()
    }

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        player?.release()
        player = null
        super.onDestroy()
    }
}

object ChatPlayback {
    private var controller: MediaController? = null
    private var connecting = false
    private var pendingUrl = ""
    private var pendingTitle = ""

    fun play(context: Context, url: String, title: String) {
        val resolved = resolve(context, url)
        if (resolved.isEmpty()) return
        val label = title.ifBlank { "Audio" }
        val app = context.applicationContext
        val current = controller
        if (current != null && current.isConnected) {
            start(current, resolved, label)
            return
        }
        pendingUrl = resolved
        pendingTitle = label
        if (connecting) return
        runCatching { current?.release() }
        controller = null
        connecting = true
        val token = SessionToken(app, ComponentName(app, ChatPlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        future.addListener({
            connecting = false
            val ready = try {
                future.get()
            } catch (_: Exception) {
                null
            }
            if (ready == null) return@addListener
            controller = ready
            val nextUrl = pendingUrl.ifEmpty { resolved }
            val nextTitle = pendingTitle.ifEmpty { label }
            pendingUrl = ""
            pendingTitle = ""
            start(ready, nextUrl, nextTitle)
        }, ContextCompat.getMainExecutor(app))
    }

    private fun start(controller: MediaController, url: String, title: String) {
        val item = MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
            .build()
        controller.setMediaItem(item)
        controller.prepare()
        controller.play()
    }

    private fun resolve(context: Context, url: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.startsWith("http://", true) ||
            trimmed.startsWith("https://", true) ||
            trimmed.startsWith("file:", true) ||
            trimmed.startsWith("content:", true)
        ) {
            return trimmed
        }
        return AirdHttp.absolute(context, trimmed)
    }
}
