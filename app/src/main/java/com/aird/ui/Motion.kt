package com.aird.ui

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val LocalAirdReducedMotion get() = com.aird.designsystem.motion.LocalReducedMotion

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

private fun quick(ms: Int): FiniteAnimationSpec<Float> = tween(durationMillis = ms, easing = FastOutSlowInEasing)

@Composable
fun airdEnter(): EnterTransition = airdEnter(LocalAirdReducedMotion.current)

fun airdEnter(reduced: Boolean): EnterTransition {
    if (reduced) return fadeIn(quick(80))
    return slideInHorizontally(tween(160, easing = FastOutSlowInEasing)) { it / 10 } + fadeIn(quick(140))
}

@Composable
fun airdExit(): ExitTransition = airdExit(LocalAirdReducedMotion.current)

fun airdExit(reduced: Boolean): ExitTransition {
    if (reduced) return fadeOut(quick(60))
    return slideOutHorizontally(tween(120, easing = FastOutSlowInEasing)) { it / 10 } + fadeOut(quick(100))
}

fun airdPush(forward: Boolean, reduced: Boolean = false): ContentTransform {
    if (reduced) return fadeIn(quick(80)) togetherWith fadeOut(quick(60))
    val dir = if (forward) 1 else -1
    return (slideInHorizontally(tween(170, easing = FastOutSlowInEasing)) { it / 6 * dir } + fadeIn(quick(130))) togetherWith
        (slideOutHorizontally(tween(130, easing = FastOutSlowInEasing)) { -it / 6 * dir } + fadeOut(quick(90)))
}

@Composable
fun airdReveal(): EnterTransition = airdReveal(LocalAirdReducedMotion.current)

fun airdReveal(reduced: Boolean): EnterTransition {
    if (reduced) return fadeIn(quick(80))
    return fadeIn(quick(120)) + expandVertically(tween(140, easing = FastOutSlowInEasing))
}

@Composable
fun airdHide(): ExitTransition = airdHide(LocalAirdReducedMotion.current)

fun airdHide(reduced: Boolean): ExitTransition {
    if (reduced) return fadeOut(quick(60))
    return fadeOut(quick(90)) + shrinkVertically(tween(110, easing = FastOutSlowInEasing))
}

@Composable
fun airdInset(depth: Int): Dp {
    val target = 16.dp * depth
    if (LocalAirdReducedMotion.current) return target
    val inset by androidx.compose.animation.core.animateDpAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 140, easing = FastOutSlowInEasing),
        label = "indent",
    )
    return inset
}
