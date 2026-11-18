package com.aird.designsystem.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aird.designsystem.theme.Spacing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class AirdSheetScope internal constructor(
    private val hide: suspend () -> Unit,
    private val visible: () -> Boolean,
    private val scope: CoroutineScope,
    private val onDismiss: () -> Unit,
    column: ColumnScope,
) : ColumnScope by column {
    /** Runs [then] right away and animates the sheet out. */
    fun dismiss(then: () -> Unit = {}) {
        then()
        scope.launch { hide() }.invokeOnCompletion { if (!visible()) onDismiss() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun rememberAirdSheetState(skipPartiallyExpanded: Boolean): SheetState =
    @Suppress("DEPRECATION")
    rememberModalBottomSheetState(skipPartiallyExpanded = skipPartiallyExpanded)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AirdSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    skipPartiallyExpanded: Boolean = true,
    content: @Composable AirdSheetScope.() -> Unit,
) {
    val state = rememberAirdSheetState(skipPartiallyExpanded)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = state,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = Spacing.md)) {
            val sheetScope = remember(state, scope, onDismiss, this) {
                AirdSheetScope({ state.hide() }, { state.isVisible }, scope, onDismiss, this)
            }
            sheetScope.content()
        }
    }
}

@Composable
fun SheetAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    destructive: Boolean = false,
    enabled: Boolean = true,
) {
    val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.xxl, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Spacer(Modifier.width(Spacing.xl))
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!supporting.isNullOrBlank()) {
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
