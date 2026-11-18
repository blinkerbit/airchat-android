package com.aird.session

import android.os.Handler
import android.os.HandlerThread
import android.webkit.CookieManager
import java.util.concurrent.atomic.AtomicBoolean

/** Persists cookies off the calling thread, at most once per second. Reads never need it. */
object CookieFlush {
    private val thread = HandlerThread("aird-cookies").apply { start() }
    private val handler = Handler(thread.looper)
    private val queued = AtomicBoolean(false)

    fun soon() {
        if (!queued.compareAndSet(false, true)) return
        handler.postDelayed({
            queued.set(false)
            try {
                CookieManager.getInstance().flush()
            } catch (_: Exception) {
            }
        }, 1_000)
    }
}
