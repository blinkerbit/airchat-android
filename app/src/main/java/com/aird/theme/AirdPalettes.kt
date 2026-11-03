package com.aird.theme

import androidx.compose.ui.graphics.Color

data class AirdPalette(
    val id: String,
    val label: String,
    val background: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val content: Color,
    val primary: Color,
    val onPrimary: Color,
    val secondary: Color,
    val accent: Color,
    val dark: Boolean,
)

object AirdPalettes {
    val all = listOf(
        AirdPalette("midnight", "Midnight OLED", Color(0xFF000000), Color(0xFF0C0E14), Color(0xFF161922), Color(0xFFF8FAFC), Color(0xFF6366F1), Color.White, Color(0xFFEC4899), Color(0xFF10B981), true),
        AirdPalette("dark", "Slate Dark", Color(0xFF0F141C), Color(0xFF161C26), Color(0xFF1E2634), Color(0xFFF1F5F9), Color(0xFF6366F1), Color.White, Color(0xFFEC4899), Color(0xFF38BDF8), true),
        AirdPalette("light", "Porcelain Light", Color(0xFFF8FAFC), Color(0xFFFFFFFF), Color(0xFFF1F5F9), Color(0xFF0F172A), Color(0xFF4F46E5), Color.White, Color(0xFFDB2777), Color(0xFF0EA5E9), false),
        AirdPalette("emerald", "Emerald Luxe", Color(0xFF0A120E), Color(0xFF101C16), Color(0xFF172820), Color(0xFFECFDF5), Color(0xFF10B981), Color(0xFF022C22), Color(0xFF14B8A6), Color(0xFF34D399), true),
        AirdPalette("nord", "Nord", Color(0xFFECEFF4), Color(0xFFE5E9F0), Color(0xFFD8DEE9), Color(0xFF2E3440), Color(0xFF5E81AC), Color(0xFFECEFF4), Color(0xFF81A1C1), Color(0xFF88C0D0), false),
        AirdPalette("dracula", "Dracula", Color(0xFF282A36), Color(0xFF21222C), Color(0xFF44475A), Color(0xFFF8F8F2), Color(0xFFFF79C6), Color(0xFF282A36), Color(0xFFBD93F9), Color(0xFF8BE9FD), true),
        AirdPalette("cyberpunk", "Cyberpunk", Color(0xFFFFEE00), Color(0xFFF8E100), Color(0xFFE5C100), Color(0xFF000000), Color(0xFFFF7598), Color(0xFF000000), Color(0xFF75D1F0), Color(0xFFC07EEC), false),
        AirdPalette("retro", "Retro", Color(0xFFE4D8B4), Color(0xFFEFE4C6), Color(0xFFDBD0A8), Color(0xFF291334), Color(0xFFEF9995), Color(0xFF291334), Color(0xFFA4CBB4), Color(0xFFDC8850), false),
        AirdPalette("autumn", "Autumn", Color(0xFFF1F1F1), Color(0xFFFAF7F5), Color(0xFFE4D8D0), Color(0xFF1F2937), Color(0xFF8C0327), Color.White, Color(0xFFD85251), Color(0xFFD59B6A), false),
    )

    fun byId(id: String): AirdPalette = all.firstOrNull { it.id == id } ?: all[1]
}
