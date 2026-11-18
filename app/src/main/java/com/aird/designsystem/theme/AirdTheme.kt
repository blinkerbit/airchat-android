package com.aird.designsystem.theme

import android.app.Activity
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.aird.designsystem.haptics.LocalAirdHaptics
import com.aird.designsystem.haptics.rememberAirdHaptics
import com.aird.designsystem.motion.LocalReducedMotion
import com.aird.designsystem.motion.ProvideReducedMotion
import com.aird.theme.AirdPalettes
import com.aird.theme.ThemeMode
import com.aird.theme.ThemePrefs
import com.aird.theme.ThemeStore
import com.materialkolor.dynamicColorScheme

@Composable
fun resolveDark(prefs: ThemePrefs): Boolean {
    val system = isSystemInDarkTheme()
    val palette = AirdPalettes.byId(prefs.paletteId)
    if (!prefs.dynamic && palette.forceDark) return true
    return when (prefs.mode) {
        ThemeMode.System -> system
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
}

@Composable
fun rememberAirdColorScheme(prefs: ThemePrefs, dark: Boolean): ColorScheme {
    val context = LocalContext.current
    return remember(prefs.paletteId, prefs.amoled, dark) {
        val amoled = dark && prefs.amoled
        if (prefs.dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val base = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            if (amoled) base.toAmoled() else base
        } else {
            val palette = AirdPalettes.byId(prefs.paletteId)
            dynamicColorScheme(palette.seed, dark, amoled, style = palette.style)
        }
    }
}

private fun ColorScheme.toAmoled(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF07080A),
    surfaceContainer = Color(0xFF0D0E11),
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AirdTheme(content: @Composable () -> Unit) {
    val prefs by ThemeStore.prefs.collectAsState()
    ProvideReducedMotion {
        val dark = resolveDark(prefs)
        val target = rememberAirdColorScheme(prefs, dark)
        val reduced = LocalReducedMotion.current
        val scheme = if (reduced) target else target.eased()
        val view = LocalView.current
        if (!view.isInEditMode) {
            SideEffect {
                val window = (view.context as? Activity)?.window ?: return@SideEffect
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !dark
                controller.isAppearanceLightNavigationBars = !dark
            }
        }
        val colors = remember(scheme, dark) { airdColorsFor(scheme, dark) }
        val haptics = rememberAirdHaptics(prefs.haptics)
        MaterialExpressiveTheme(
            colorScheme = scheme,
            motionScheme = if (reduced) MotionScheme.standard() else MotionScheme.expressive(),
            shapes = AirdShapes,
            typography = AirdTypography,
        ) {
            CompositionLocalProvider(
                LocalAirdColors provides colors,
                LocalAirdHaptics provides haptics,
                content = content,
            )
        }
    }
}

@Composable
private fun ColorScheme.eased(): ColorScheme = copy(
    primary = ease(primary),
    onPrimary = ease(onPrimary),
    primaryContainer = ease(primaryContainer),
    onPrimaryContainer = ease(onPrimaryContainer),
    secondary = ease(secondary),
    secondaryContainer = ease(secondaryContainer),
    onSecondaryContainer = ease(onSecondaryContainer),
    tertiary = ease(tertiary),
    tertiaryContainer = ease(tertiaryContainer),
    onTertiaryContainer = ease(onTertiaryContainer),
    background = ease(background),
    onBackground = ease(onBackground),
    surface = ease(surface),
    onSurface = ease(onSurface),
    surfaceVariant = ease(surfaceVariant),
    onSurfaceVariant = ease(onSurfaceVariant),
    surfaceContainerLowest = ease(surfaceContainerLowest),
    surfaceContainerLow = ease(surfaceContainerLow),
    surfaceContainer = ease(surfaceContainer),
    surfaceContainerHigh = ease(surfaceContainerHigh),
    surfaceContainerHighest = ease(surfaceContainerHighest),
    outline = ease(outline),
    outlineVariant = ease(outlineVariant),
)

@Composable
private fun ease(color: Color): Color {
    val value by animateColorAsState(color, tween(320), label = "scheme")
    return value
}

object AirdTokens {
    val colors: AirdColors
        @Composable get() = LocalAirdColors.current
}
