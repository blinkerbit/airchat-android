package com.aird

import android.content.Context
import android.net.Uri

object ServerStore {
    private const val PREFS = "aird"
    private const val KEY = "server_url"
    const val DEFAULT = "https://dx6si.aiccloud.online"

    fun get(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT
    }

    fun set(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, normalize(url))
            .apply()
    }

    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        return withScheme.trimEnd('/')
    }

    fun isValid(raw: String): Boolean {
        val uri = Uri.parse(normalize(raw))
        val scheme = uri.scheme
        return (scheme == "https" || scheme == "http") && !uri.host.isNullOrBlank()
    }
}
