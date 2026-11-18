package com.aird.designsystem.motion

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

val LocalReducedMotion = compositionLocalOf { false }

@Composable
fun ProvideReducedMotion(content: @Composable () -> Unit) {
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
    CompositionLocalProvider(LocalReducedMotion provides reduced, content = content)
}

private fun animatorScale(context: Context): Float =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)

object AirdMotion {
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    fun <T> spatialFast(): SpringSpec<T> = spring(dampingRatio = 0.9f, stiffness = 1400f)
    fun <T> spatial(): SpringSpec<T> = spring(dampingRatio = 0.8f, stiffness = 380f)
    fun <T> bouncy(): SpringSpec<T> = spring(dampingRatio = 0.55f, stiffness = 420f)
    fun <T> effects(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = 1600f)
    fun <T> gentle(): SpringSpec<T> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

    fun <T> fade(ms: Int = 160): FiniteAnimationSpec<T> = tween(ms, easing = Emphasized)

    private val slideSpring: FiniteAnimationSpec<IntOffset> = spring(dampingRatio = 1f, stiffness = 700f, visibilityThreshold = IntOffset(1, 1))

    fun push(reduced: Boolean): EnterTransition {
        if (reduced) return fadeIn(fade(90))
        return slideInHorizontally(slideSpring) { it / 4 } + fadeIn(tween(200, easing = EmphasizedDecelerate))
    }

    fun pushExit(reduced: Boolean): ExitTransition {
        if (reduced) return fadeOut(fade(70))
        return slideOutHorizontally(slideSpring) { -it / 10 } + fadeOut(tween(160, easing = EmphasizedAccelerate))
    }

    fun pop(reduced: Boolean): EnterTransition {
        if (reduced) return fadeIn(fade(90))
        return slideInHorizontally(slideSpring) { -it / 10 } + fadeIn(tween(200, easing = EmphasizedDecelerate))
    }

    fun popExit(reduced: Boolean): ExitTransition {
        if (reduced) return fadeOut(fade(70))
        return slideOutHorizontally(slideSpring) { it / 4 } + fadeOut(tween(150, easing = EmphasizedAccelerate))
    }

    fun through(reduced: Boolean): ContentTransform {
        if (reduced) return fadeIn(fade(90)) togetherWith fadeOut(fade(70))
        return (fadeIn(tween(220, delayMillis = 60, easing = EmphasizedDecelerate)) + scaleIn(tween(220, delayMillis = 60, easing = EmphasizedDecelerate), initialScale = 0.96f)) togetherWith
            fadeOut(tween(90, easing = EmphasizedAccelerate))
    }

    fun <S> AnimatedContentTransitionScope<S>.slide(forward: Boolean, reduced: Boolean): ContentTransform {
        if (reduced) return fadeIn(fade(90)) togetherWith fadeOut(fade(70))
        val dir = if (forward) 1 else -1
        return (slideInHorizontally(slideSpring) { it / 5 * dir } + fadeIn(tween(200, easing = EmphasizedDecelerate))) togetherWith
            (slideOutHorizontally(slideSpring) { -it / 8 * dir } + fadeOut(tween(120, easing = EmphasizedAccelerate))) using
            SizeTransform(clip = false)
    }

    fun reveal(reduced: Boolean): EnterTransition {
        if (reduced) return fadeIn(fade(90))
        return fadeIn(tween(180, easing = EmphasizedDecelerate)) +
            expandVertically(spring(dampingRatio = 1f, stiffness = 900f, visibilityThreshold = IntSize(1, 1)))
    }

    fun hide(reduced: Boolean): ExitTransition {
        if (reduced) return fadeOut(fade(70))
        return fadeOut(tween(110, easing = EmphasizedAccelerate)) +
            shrinkVertically(spring(dampingRatio = 1f, stiffness = 1200f, visibilityThreshold = IntSize(1, 1)))
    }

    fun pop(reduced: Boolean, scale: Float): EnterTransition {
        if (reduced) return fadeIn(fade(90))
        return fadeIn(tween(140)) + scaleIn(bouncy(), initialScale = scale)
    }

    fun shrink(reduced: Boolean, scale: Float): ExitTransition {
        if (reduced) return fadeOut(fade(70))
        return fadeOut(tween(100)) + scaleOut(tween(120, easing = EmphasizedAccelerate), targetScale = scale)
    }

    fun rise(reduced: Boolean): EnterTransition {
        if (reduced) return fadeIn(fade(90))
        return slideInVertically(spring(dampingRatio = 0.85f, stiffness = 600f, visibilityThreshold = IntOffset(1, 1))) { it / 2 } + fadeIn(tween(160))
    }

    fun sink(reduced: Boolean): ExitTransition {
        if (reduced) return fadeOut(fade(70))
        return slideOutVertically(tween(160, easing = EmphasizedAccelerate)) { it / 2 } + fadeOut(tween(120))
    }
}
