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
            secondary = palette.secondary,
            tertiary = palette.accent,
            background = palette.background,
            surface = palette.surface,
            surfaceVariant = palette.surfaceAlt,
            onBackground = palette.content,
            onSurface = palette.content,
            onSurfaceVariant = palette.content,
        )
    } else {
        lightColorScheme(
            primary = palette.primary,
            onPrimary = palette.onPrimary,
            secondary = palette.secondary,
            tertiary = palette.accent,
            background = palette.background,
            surface = palette.surface,
            surfaceVariant = palette.surfaceAlt,
            onBackground = palette.content,
            onSurface = palette.content,
            onSurfaceVariant = palette.content,
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
        tertiary = ease(tertiary, "tertiary"),
        background = ease(background, "background"),
        onBackground = ease(onBackground, "onBackground"),
        surface = ease(surface, "surface"),
        onSurface = ease(onSurface, "onSurface"),
        surfaceVariant = ease(surfaceVariant, "surfaceVariant"),
        onSurfaceVariant = ease(onSurfaceVariant, "onSurfaceVariant"),
        surfaceTint = ease(surfaceTint, "surfaceTint"),
    )
}

@Composable
private fun ease(color: Color, label: String): Color {
    val eased by animateColorAsState(color, tween(240), label = label)
    return eased
}
