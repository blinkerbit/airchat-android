package com.aird.designsystem.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aird.designsystem.motion.AirdMotion
import com.aird.designsystem.motion.LocalReducedMotion
import com.aird.designsystem.theme.TabularNumbers

fun badgeLabel(count: Int): String = if (count > 99) "99+" else count.toString()

@Composable
fun CountBadge(
    count: Int,
    modifier: Modifier = Modifier,
    muted: Boolean = false,
    container: Color = if (muted) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
    content: Color = if (muted) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onPrimary,
) {
    val reduced = LocalReducedMotion.current
    AnimatedVisibility(
        visible = count > 0,
        modifier = modifier,
        enter = AirdMotion.pop(reduced, 0.3f),
        exit = AirdMotion.shrink(reduced, 0.3f),
    ) {
        Box(
            Modifier
                .defaultMinSize(minWidth = 22.dp, minHeight = 22.dp)
                .clip(CircleShape)
                .background(container)
                .padding(horizontal = 6.dp)
                .semantics { contentDescription = "$count unread" },
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = count,
                transitionSpec = { countTransition(targetState > initialState, reduced) },
                label = "badgeCount",
            ) { value ->
                Text(
                    badgeLabel(value),
                    color = content,
                    style = MaterialTheme.typography.labelMedium.merge(TabularNumbers),
                )
            }
        }
    }
}

fun countTransition(up: Boolean, reduced: Boolean): ContentTransform {
    if (reduced) return fadeIn(tween(80)) togetherWith fadeOut(tween(60))
    val dir = if (up) 1 else -1
    return (slideInVertically(AirdMotion.spatialFast()) { it * dir } + fadeIn(tween(120))) togetherWith
        (slideOutVertically(AirdMotion.spatialFast()) { -it * dir } + fadeOut(tween(90)))
}

@Composable
fun Dot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.padding(2.dp).defaultMinSize(8.dp, 8.dp).clip(CircleShape).background(color))
}
