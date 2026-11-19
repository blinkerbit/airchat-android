package com.aird.app

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.navigation.NavDestination.Companion.hasRoute
import com.aird.AppLock
import com.aird.MainActivity
import com.aird.chat.data.ChatRepository
import com.aird.chat.ui.AirdPollsScreen
import com.aird.chat.ui.ChatViewModel
import com.aird.chat.ui.EnhancedSecureHub
import com.aird.chat.ui.EnhancedSecureScreen
import com.aird.chat.ui.SharedWithMeScreen
import com.aird.chat.ui.ShareToAirdSheet
import com.aird.designsystem.motion.AirdMotion
import com.aird.designsystem.motion.LocalReducedMotion
import com.aird.feature.extras.BackupScreen
import com.aird.feature.extras.ChatGalleryScreen
import com.aird.feature.extras.SessionsScreen
import com.aird.feature.extras.ShareLinksScreen
import com.aird.feature.extras.StarredScreen
import com.aird.feature.extras.StorageScreen
import com.aird.feature.settings.SettingsScreen
import com.aird.feature.thread.ChatInfoScreen
import com.aird.feature.thread.ThreadScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AirdNavHost() {
    val context = LocalContext.current
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    val reduced = LocalReducedMotion.current
    val twoPane = LocalConfiguration.current.screenWidthDp >= 840
    SideEffect { ShellState.twoPane = twoPane }
    val navigator = remember(nav) { NavActions(nav, context.applicationContext, scope) }
    val active by ChatRepository.activeId.collectAsStateWithLifecycle()
    val pending by ChatRepository.pendingOpen.collectAsStateWithLifecycle()
    val locked by AppLock.locked.collectAsStateWithLifecycle()
    val wantSearch by MainActivity.openSearch.collectAsStateWithLifecycle()
    val wantLists by MainActivity.openLists.collectAsStateWithLifecycle()
    val shared by MainActivity.sharedContent.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { EnhancedSecureHub.ensureStarted() }
    LaunchedEffect(pending, locked) {
        if (locked || pending.isEmpty()) return@LaunchedEffect
        val id = pending
        ChatRepository.consumePending()
        navigator.openThread(id)
    }
    LaunchedEffect(wantSearch, locked) {
        if (!wantSearch || locked) return@LaunchedEffect
        MainActivity.openSearch.value = false
        navigator.openTab(HomeTab.Search)
    }
    LaunchedEffect(wantLists, locked) {
        if (!wantLists || locked) return@LaunchedEffect
        MainActivity.openLists.value = false
        navigator.openTab(HomeTab.Lists)
    }

    var lastActive by remember { mutableStateOf(active) }
    LaunchedEffect(active, twoPane) {
        val top = nav.currentBackStackEntry
        if (twoPane) {
            if (top?.destination?.hasRoute<ThreadRoute>() == true) nav.popBackStack<HomeRoute>(inclusive = false)
        } else if (active.isNotEmpty() && top?.destination?.hasRoute<HomeRoute>() == true) {
            nav.navigate(ThreadRoute(active)) { launchSingleTop = true }
        }
        if (active.isEmpty() && lastActive.isNotEmpty() && top?.threadId() == lastActive) {
            nav.popBackStack<HomeRoute>(inclusive = false)
        }
        lastActive = active
    }

    CompositionLocalProvider(LocalNavigator provides navigator) {
        SharedTransitionLayout {
            CompositionLocalProvider(LocalSharedScope provides this) {
                NavHost(
                    navController = nav,
                    startDestination = HomeRoute,
                    enterTransition = { AirdMotion.push(reduced) },
                    exitTransition = { AirdMotion.pushExit(reduced) },
                    popEnterTransition = { AirdMotion.pop(reduced) },
                    popExitTransition = { AirdMotion.popExit(reduced) },
                ) {
                    composable<HomeRoute> { Route { HomeScreen(twoPane) } }
                    composable<ThreadRoute> { entry ->
                        val id = entry.toRoute<ThreadRoute>().id
                        Route { ThreadScreen(id = id, pane = false, onBack = navigator::back) }
                    }
                    composable<ChatInfoRoute> { entry ->
                        val id = entry.toRoute<ChatInfoRoute>().id
                        Route { ChatInfoScreen(id = id, onBack = navigator::back) }
                    }
                    composable<SettingsRoute> { Route { SettingsScreen(onBack = navigator::back) } }
                    composable<SharedWithMeRoute> { Route { SharedWithMeScreen(onBack = navigator::back) } }
                    composable<PollsRoute> { Route { AirdPollsScreen(onBack = navigator::back) } }
                    composable<SecureRoute> { Route { SecureHost(onBack = navigator::back) } }
                    composable<StarredRoute> { Route { StarredScreen(onBack = navigator::back) } }
                    composable<SessionsRoute> { Route { SessionsScreen(onBack = navigator::back) } }
                    composable<ShareLinksRoute> { Route { ShareLinksScreen(onBack = navigator::back) } }
                    composable<BackupRoute> { Route { BackupScreen(onBack = navigator::back) } }
                    composable<StorageRoute> { entry ->
                        val path = entry.toRoute<StorageRoute>().path
                        Route { StorageScreen(path = path, onBack = navigator::back) }
                    }
                    composable<GalleryRoute> { entry ->
                        val id = entry.toRoute<GalleryRoute>().id
                        Route { ChatGalleryScreen(conversationId = id, onBack = navigator::back) }
                    }
                }
            }
        }
        if (!locked) {
            shared?.let { content ->
                val model: ChatViewModel = viewModel()
                ShareToAirdSheet(shared = content, model = model, onDismiss = { MainActivity.sharedContent.value = null })
            }
        }
    }
}

