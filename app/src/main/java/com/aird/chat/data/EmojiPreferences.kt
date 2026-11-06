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

    private const val EMOJI_PICKER_PREFS = "androidx.emoji2.emojipicker.preferences"
    private const val STICKY_KEY = "pref_key_sticky_variant"

    private val baseHumanEmojis = listOf(
        "🖐", "👍", "👎", "👊", "✊", "🤛", "🤜", "👏", "🙌", "👐", "🤲", "🤝", "🙏",
        "✌️", "🤟", "🤘", "🤙", "👈", "👉", "👆", "🖕", "👇", "☝️", "✍️", "💅", "🤳",
        "💪", "🦵", "🦶", "👂", "🦻", "👃", "👶", "🧒", "👦", "👧", "🧑", "👨", "👩",
        "👴", "👵", "👮", "👷", "💂", "🕵", "🤶", "🧑‍🎄", "🎅", "🙅", "🙆", "🙇", "🙋",
        "🙌", "💆", "💇", "🚶", "🧍", "🧎", "🏃", "💃", "🕺", "🧖", "🧗", "🛌", "🛀"
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
        syncPickerPreferences(context, skinTone)
    }

    private fun syncPickerPreferences(context: Context, skinTone: String) {
        val pickerPrefs = context.getSharedPreferences(EMOJI_PICKER_PREFS, Context.MODE_PRIVATE)
        val targetOption = allSkins.firstOrNull { it.id == skinTone } ?: return
        val modifier = targetOption.modifier

        val currentStr = pickerPrefs.getString(STICKY_KEY, "") ?: ""
        val map = currentStr.split("|")
            .mapNotNull { entry ->
                val parts = entry.split("=", limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else null
            }
            .toMap()
            .toMutableMap()

        baseHumanEmojis.forEach { base ->
            if (modifier.isEmpty()) {
                map.remove(base)
            } else {
                map[base] = "$base$modifier"
            }
        }

        val updatedStr = map.entries.joinToString("|") { "${it.key}=${it.value}" }
        pickerPrefs.edit().putString(STICKY_KEY, updatedStr).apply()
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
        return when (cp) {
            0x261D, 0x270A, 0x270B, 0x270C, 0x270D -> true
            in 0x1F442..0x1F443 -> true
            in 0x1F446..0x1F450 -> true
            in 0x1F466..0x1F490 -> true
            0x1F4AA -> true
            0x1F590, 0x1F595, 0x1F596 -> true
            in 0x1F645..0x1F64F -> true  // Gesturing people (🙅🙆🙇🙋🙌), NOT smileys (0x1F600..0x1F644)
            0x1F6AC -> true
            in 0x1F6B4..0x1F6B6 -> true
            0x1F6C0, 0x1F6CC -> true
            0x1F90C, 0x1F90F -> true
            in 0x1F918..0x1F91F -> true
            0x1F926 -> true
            in 0x1F930..0x1F93E -> true
            0x1F977 -> true
            in 0x1F9B5..0x1F9B9 -> true
            in 0x1F9D1..0x1F9DD -> true
            in 0x1FAF0..0x1FAF8 -> true  // Hand gestures (🫱🫲🫷🫸🫶🫰)
            else -> false
        }
    }
}
