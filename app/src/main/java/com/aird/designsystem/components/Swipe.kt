package com.aird.designsystem.components

import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.aird.designsystem.haptics.LocalAirdHaptics
import com.aird.designsystem.theme.Spacing
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@Immutable
data class SwipeAction(
    val icon: ImageVector,
    val label: String,
    val color: Color,
    val onColor: Color,
    val onTrigger: () -> Unit,
)

@Composable
fun SwipeRow(
    modifier: Modifier = Modifier,
    start: SwipeAction? = null,
    end: SwipeAction? = null,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val threshold = with(density) { 88.dp.toPx() }
    val limit = with(density) { 160.dp.toPx() }
    var offset by remember { mutableFloatStateOf(0f) }
    var armed by remember { mutableStateOf(false) }
    val haptics = LocalAirdHaptics.current
    val scope = rememberCoroutineScope()
    val startAction by rememberUpdatedState(start)
    val endAction by rememberUpdatedState(end)
    val dragState = rememberDraggableState { delta ->
        val raw = offset + delta * (if (abs(offset) > threshold) 0.35f else 1f)
        val min = if (endAction != null) -limit else 0f
        val max = if (startAction != null) limit else 0f
        offset = raw.coerceIn(min, max)
        val nowArmed = abs(offset) >= threshold
        if (nowArmed != armed) {
            armed = nowArmed
            if (nowArmed) haptics.threshold() else haptics.lightTick()
        }
    }
    Box(modifier) {
        val action = when {
            offset > 0f -> startAction
            offset < 0f -> endAction
            else -> null
        }
        if (action != null) {
            val progress = (abs(offset) / threshold).coerceIn(0f, 1f)
            Box(
                Modifier
                    .matchParentSize()
                    .background(lerp(MaterialTheme.colorScheme.surfaceContainerHigh, action.color, progress))
                    .padding(horizontal = Spacing.xxl),
                contentAlignment = if (offset > 0f) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val scale = 0.6f + 0.4f * progress + if (armed) 0.12f else 0f
                    Icon(
                        action.icon,
                        contentDescription = null,
                        tint = lerp(MaterialTheme.colorScheme.onSurfaceVariant, action.onColor, progress),
                        modifier = Modifier.size(24.dp).graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        },
                    )
                    Text(
                        action.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = action.onColor.copy(alpha = progress),
                    )
                }
            }
        }
        Box(
            Modifier
                .absoluteOffset { IntOffset(offset.roundToInt(), 0) }
                .draggable(
                    state = dragState,
                    orientation = Orientation.Horizontal,
                    enabled = enabled && (start != null || end != null),
                    onDragStopped = {
                        val fire = armed
                        val direction = offset
                        armed = false
                        scope.launch {
                            animate(offset, 0f, animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.7f, stiffness = 500f)) { value, _ ->
                                offset = value
                            }
                        }
                        if (fire) {
                            haptics.confirm()
                            (if (direction > 0f) startAction else endAction)?.onTrigger?.invoke()
                        }
                    },
                ),
        ) {
            content()
        }
    }
}
