package com.aird.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable

@Serializable data object HomeRoute
@Serializable data class ThreadRoute(val id: String)
@Serializable data class ChatInfoRoute(val id: String)
@Serializable data object SettingsRoute
@Serializable data object SharedWithMeRoute
@Serializable data object PollsRoute
@Serializable data object SecureRoute
@Serializable data object StarredRoute
@Serializable data object SessionsRoute
@Serializable data class StorageRoute(val path: String = "")
@Serializable data object ShareLinksRoute
@Serializable data object BackupRoute
@Serializable data class GalleryRoute(val id: String)

enum class HomeTab(val label: String, val selected: ImageVector, val idle: ImageVector) {
    Chats("Chats", Icons.AutoMirrored.Rounded.Chat, Icons.AutoMirrored.Outlined.Chat),
    Files("Files", Icons.Rounded.Folder, Icons.Outlined.Folder),
    Lists("Lists", Icons.AutoMirrored.Rounded.ListAlt, Icons.AutoMirrored.Outlined.ListAlt),
    Search("Search", Icons.Rounded.Search, Icons.Outlined.Search),
}

/** UI-wide state that outlives a single screen. */
object ShellState {
    val tab = MutableStateFlow(HomeTab.Chats)
    val filesPath = MutableStateFlow("")
    val searchQuery = MutableStateFlow("")

    @Volatile var twoPane = false

    fun openFiles(path: String) {
        filesPath.value = path
        tab.value = HomeTab.Files
    }
}

/** Navigation actions any screen can call without knowing the back stack. */
interface AppNavigator {
    fun back()
    fun openThread(id: String)
    fun openChatInfo(id: String)
    fun openSettings()
    fun openSharedWithMe()
    fun openPolls()
    fun openSecure()
    fun openStarred()
    fun openSessions()
    fun openStorage(path: String = "")
    fun openShareLinks()
    fun openBackup()
    fun openGallery(id: String)
    fun openTab(tab: HomeTab)
}

val LocalNavigator = staticCompositionLocalOf<AppNavigator> { error("No navigator") }
