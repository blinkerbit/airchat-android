package com.aird.feature.extras

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aird.data.db.BackupStatus
import com.aird.data.db.LocalStore
import com.aird.designsystem.components.AirdLargeTopBar
import com.aird.designsystem.components.BackButton
import com.aird.designsystem.components.SectionHeader
import com.aird.designsystem.components.SegmentedGroup
import com.aird.designsystem.components.SettingRow
import com.aird.designsystem.components.SwitchRow
import com.aird.designsystem.theme.Spacing
import com.aird.session.Session
import com.aird.transfer.CameraBackup
import kotlinx.coroutines.flow.flowOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { CameraBackup.Prefs(context) }
    val me by Session.username.collectAsStateWithLifecycle()
    var enabled by remember { mutableStateOf(prefs.enabled) }
    var wifiOnly by remember { mutableStateOf(prefs.wifiOnly) }
    var chargingOnly by remember { mutableStateOf(prefs.chargingOnly) }
    var folder by remember { mutableStateOf(prefs.folder) }
    var photos by remember { mutableStateOf(prefs.photos) }
    var videos by remember { mutableStateOf(prefs.videos) }
    var pendingFlow by remember { mutableStateOf(flowOf(0)) }
    var doneFlow by remember { mutableStateOf(flowOf(0)) }
    var failedFlow by remember { mutableStateOf(flowOf(0)) }
    LaunchedEffect(me) {
        val db = LocalStore.db(context, me)
        if (db == null) {
            pendingFlow = flowOf(0)
            doneFlow = flowOf(0)
            failedFlow = flowOf(0)
        } else {
            pendingFlow = db.backup().countFlow(BackupStatus.PENDING)
            doneFlow = db.backup().countFlow(BackupStatus.DONE)
            failedFlow = db.backup().countFlow(BackupStatus.FAILED)
        }
    }
    val pending by pendingFlow.collectAsStateWithLifecycle(0)
    val done by doneFlow.collectAsStateWithLifecycle(0)
    val failed by failedFlow.collectAsStateWithLifecycle(0)
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    fun persist(schedule: Boolean = true) {
        prefs.enabled = enabled
        prefs.wifiOnly = wifiOnly
        prefs.chargingOnly = chargingOnly
        prefs.folder = folder
        prefs.photos = photos
        prefs.videos = videos
        if (!schedule) return
        if (enabled) CameraBackup.schedule(context) else CameraBackup.cancel(context)
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            AirdLargeTopBar(title = "Camera backup", scrollBehavior = scroll, navigationIcon = { BackButton(onClick = onBack) })
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 48.dp),
        ) {
            item {
                SectionHeader("Status")
                SegmentedGroup {
                    SettingRow(title = "Pending", subtitle = "$pending", icon = Icons.Rounded.CloudUpload)
                    SettingRow(title = "Uploaded", subtitle = "$done", icon = Icons.Rounded.Backup)
                    SettingRow(title = "Failed", subtitle = "$failed", icon = Icons.Rounded.CloudUpload, destructive = failed > 0)
                }
            }
            item {
                SectionHeader("Settings")
                SegmentedGroup {
                    SwitchRow(
                        title = "Enable camera backup",
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            persist()
                        },
                        icon = Icons.Rounded.Backup,
                    )
                    SwitchRow(
                        title = "Wi‑Fi only",
                        checked = wifiOnly,
                        onCheckedChange = {
                            wifiOnly = it
                            persist()
                        },
                        icon = Icons.Rounded.Wifi,
                    )
                    SwitchRow(
                        title = "Charging only",
                        checked = chargingOnly,
                        onCheckedChange = {
                            chargingOnly = it
                            persist()
                        },
                        icon = Icons.Rounded.BatteryChargingFull,
                    )
                    SwitchRow(
                        title = "Photos",
                        checked = photos,
                        onCheckedChange = {
                            photos = it
                            persist()
                        },
                        icon = Icons.Rounded.Photo,
                    )
                    SwitchRow(
                        title = "Videos",
                        checked = videos,
                        onCheckedChange = {
                            videos = it
                            persist()
                        },
                        icon = Icons.Rounded.Videocam,
                    )
                }
            }
            item {
                SectionHeader("Destination")
                OutlinedTextField(
                    value = folder,
                    onValueChange = { folder = it },
                    label = { Text("Target folder path") },
                    leadingIcon = { Icon(Icons.Rounded.Folder, null) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
                    singleLine = true,
                )
                Button(
                    onClick = {
                        persist(schedule = false)
                        CameraBackup.schedule(context, immediate = true)
                    },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth().padding(Spacing.lg),
                ) { Text("Back up now") }
            }
        }
    }
}
