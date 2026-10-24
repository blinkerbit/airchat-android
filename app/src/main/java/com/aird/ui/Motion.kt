package com.aird.ui

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

val LocalAirdReducedMotion = compositionLocalOf { false }

@Composable
fun ProvideAirdMotion(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var reduced by remember { mutableStateOf(animatorScale(context) == 0f) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduced = animatorScale(context) == 0f
            }
        }
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        reduced = animatorScale(context) == 0f
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    CompositionLocalProvider(LocalAirdReducedMotion provides reduced, content = content)
}

private fun animatorScale(context: Context): Float {
    return Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    )
}

@Composable
fun pressScale(interaction: MutableInteractionSource): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && !LocalAirdReducedMotion.current) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 1f, stiffness = 1500f),
        label = "press",
    )
    return Modifier.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

@Composable
fun PressIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    IconButton(
        onClick = onClick,
        modifier = modifier.then(pressScale(interaction)),
        enabled = enabled,
        interactionSource = interaction,
        content = content,
    )
}

@Composable
fun enterRiseModifier(id: String, enter: Boolean): Modifier {
    val reduced = LocalAirdReducedMotion.current
    val play = remember(id) { enter && !reduced }
    val rise = with(LocalDensity.current) { 8.dp.toPx() }
    var target by remember(id) { mutableFloatStateOf(if (play) 0f else 1f) }
    LaunchedEffect(id) { target = 1f }
    val progress by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "messageEnter",
    )
    if (!play) return Modifier
    return Modifier.graphicsLayer {
        alpha = progress
        translationY = (1f - progress) * rise
    }
}
