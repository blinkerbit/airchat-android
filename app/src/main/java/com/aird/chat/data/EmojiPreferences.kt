package com.aird.chat.data

import android.content.Context

object EmojiPreferences {
    private const val PREFS = "aird_emoji_prefs"
    private const val KEY_SKIN_TONE = "default_skin_tone"

    data class SkinOption(val id: String, val label: String, val preview: String, val modifier: String)

    val allSkins = listOf(
        SkinOption("default", "Default", "🖐️", ""),
        SkinOption("light", "Light", "🖐🏻", "\uD83C\uDFFB"),
        SkinOption("medium-light", "Medium-Light", "🖐🏼", "\uD83C\uDFFC"),
        SkinOption("medium", "Medium", "🖐🏽", "\uD83C\uDFFD"),
        SkinOption("medium-dark", "Medium-Dark", "🖐🏾", "\uD83C\uDFFE"),
        SkinOption("dark", "Dark", "🖐🏿", "\uD83C\uDFFF"),
    )

    fun getSkinTone(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SKIN_TONE, "default") ?: "default"
    }

    fun setSkinTone(context: Context, skinTone: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SKIN_TONE, skinTone)
            .apply()
    }

    fun applySkinTone(emoji: String, context: Context): String {
        val currentTone = getSkinTone(context)
        if (currentTone == "default" || emoji.isEmpty()) return emoji
        val targetOption = allSkins.firstOrNull { it.id == currentTone } ?: return emoji
        val modifier = targetOption.modifier
        if (modifier.isEmpty()) return emoji

        // Check if emoji already contains a skin tone modifier
        if (emoji.contains("\uD83C\uDFFB") || emoji.contains("\uD83C\uDFFC") ||
            emoji.contains("\uD83C\uDFFD") || emoji.contains("\uD83C\uDFFE") ||
            emoji.contains("\uD83C\uDFFF")) {
            return emoji
        }

        return if (canAcceptSkinTone(emoji)) "$emoji$modifier" else emoji
    }

    private fun canAcceptSkinTone(emoji: String): Boolean {
        if (emoji.isEmpty()) return false
        val cp = emoji.codePointAt(0)
        return (cp in 0x1F440..0x1F490) || (cp in 0x1F650..0x1F6CC) ||
               (cp in 0x1F90C..0x1F93F) || (cp in 0x1F970..0x1F9DF) ||
               (cp in 0x2600..0x27BF) || (cp in 0x1F380..0x1F3C4)
    }
}
