package com.aird.designsystem.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aird.designsystem.motion.LocalReducedMotion
import com.aird.designsystem.theme.Spacing

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = Spacing.xxxl, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(112.dp)
                .clip(MaterialShapes.Cookie9Sided.toShape())
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(44.dp))
        }
        Spacer(Modifier.height(Spacing.xxl))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface)
        if (!body.isNullOrBlank()) {
            Spacer(Modifier.height(Spacing.sm))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(max = 320.dp),
            )
        }
        if (action != null && onAction != null) {
            Spacer(Modifier.height(Spacing.xl))
            FilledTonalButton(onClick = onAction) { Text(action) }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AirdLoading(modifier: Modifier = Modifier, contained: Boolean = false, size: Dp = 48.dp) {
    if (contained) {
        ContainedLoadingIndicator(modifier.size(size))
    } else {
        LoadingIndicator(modifier.size(size))
    }
}

@Composable
fun CenteredLoading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
        AirdLoading()
    }
}

fun Modifier.shimmer(): Modifier = composed {
    val reduced = LocalReducedMotion.current
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val glow = MaterialTheme.colorScheme.surfaceContainerHighest
    if (reduced) return@composed background(base)
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmerProgress",
    )
    drawWithContent {
        val width = size.width
        val x = width * progress
        drawRect(base)
        drawRect(
            Brush.linearGradient(
                colors = listOf(Color.Transparent, glow, Color.Transparent),
                start = Offset(x - width * 0.4f, 0f),
                end = Offset(x + width * 0.4f, size.height),
            ),
        )
    }
}

@Composable
fun SkeletonBlock(width: Dp, height: Dp, modifier: Modifier = Modifier, round: Boolean = false) {
    Box(
        modifier
            .size(width, height)
            .clip(if (round) CircleShape else RoundedCornerShape(6.dp))
            .shimmer(),
    )
}

@Composable
fun SkeletonRow(modifier: Modifier = Modifier, avatar: Dp = 52.dp, lines: Int = 2) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SkeletonBlock(avatar, avatar, round = true)
        Spacer(Modifier.width(Spacing.md))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SkeletonBlock(140.dp, 14.dp)
            if (lines > 1) SkeletonBlock(220.dp, 12.dp)
        }
    }
}
