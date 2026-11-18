package com.aird

import android.content.Context
import android.content.Intent
import java.util.UUID

/** Extras on [MainActivity] are forgeable because the activity is exported. Stamp the ones we create. */
object InternalLaunch {
    private const val EXTRA = "aird.launch_token"
    private const val KEY = "launch_token"

    fun stamp(context: Context, intent: Intent): Intent {
        intent.putExtra(EXTRA, token(context))
        return intent
    }

    fun trusted(context: Context, intent: Intent): Boolean {
        val given = intent.getStringExtra(EXTRA) ?: return false
        if (given.isEmpty()) return false
        return given == token(context)
    }

    private fun token(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences("aird", Context.MODE_PRIVATE)
        synchronized(this) {
            val existing = prefs.getString(KEY, null)
            if (!existing.isNullOrEmpty()) return existing
            val created = UUID.randomUUID().toString() + UUID.randomUUID().toString()
            prefs.edit().putString(KEY, created).commit()
            return created
        }
    }
}
