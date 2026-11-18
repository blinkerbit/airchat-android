package com.aird.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aird.chat.data.ChatRepository
import com.aird.chat.ui.AirdFilesScreen
import com.aird.chat.ui.AirdListsScreen
import com.aird.chat.ui.AirdSuperSearchScreen
import com.aird.designsystem.components.EmptyState
import com.aird.designsystem.components.badgeLabel
import com.aird.designsystem.haptics.LocalAirdHaptics
import com.aird.designsystem.motion.AirdMotion
import com.aird.designsystem.motion.LocalReducedMotion
import com.aird.feature.chats.ChatsScreen
import com.aird.feature.thread.ThreadScreen

@Composable
fun HomeScreen(twoPane: Boolean) {
    val tab by ShellState.tab.collectAsStateWithLifecycle()
    val conversations by ChatRepository.conversations.collectAsStateWithLifecycle()
    val unread = remember(conversations) { conversations.sumOf { if (it.muted) 0 else it.unread } }
    val haptics = LocalAirdHaptics.current
    val reduced = LocalReducedMotion.current
    val navigator = LocalNavigator.current
    val holder = rememberSaveableStateHolder()

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            HomeTab.entries.forEach { item ->
                val selected = item == tab
                item(
                    selected = selected,
                    onClick = {
                        if (!selected) haptics.tick()
                        ShellState.tab.value = item
                    },
                    icon = {
                        BadgedBox(badge = {
                            if (item == HomeTab.Chats && unread > 0) Badge { Text(badgeLabel(unread)) }
                        }) {
                            Icon(if (selected) item.selected else item.idle, contentDescription = null)
                        }
                    },
                    label = { Text(item.label) },
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        AnimatedContent(
            targetState = tab,
            transitionSpec = { AirdMotion.through(reduced) },
            label = "homeTab",
        ) { current ->
            holder.SaveableStateProvider(current.name) {
                when (current) {
                    HomeTab.Chats -> ChatsTab(twoPane)
                    HomeTab.Files -> {
                        val path by ShellState.filesPath.collectAsStateWithLifecycle()
                        key(path) {
                            AirdFilesScreen(startPath = path, onBack = { navigator.openTab(HomeTab.Chats) })
                        }
                    }
                    HomeTab.Lists -> AirdListsScreen(onBack = { navigator.openTab(HomeTab.Chats) })
                    HomeTab.Search -> AirdSuperSearchScreen(
                        startPath = "",
                        onBack = { navigator.openTab(HomeTab.Chats) },
                        onOpenPath = { ShellState.openFiles(it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatsTab(twoPane: Boolean) {
    val navigator = LocalNavigator.current
    val reduced = LocalReducedMotion.current
    val active by ChatRepository.activeId.collectAsStateWithLifecycle()
    if (!twoPane) {
        ChatsScreen(selectedId = "", onOpen = navigator::openThread)
        return
    }
    Row(Modifier.fillMaxSize()) {
        ChatsScreen(
            selectedId = active,
            onOpen = navigator::openThread,
            modifier = Modifier.width(380.dp).fillMaxHeight(),
        )
        VerticalDivider()
        Box(Modifier.weight(1f).fillMaxHeight().background(MaterialTheme.colorScheme.background)) {
            AnimatedContent(
                targetState = active,
                transitionSpec = { AirdMotion.through(reduced) },
                label = "pane",
            ) { id ->
                if (id.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        EmptyState(
                            icon = Icons.AutoMirrored.Rounded.Chat,
                            title = "Pick a chat",
                            body = "Choose a conversation on the left, or start a new one.",
                        )
                    }
                } else {
                    ThreadScreen(id = id, pane = true, onBack = { ChatRepository.close() })
                }
            }
        }
    }
}
