package com.aird.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import com.aird.theme.AirdPalette
import com.aird.ui.LocalAirdReducedMotion

@Composable
fun AirdTheme(palette: AirdPalette, content: @Composable () -> Unit) {
    val scheme = if (palette.dark) {
        darkColorScheme(
            primary = palette.primary,
            onPrimary = palette.onPrimary,
            primaryContainer = palette.primary.copy(alpha = 0.22f),
            onPrimaryContainer = palette.primary,
            secondary = palette.secondary,
            onSecondary = Color.White,
            secondaryContainer = palette.secondary.copy(alpha = 0.18f),
            onSecondaryContainer = palette.secondary,
            tertiary = palette.accent,
            onTertiary = Color.White,
            tertiaryContainer = palette.accent.copy(alpha = 0.18f),
            onTertiaryContainer = palette.accent,
            background = palette.background,
            onBackground = palette.content,
            surface = palette.surface,
            onSurface = palette.content,
            surfaceVariant = palette.surfaceAlt,
            onSurfaceVariant = palette.content.copy(alpha = 0.65f),
            surfaceContainer = palette.surfaceAlt,
            surfaceContainerLow = palette.surface,
            surfaceContainerLowest = palette.background,
            surfaceContainerHigh = palette.surfaceAlt,
            surfaceContainerHighest = palette.surfaceAlt,
            surfaceTint = palette.primary,
            outline = palette.content.copy(alpha = 0.16f),
            outlineVariant = palette.content.copy(alpha = 0.08f),
            inverseSurface = Color(0xFFF1F5F9),
            inverseOnSurface = Color(0xFF0F172A),
        )
    } else {
        lightColorScheme(
            primary = palette.primary,
            onPrimary = palette.onPrimary,
            primaryContainer = palette.primary.copy(alpha = 0.12f),
            onPrimaryContainer = palette.primary,
            secondary = palette.secondary,
            onSecondary = Color.White,
            secondaryContainer = palette.secondary.copy(alpha = 0.10f),
            onSecondaryContainer = palette.secondary,
            tertiary = palette.accent,
            onTertiary = Color.White,
            tertiaryContainer = palette.accent.copy(alpha = 0.10f),
            onTertiaryContainer = palette.accent,
            background = palette.background,
            onBackground = palette.content,
            surface = palette.surface,
            onSurface = palette.content,
            surfaceVariant = palette.surfaceAlt,
            onSurfaceVariant = palette.content.copy(alpha = 0.62f),
            surfaceContainer = palette.surfaceAlt,
            surfaceContainerLow = palette.surface,
            surfaceContainerLowest = palette.background,
            surfaceContainerHigh = Color.White,
            surfaceContainerHighest = palette.surfaceAlt,
            surfaceTint = palette.primary,
            outline = palette.content.copy(alpha = 0.14f),
            outlineVariant = palette.content.copy(alpha = 0.06f),
            inverseSurface = Color(0xFF0F172A),
            inverseOnSurface = Color(0xFFF8FAFC),
        )
    }
    MaterialTheme(colorScheme = if (LocalAirdReducedMotion.current) scheme else scheme.eased(), content = content)
}

@Composable
private fun ColorScheme.eased(): ColorScheme {
    return copy(
        primary = ease(primary, "primary"),
        onPrimary = ease(onPrimary, "onPrimary"),
        primaryContainer = ease(primaryContainer, "primaryContainer"),
        onPrimaryContainer = ease(onPrimaryContainer, "onPrimaryContainer"),
        secondary = ease(secondary, "secondary"),
        onSecondary = ease(onSecondary, "onSecondary"),
        secondaryContainer = ease(secondaryContainer, "secondaryContainer"),
        onSecondaryContainer = ease(onSecondaryContainer, "onSecondaryContainer"),
        tertiary = ease(tertiary, "tertiary"),
        tertiaryContainer = ease(tertiaryContainer, "tertiaryContainer"),
        onTertiaryContainer = ease(onTertiaryContainer, "onTertiaryContainer"),
        background = ease(background, "background"),
        onBackground = ease(onBackground, "onBackground"),
        surface = ease(surface, "surface"),
        onSurface = ease(onSurface, "onSurface"),
        surfaceVariant = ease(surfaceVariant, "surfaceVariant"),
        onSurfaceVariant = ease(onSurfaceVariant, "onSurfaceVariant"),
        surfaceContainer = ease(surfaceContainer, "surfaceContainer"),
        surfaceContainerLow = ease(surfaceContainerLow, "surfaceContainerLow"),
        surfaceContainerHigh = ease(surfaceContainerHigh, "surfaceContainerHigh"),
        surfaceTint = ease(surfaceTint, "surfaceTint"),
        outline = ease(outline, "outline"),
        outlineVariant = ease(outlineVariant, "outlineVariant"),
    )
}

@Composable
private fun ease(color: Color, label: String): Color {
    val eased by animateColorAsState(color, tween(240), label = label)
    return eased
}
