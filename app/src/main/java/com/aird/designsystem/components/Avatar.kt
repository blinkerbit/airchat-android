package com.aird.designsystem.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.aird.designsystem.motion.AirdMotion
import com.aird.designsystem.motion.LocalReducedMotion
import com.aird.designsystem.theme.LocalAirdColors
import kotlin.math.abs

private val hues = floatArrayOf(4f, 22f, 38f, 145f, 168f, 190f, 205f, 222f, 248f, 268f, 290f, 318f, 338f)

fun avatarInitials(name: String): String {
    val parts = name.trim().split(Regex("[\\s._-]+")).filter { it.isNotEmpty() }
    if (parts.isEmpty()) return "?"
    val first = parts[0].first()
    val second = parts.getOrNull(1)?.firstOrNull()
    return (if (second != null) "$first$second" else parts[0].take(2)).uppercase()
}

fun avatarBrush(name: String, dark: Boolean): Brush {
    val hash = abs(name.lowercase().hashCode())
    val hue = hues[hash % hues.size]
    val start = Color.hsl(hue, 0.62f, if (dark) 0.52f else 0.58f)
    val end = Color.hsl((hue + 28f) % 360f, 0.7f, if (dark) 0.40f else 0.48f)
    return Brush.linearGradient(listOf(start, lerp(end, start, 0.15f)))
}

@Composable
fun Avatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    imageUrl: String? = null,
    online: Boolean = false,
    group: Boolean = false,
    ring: Color? = null,
) {
    val dark = LocalAirdColors.current.dark
    val brush = remember(name, dark) { avatarBrush(name, dark) }
    val initials = remember(name) { avatarInitials(name) }
    Box(modifier.size(size).clearAndSetSemantics { }) {
        val ringModifier = if (ring != null) Modifier.border(2.dp, ring, CircleShape) else Modifier
        Box(
            Modifier
                .size(size)
                .then(ringModifier)
                .clip(CircleShape)
                .background(brush),
            contentAlignment = Alignment.Center,
        ) {
            if (group && imageUrl == null) {
                Icon(Icons.Rounded.Groups, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.5f))
            } else {
                Text(
                    initials,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = (size.value * 0.36f).sp,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (!imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(size).clip(CircleShape),
                )
            }
        }
        PresenceDot(online, size, Modifier.align(Alignment.BottomEnd))
    }
}

@Composable
private fun PresenceDot(online: Boolean, size: Dp, modifier: Modifier) {
    val dot = (size * 0.28f).coerceIn(10.dp, 16.dp)
    val reduced = LocalReducedMotion.current
    AnimatedVisibility(
        visible = online,
        modifier = modifier.offset(x = 1.dp, y = 1.dp),
        enter = AirdMotion.pop(reduced, 0.4f),
        exit = AirdMotion.shrink(reduced, 0.4f),
    ) {
        Box(
            Modifier
                .size(dot)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(dot - 4.dp).clip(CircleShape).background(LocalAirdColors.current.online))
        }
    }
}
