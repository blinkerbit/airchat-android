package com.aird

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.webkit.CookieManager
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != "android.intent.action.QUICKBOOT_POWERON") return
        val cookie = try {
            CookieManager.getInstance().getCookie(ServerStore.get(context))
        } catch (_: Exception) {
            null
        }
        if (cookie.isNullOrBlank()) return
        try {
            ContextCompat.startForegroundService(context, Intent(context, AirdConnectionService::class.java))
        } catch (_: Exception) {
        }
    }
}
