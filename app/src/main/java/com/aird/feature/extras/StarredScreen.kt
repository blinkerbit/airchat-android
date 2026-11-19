package com.aird.feature.extras

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aird.app.LocalNavigator
import com.aird.data.db.StarredEntity
import com.aird.designsystem.components.AirdLargeTopBar
import com.aird.designsystem.components.BackButton
import com.aird.designsystem.components.EmptyState
import com.aird.designsystem.components.SwipeAction
import com.aird.designsystem.components.SwipeRow
import com.aird.designsystem.theme.Spacing
import com.aird.session.Session
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StarredScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val me by Session.username.collectAsStateWithLifecycle()
    var flow by remember { mutableStateOf(flowOf(emptyList<StarredEntity>())) }
    LaunchedEffect(me) { flow = StarredRepo.all(context) }
    val items by flow.collectAsStateWithLifecycle(emptyList())
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            AirdLargeTopBar(title = "Starred messages", scrollBehavior = scroll, navigationIcon = { BackButton(onClick = onBack) })
        },
    ) { padding ->
        if (items.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.Star,
                title = "No starred messages",
                body = "Star a message in a chat to find it here.",
                modifier = Modifier.padding(padding),
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 48.dp),
            ) {
                items(items, key = { it.messageId }) { row ->
                    SwipeRow(
                        end = SwipeAction(
                            icon = Icons.Rounded.DeleteOutline,
                            label = "Unstar",
                            color = MaterialTheme.colorScheme.error,
                            onColor = MaterialTheme.colorScheme.onError,
                            onTrigger = { scope.launch { StarredRepo.unstar(context, row.messageId) } },
                        ),
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { navigator.openThread(row.conversationId) }
                                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                        ) {
                            Text(row.sender, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                row.body.ifBlank { "Attachment" },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
