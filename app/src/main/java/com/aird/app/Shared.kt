package com.aird.app

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import com.aird.designsystem.motion.LocalReducedMotion

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalRouteScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/** Morphs this element into the one with the same key on the next screen. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.shared(key: String): Modifier {
    val shared = LocalSharedScope.current ?: return this
    val route = LocalRouteScope.current ?: return this
    if (LocalReducedMotion.current) return this
    return with(shared) { this@shared.sharedElement(rememberSharedContentState(key), route) }
}

object SharedKeys {
    fun avatar(id: String) = "avatar:$id"
    fun title(id: String) = "title:$id"
}
