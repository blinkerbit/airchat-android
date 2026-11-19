package com.aird.designsystem.effects

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import com.aird.designsystem.motion.LocalReducedMotion
import kotlin.math.roundToInt
import kotlin.random.Random

private data class BurstPiece(
    val dx: Float,
    val dy: Float,
    val delay: Int,
)

/** Short emoji particle burst for reactions. */
@Composable
fun ReactionBurst(
    emoji: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onFinished: () -> Unit = {},
) {
    val reduced = LocalReducedMotion.current
    if (!active || emoji.isBlank()) return
    if (reduced) {
        LaunchedEffect(active, emoji) { onFinished() }
        return
    }
    val density = LocalDensity.current
    val pieces = remember(emoji) {
        List(7) {
            BurstPiece(
                dx = (Random.nextFloat() - 0.5f) * 120f,
                dy = -40f - Random.nextFloat() * 90f,
                delay = Random.nextInt(0, 80),
            )
        }
    }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(active, emoji) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(520, easing = FastOutSlowInEasing))
        onFinished()
    }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        pieces.forEach { piece ->
            val t = ((progress.value * 1000f - piece.delay) / 440f).coerceIn(0f, 1f)
            if (t <= 0f) return@forEach
            val alpha = if (t < 0.7f) 1f else (1f - (t - 0.7f) / 0.3f)
            val scale = 0.6f + t * 0.7f
            Text(
                emoji,
                fontSize = 22.sp,
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (piece.dx * t * density.density).roundToInt(),
                            (piece.dy * t * density.density).roundToInt(),
                        )
                    }
                    .scale(scale)
                    .alpha(alpha),
            )
        }
    }
}
