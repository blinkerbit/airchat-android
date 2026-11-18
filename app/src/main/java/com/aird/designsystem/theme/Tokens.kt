package com.aird.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

object Spacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
    val screen = 16.dp
    val touch = 48.dp
}

object Radius {
    val xs = 6.dp
    val sm = 10.dp
    val md = 14.dp
    val lg = 20.dp
    val xl = 28.dp
}

val AirdShapes = Shapes(
    extraSmall = RoundedCornerShape(Radius.xs),
    small = RoundedCornerShape(Radius.sm),
    medium = RoundedCornerShape(Radius.md),
    large = RoundedCornerShape(Radius.lg),
    extraLarge = RoundedCornerShape(Radius.xl),
)

object BubbleShapes {
    private val outer = 20.dp
    private val inner = 6.dp

    private val cache = HashMap<Int, RoundedCornerShape>()

    fun of(mine: Boolean, first: Boolean, last: Boolean): RoundedCornerShape {
        val key = (if (mine) 1 else 0) or (if (first) 2 else 0) or (if (last) 4 else 0)
        return cache.getOrPut(key) {
            val top = if (first) outer else inner
            val bottom = if (last) outer else inner
            if (mine) {
                RoundedCornerShape(topStart = outer, topEnd = top, bottomEnd = bottom, bottomStart = outer)
            } else {
                RoundedCornerShape(topStart = top, topEnd = outer, bottomEnd = outer, bottomStart = bottom)
            }
        }
    }
}

object SegmentShapes {
    private val outer = 20.dp
    private val inner = 4.dp

    fun of(index: Int, count: Int): RoundedCornerShape {
        val first = index == 0
        val last = index == count - 1
        return RoundedCornerShape(
            topStart = if (first) outer else inner,
            topEnd = if (first) outer else inner,
            bottomStart = if (last) outer else inner,
            bottomEnd = if (last) outer else inner,
        )
    }
}
