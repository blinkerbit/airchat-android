package com.aird.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.materialkolor.PaletteStyle

@Immutable
data class AirdPalette(
    val id: String,
    val label: String,
    val seed: Color,
    val style: PaletteStyle = PaletteStyle.TonalSpot,
    val forceDark: Boolean = false,
)

object AirdPalettes {
    const val DYNAMIC = "dynamic"

    val aird = AirdPalette("aird", "Aird Indigo", Color(0xFF5B5BF0), PaletteStyle.Expressive)
    val midnight = AirdPalette("midnight", "Midnight OLED", Color(0xFF38BDF8), PaletteStyle.Content, forceDark = true)

    val all = listOf(
        aird,
        midnight,
        AirdPalette("ocean", "Ocean", Color(0xFF0A84FF), PaletteStyle.TonalSpot),
        AirdPalette("emerald", "Emerald", Color(0xFF10B981), PaletteStyle.TonalSpot),
        AirdPalette("rose", "Rose", Color(0xFFE5487D), PaletteStyle.Expressive),
        AirdPalette("sunset", "Sunset", Color(0xFFF2711C), PaletteStyle.Vibrant),
        AirdPalette("autumn", "Autumn", Color(0xFF8C0327), PaletteStyle.Fidelity),
        AirdPalette("retro", "Retro", Color(0xFFEF9995), PaletteStyle.Content),
        AirdPalette("cyberpunk", "Cyberpunk", Color(0xFFFFE600), PaletteStyle.Vibrant),
        AirdPalette("nord", "Nord", Color(0xFF5E81AC), PaletteStyle.Neutral),
        AirdPalette("dracula", "Dracula", Color(0xFFBD93F9), PaletteStyle.Vibrant, forceDark = true),
        AirdPalette("mono", "Graphite", Color(0xFF64748B), PaletteStyle.Monochrome),
    )

    fun byId(id: String): AirdPalette = all.firstOrNull { it.id == id } ?: aird
}
