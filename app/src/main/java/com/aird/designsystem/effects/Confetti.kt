package com.aird.designsystem.effects

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import com.aird.designsystem.motion.LocalReducedMotion
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private data class ConfettiParticle(
    val x: Float,
    val y: Float,
    val vx: Float,
    val vy: Float,
    val size: Float,
    val color: Color,
    val spin: Float,
)

@Composable
fun ConfettiBurst(
    active: Boolean,
    modifier: Modifier = Modifier,
    colors: List<Color> = listOf(
        Color(0xFFE53935),
        Color(0xFFFB8C00),
        Color(0xFFFDD835),
        Color(0xFF43A047),
        Color(0xFF1E88E5),
        Color(0xFF8E24AA),
    ),
    onFinished: () -> Unit = {},
) {
    val reduced = LocalReducedMotion.current
    if (!active || reduced) {
        if (active && reduced) LaunchedEffect(active) { onFinished() }
        return
    }
    val progress = remember { Animatable(0f) }
    val particles = remember {
        List(36) {
            val angle = Random.nextFloat() * Math.PI.toFloat() * 2f
            val speed = 0.35f + Random.nextFloat() * 0.9f
            ConfettiParticle(
                x = 0.5f,
                y = 0.45f,
                vx = cos(angle) * speed,
                vy = sin(angle) * speed - 0.4f,
                size = 4f + Random.nextFloat() * 6f,
                color = colors[it % colors.size],
                spin = Random.nextFloat() * 360f,
            )
        }
    }
    LaunchedEffect(active) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(900, easing = LinearEasing))
        onFinished()
    }
    Canvas(modifier.fillMaxSize()) {
        val t = progress.value
        particles.forEach { p ->
            val px = (p.x + p.vx * t) * size.width
            val py = (p.y + p.vy * t + 0.9f * t * t) * size.height
            val alpha = (1f - t).coerceIn(0f, 1f)
            rotate(p.spin * t, Offset(px, py)) {
                drawRect(
                    color = p.color.copy(alpha = alpha),
                    topLeft = Offset(px - p.size / 2f, py - p.size / 2f),
                    size = androidx.compose.ui.geometry.Size(p.size, p.size * 0.6f),
                )
            }
        }
    }
}
