package com.aird.designsystem.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aird.designsystem.motion.AirdMotion
import com.aird.designsystem.motion.LocalReducedMotion
import com.aird.designsystem.theme.Spacing

@Composable
fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    autofocus: Boolean = false,
    height: Dp = 52.dp,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    leading: ImageVector = Icons.Rounded.Search,
    onSearch: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val focus = LocalFocusManager.current
    val requester = remember { FocusRequester() }
    val reduced = LocalReducedMotion.current
    if (autofocus) {
        LaunchedEffect(Unit) {
            try {
                requester.requestFocus()
            } catch (_: IllegalStateException) {
            }
        }
    }
    Surface(shape = CircleShape, color = container, modifier = modifier.fillMaxWidth().height(height)) {
        Row(Modifier.padding(start = Spacing.lg, end = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            Icon(leading, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(Spacing.md))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text(
                        placeholder,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        onSearch?.invoke()
                        focus.clearFocus()
                    }),
                    modifier = Modifier.fillMaxWidth().focusRequester(requester),
                )
            }
            AnimatedVisibility(
                visible = query.isNotEmpty(),
                enter = AirdMotion.pop(reduced, 0.6f),
                exit = AirdMotion.shrink(reduced, 0.6f),
            ) {
                BounceIconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Rounded.Close, contentDescription = "Clear search")
                }
            }
            if (trailing != null) trailing()
            if (trailing == null && query.isEmpty()) Spacer(Modifier.width(Spacing.sm))
        }
    }
}