@Composable
private fun AnimatedContentScope.Route(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalRouteScope provides this, content = content)
}

@Composable
private fun SecureHost(onBack: () -> Unit) {
    val close by EnhancedSecureHub.closeWindow.collectAsStateWithLifecycle()
    val opened = remember { close }
    fun leave() {
        EnhancedSecureHub.leaveWindow()
        onBack()
    }
    DisposableEffect(Unit) {
        MainActivity.secureChat.value = true
        onDispose { MainActivity.secureChat.value = false }
    }
    LaunchedEffect(close) { if (close != opened) onBack() }
    BackHandler { leave() }
    EnhancedSecureScreen(onBack = ::leave)
}

private fun NavBackStackEntry.threadId(): String? = when {
    destination.hasRoute<ThreadRoute>() -> toRoute<ThreadRoute>().id
    destination.hasRoute<ChatInfoRoute>() -> toRoute<ChatInfoRoute>().id
    else -> null
}

private class NavActions(
    private val nav: NavHostController,
    private val app: Context,
    private val scope: CoroutineScope,
) : AppNavigator {
    override fun back() {
        if (nav.previousBackStackEntry != null) nav.popBackStack()
    }

    override fun openThread(id: String) {
        if (id.isEmpty()) return
        ShellState.tab.value = HomeTab.Chats
        if (ShellState.twoPane) {
            nav.popBackStack<HomeRoute>(inclusive = false)
            scope.launch { ChatRepository.open(app, id) }
            return
        }
        val top = nav.currentBackStackEntry
        if (top?.destination?.hasRoute<ThreadRoute>() == true && top.toRoute<ThreadRoute>().id == id) return
        nav.navigate(ThreadRoute(id)) { popUpTo<HomeRoute>() }
    }

    override fun openChatInfo(id: String) = push(ChatInfoRoute(id))
    override fun openSettings() = push(SettingsRoute)
    override fun openSharedWithMe() = push(SharedWithMeRoute)
    override fun openPolls() = push(PollsRoute)
    override fun openSecure() = push(SecureRoute)
    override fun openStarred() = push(StarredRoute)
    override fun openSessions() = push(SessionsRoute)
    override fun openStorage(path: String) = push(StorageRoute(path))
    override fun openShareLinks() = push(ShareLinksRoute)
    override fun openBackup() = push(BackupRoute)
    override fun openGallery(id: String) = push(GalleryRoute(id))

    override fun openTab(tab: HomeTab) {
        ShellState.tab.value = tab
        nav.popBackStack<HomeRoute>(inclusive = false)
    }

    private fun push(route: Any) {
        nav.navigate(route) { launchSingleTop = true }
    }
}
