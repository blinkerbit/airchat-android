package com.aird.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object ThemeStore {
    private const val PREFS = "aird"
    private const val KEY = "theme"

    private val _id = MutableStateFlow("dark")
    val id: StateFlow<String> = _id

    fun load(context: Context) {
        _id.value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "dark") ?: "dark"
    }

    fun set(context: Context, themeId: String) {
        _id.value = themeId
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, themeId).apply()
    }
}
