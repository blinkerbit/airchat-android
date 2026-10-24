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
        AirdPalette("light", "Light", Color(0xFFFFFFFF), Color(0xFFF2F2F2), Color(0xFFE5E6E6), Color(0xFF1F2937), Color(0xFF570DF8), Color.White, Color(0xFFF000B8), Color(0xFF37CDBE), false),
        AirdPalette("dark", "Dark", Color(0xFF1D232A), Color(0xFF191E24), Color(0xFF15191E), Color(0xFFA6ADBB), Color(0xFF661AE6), Color.White, Color(0xFFD926A9), Color(0xFF1FB2A6), true),
        AirdPalette("nord", "Nord", Color(0xFFECEFF4), Color(0xFFE5E9F0), Color(0xFFD8DEE9), Color(0xFF2E3440), Color(0xFF5E81AC), Color(0xFFECEFF4), Color(0xFF81A1C1), Color(0xFF88C0D0), false),
        AirdPalette("dracula", "Dracula", Color(0xFF282A36), Color(0xFF21222C), Color(0xFF44475A), Color(0xFFF8F8F2), Color(0xFFFF79C6), Color(0xFF282A36), Color(0xFFBD93F9), Color(0xFF8BE9FD), true),
        AirdPalette("cyberpunk", "Cyberpunk", Color(0xFFFFEE00), Color(0xFFF8E100), Color(0xFFE5C100), Color(0xFF000000), Color(0xFFFF7598), Color(0xFF000000), Color(0xFF75D1F0), Color(0xFFC07EEC), false),
        AirdPalette("retro", "Retro", Color(0xFFE4D8B4), Color(0xFFEFE4C6), Color(0xFFDBD0A8), Color(0xFF291334), Color(0xFFEF9995), Color(0xFF291334), Color(0xFFA4CBB4), Color(0xFFDC8850), false),
        AirdPalette("autumn", "Autumn", Color(0xFFF1F1F1), Color(0xFFFAF7F5), Color(0xFFE4D8D0), Color(0xFF1F2937), Color(0xFF8C0327), Color.White, Color(0xFFD85251), Color(0xFFD59B6A), false),
    )

    fun byId(id: String): AirdPalette = all.firstOrNull { it.id == id } ?: all[1]
}
