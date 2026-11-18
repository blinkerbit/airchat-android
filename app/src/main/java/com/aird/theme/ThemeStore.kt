package com.aird.theme

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class ThemeMode { System, Light, Dark }

@Immutable
data class ThemePrefs(
    val paletteId: String = defaultPalette(),
    val mode: ThemeMode = ThemeMode.System,
    val amoled: Boolean = false,
    val haptics: Boolean = true,
    val sounds: Boolean = false,
) {
    val dynamic: Boolean get() = paletteId == AirdPalettes.DYNAMIC
}

private fun defaultPalette(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) AirdPalettes.DYNAMIC else AirdPalettes.aird.id

object ThemeStore {
    private const val PREFS = "aird"
    private const val LEGACY_KEY = "theme"
    private const val KEY_PALETTE = "theme_palette"
    private const val KEY_MODE = "theme_mode"
    private const val KEY_AMOLED = "theme_amoled"
    private const val KEY_HAPTICS = "ui_haptics"
    private const val KEY_SOUNDS = "ui_sounds"

    private val _prefs = MutableStateFlow(ThemePrefs())
    val prefs: StateFlow<ThemePrefs> = _prefs

    fun load(context: Context) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!sp.contains(KEY_PALETTE) && sp.contains(LEGACY_KEY)) migrateLegacy(context, sp.getString(LEGACY_KEY, null))
        _prefs.value = ThemePrefs(
            paletteId = sp.getString(KEY_PALETTE, null) ?: defaultPalette(),
            mode = runCatching { ThemeMode.valueOf(sp.getString(KEY_MODE, null) ?: "") }.getOrDefault(ThemeMode.System),
            amoled = sp.getBoolean(KEY_AMOLED, false),
            haptics = sp.getBoolean(KEY_HAPTICS, true),
            sounds = sp.getBoolean(KEY_SOUNDS, false),
        )
    }

    fun setPalette(context: Context, id: String) = edit(context) { it.copy(paletteId = id) }

    fun setMode(context: Context, mode: ThemeMode) = edit(context) { it.copy(mode = mode) }

    fun setAmoled(context: Context, on: Boolean) = edit(context) { it.copy(amoled = on) }

    fun setHaptics(context: Context, on: Boolean) = edit(context) { it.copy(haptics = on) }

    fun setSounds(context: Context, on: Boolean) = edit(context) { it.copy(sounds = on) }

    private fun edit(context: Context, change: (ThemePrefs) -> ThemePrefs) {
        _prefs.update(change)
        val next = _prefs.value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PALETTE, next.paletteId)
            .putString(KEY_MODE, next.mode.name)
            .putBoolean(KEY_AMOLED, next.amoled)
            .putBoolean(KEY_HAPTICS, next.haptics)
            .putBoolean(KEY_SOUNDS, next.sounds)
            .apply()
    }

    private fun migrateLegacy(context: Context, legacy: String?) {
        val (palette, mode, amoled) = when (legacy) {
            "midnight" -> Triple(AirdPalettes.aird.id, ThemeMode.Dark, true)
            "dark" -> Triple(AirdPalettes.aird.id, ThemeMode.Dark, false)
            "light" -> Triple(AirdPalettes.aird.id, ThemeMode.Light, false)
            "emerald", "dracula" -> Triple(legacy, ThemeMode.Dark, false)
            "nord", "cyberpunk", "retro", "autumn" -> Triple(legacy, ThemeMode.Light, false)
            else -> return
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PALETTE, palette)
            .putString(KEY_MODE, mode.name)
            .putBoolean(KEY_AMOLED, amoled)
            .remove(LEGACY_KEY)
            .apply()
    }
}
