package com.aird.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.materialkolor.ktx.harmonize

@Immutable
data class AirdColors(
    val dark: Boolean,
    val bubbleMine: Color,
    val bubbleMineEnd: Color,
    val onBubbleMine: Color,
    val bubbleTheirs: Color,
    val onBubbleTheirs: Color,
    val online: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val link: Color,
    val mention: Color,
    val highlight: Color,
    val scrim: Color,
    val hairline: Color,
) {
    val bubbleMineBrush: Brush = Brush.linearGradient(listOf(bubbleMine, bubbleMineEnd))
}

fun airdColorsFor(scheme: ColorScheme, dark: Boolean, accent: Color? = null): AirdColors {
    val mine = accent ?: scheme.primary
    val mineEnd = lerp(mine, scheme.tertiary, if (accent == null) 0.28f else 0.12f)
    val onMine = if (accent == null) scheme.onPrimary else onColorFor(mine)
    return AirdColors(
        dark = dark,
        bubbleMine = mine,
        bubbleMineEnd = mineEnd,
        onBubbleMine = onMine,
        bubbleTheirs = if (dark) scheme.surfaceContainerHigh else scheme.surfaceContainerHighest,
        onBubbleTheirs = scheme.onSurface,
        online = Color(0xFF22C55E).harmonize(scheme.primary),
        success = Color(0xFF16A34A).harmonize(scheme.primary),
        warning = Color(0xFFF59E0B).harmonize(scheme.primary),
        danger = scheme.error,
        link = if (dark) lerp(scheme.primary, Color.White, 0.25f) else scheme.primary,
        mention = scheme.tertiary,
        highlight = scheme.tertiaryContainer,
        scrim = Color.Black.copy(alpha = if (dark) 0.6f else 0.36f),
        hairline = scheme.outlineVariant.copy(alpha = 0.6f),
    )
}

private fun onColorFor(color: Color): Color {
    val luminance = 0.2126f * color.red + 0.7152f * color.green + 0.0722f * color.blue
    return if (luminance > 0.6f) Color(0xFF111318) else Color.White
}

val LocalAirdColors = staticCompositionLocalOf<AirdColors> { error("AirdColors not provided") }
