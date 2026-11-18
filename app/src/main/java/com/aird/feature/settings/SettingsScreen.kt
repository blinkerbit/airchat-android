package com.aird.feature.settings

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhoneInTalk
import androidx.compose.material.icons.rounded.Poll
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.FolderShared
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aird.ServerStore
import com.aird.app.HomeTab
import com.aird.app.LocalNavigator
import com.aird.chat.ui.AppSettingsSheet
import com.aird.chat.ui.EnhancedSecureHub
import com.aird.chat.ui.KeyBackupSheet
import com.aird.designsystem.components.AirdLargeTopBar
import com.aird.designsystem.components.Avatar
import com.aird.designsystem.components.BackButton
import com.aird.designsystem.components.SectionHeader
import com.aird.designsystem.components.SegmentedGroup
import com.aird.designsystem.components.SettingRow
import com.aird.designsystem.theme.LocalAirdColors
import com.aird.designsystem.theme.Spacing
import com.aird.feature.chats.toStatus
import com.aird.designsystem.components.ConnectionStatus
import com.aird.session.AirdSocket
import com.aird.session.Session

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val me by Session.username.collectAsStateWithLifecycle()
    val link by AirdSocket.link.collectAsStateWithLifecycle()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var appearance by remember { mutableStateOf(false) }
    var keys by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumes++ }
    val access = remember(resumes) { DeviceAccess.read(context) }
    val server = remember { ServerStore.get(context).removePrefix("https://").removePrefix("http://").trimEnd('/') }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            AirdLargeTopBar(title = "Settings", scrollBehavior = scroll, navigationIcon = { BackButton(onClick = onBack) })
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 48.dp)) {
            item(key = "profile") {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                ) {
                    Row(Modifier.padding(Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(me, size = 64.dp, online = link == AirdSocket.Link.Online)
                        Spacer(Modifier.width(Spacing.lg))
                        Column(Modifier.weight(1f)) {
                            Text(me, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(server, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                when (link.toStatus()) {
                                    ConnectionStatus.Online -> "Connected"
                                    ConnectionStatus.Connecting -> "Connecting…"
                                    ConnectionStatus.Offline -> "Offline"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = if (link == AirdSocket.Link.Online) LocalAirdColors.current.online else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            item(key = "general") {
                SectionHeader("General")
                SegmentedGroup {
                    SettingRow(
                        title = "Appearance & chats",
                        subtitle = "Theme, colors, emoji, account security",
                        icon = Icons.Rounded.Palette,
                        chevron = true,
                        onClick = { appearance = true },
                    )
                    SettingRow(
                        title = "Notifications",
                        subtitle = if (access.notifications) "On" else "Off · new messages won't alert you",
                        icon = Icons.Rounded.Notifications,
                        chevron = true,
                        onClick = { DeviceAccess.openNotifications(context) },
                    )
                }
            }
            if (!access.fullScreen || !access.unrestricted) {
                item(key = "reliability") {
                    SectionHeader("Reliability")
                    SegmentedGroup {
                        if (!access.fullScreen) {
                            SettingRow(
                                title = "Ring on the lock screen",
                                subtitle = "Allow full-screen alerts so calls can ring while the phone is locked",
                                icon = Icons.Rounded.PhoneInTalk,
                                iconContainer = MaterialTheme.colorScheme.tertiaryContainer,
                                iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
                                chevron = true,
                                onClick = { DeviceAccess.openFullScreen(context) },
                            )
                        }
                        if (!access.unrestricted) {
                            SettingRow(
                                title = "Deliver in the background",
                                subtitle = "Let Aird stay connected so messages and calls arrive instantly",
                                icon = Icons.Rounded.BatteryChargingFull,
                                iconContainer = MaterialTheme.colorScheme.tertiaryContainer,
                                iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
                                chevron = true,
                                onClick = { DeviceAccess.openBattery(context) },
                            )
                        }
                    }
                }
            }
            item(key = "privacy") {
                SectionHeader("Privacy & security")
                SegmentedGroup {
                    SettingRow(
                        title = "Key backup",
                        subtitle = "Restore your encrypted history on a new device",
                        icon = Icons.Rounded.Key,
                        chevron = true,
                        onClick = { keys = true },
                    )
                    SettingRow(
                        title = "Enhanced secure chat",
                        subtitle = "Ephemeral sessions that are never stored",
                        icon = Icons.Rounded.Lock,
                        chevron = true,
                        onClick = {
                            EnhancedSecureHub.reveal()
                            EnhancedSecureHub.ensureStarted()
                            navigator.openSecure()
                        },
                    )
                }
            }
            item(key = "spaces") {
                SectionHeader("Your spaces")
                SegmentedGroup {
                    SettingRow(
                        title = "Shared with me",
                        icon = Icons.Rounded.FolderShared,
                        chevron = true,
                        onClick = navigator::openSharedWithMe,
                    )
                    SettingRow(
                        title = "Polls",
                        icon = Icons.Rounded.Poll,
                        chevron = true,
                        onClick = navigator::openPolls,
                    )
                    SettingRow(
                        title = "Lists",
                        icon = Icons.AutoMirrored.Rounded.ListAlt,
                        chevron = true,
                        onClick = { navigator.openTab(HomeTab.Lists) },
                    )
                }
            }
            item(key = "about") {
                SectionHeader("About")
                SegmentedGroup {
                    SettingRow(
                        title = "Aird for Android",
                        subtitle = "Version ${DeviceAccess.version(context)}",
                        icon = Icons.Rounded.Info,
                    )
                }
                Spacer(Modifier.height(Spacing.xl))
                SegmentedGroup {
                    SettingRow(
                        title = "Sign out",
                        icon = Icons.AutoMirrored.Rounded.Logout,
                        destructive = true,
                        onClick = { confirmSignOut = true },
                    )
                }
            }
        }
    }

    if (appearance) AppSettingsSheet(onDismiss = { appearance = false })
    if (keys) KeyBackupSheet(onDismiss = { keys = false })
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            icon = { Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null) },
            title = { Text("Sign out of Aird?") },
            text = { Text("Saved messages on this device are removed. Back up your keys first if you want to read encrypted history later.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    Session.signOut(context)
                }) { Text("Sign out", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }
}

data class DeviceAccess(val notifications: Boolean, val fullScreen: Boolean, val unrestricted: Boolean) {
    companion object {
        fun read(context: Context): DeviceAccess {
            val manager = context.getSystemService(NotificationManager::class.java)
            val power = context.getSystemService(PowerManager::class.java)
            return DeviceAccess(
                notifications = manager?.areNotificationsEnabled() != false,
                fullScreen = Build.VERSION.SDK_INT < 34 || manager?.canUseFullScreenIntent() != false,
                unrestricted = power?.isIgnoringBatteryOptimizations(context.packageName) != false,
            )
        }

        fun openNotifications(context: Context) = launch(
            context,
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        )

        fun openFullScreen(context: Context) {
            if (Build.VERSION.SDK_INT < 34) return
            launch(context, Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}")))
        }

        @android.annotation.SuppressLint("BatteryLife")
        fun openBattery(context: Context) = launch(
            context,
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
        )

        fun version(context: Context): String = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        } catch (_: Exception) {
            ""
        }

        private fun launch(context: Context, intent: Intent) {
            try {
                context.startActivity(intent)
            } catch (_: Exception) {
                try {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                } catch (_: Exception) {
                }
            }
        }
    }
}
