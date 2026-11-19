package com.aird.feature.extras

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.aird.designsystem.theme.Spacing

/** Horizontal favorites strip for Files. Reloads when [reloadKey] changes. */
@Composable
fun FavoritesStrip(
    modifier: Modifier = Modifier,
    reloadKey: Any = Unit,
    onOpen: (FavoritePath) -> Unit,
) {
    val context = LocalContext.current
    var items by remember { mutableStateOf<List<FavoritePath>>(emptyList()) }
    LaunchedEffect(reloadKey) {
        items = FavoritesStore.list(context)
    }
    if (items.isEmpty()) return
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        items.forEach { fav ->
            AssistChip(
                onClick = { onOpen(fav) },
                label = { Text(fav.name) },
                leadingIcon = { Icon(Icons.Rounded.Star, contentDescription = null) },
            )
        }
    }
}
