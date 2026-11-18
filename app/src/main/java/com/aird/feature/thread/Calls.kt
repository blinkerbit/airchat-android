package com.aird.feature.thread

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.aird.call.CallController

/** Starts a call, asking just in time for the permissions that call needs. */
@Composable
fun rememberCallLauncher(conversationId: String, peer: String): (video: Boolean) -> Unit {
    val context = LocalContext.current
    val id by rememberUpdatedState(conversationId)
    val name by rememberUpdatedState(peer)
    var pending by remember { mutableStateOf("audio") }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val essentials = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (pending == "video") add(Manifest.permission.CAMERA)
        }
        if (essentials.all { granted[it] != false && ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) {
            CallController.start(context, id, name, pending)
        }
    }
    return remember(launcher) {
        { video ->
            val mode = if (video) "video" else "audio"
            pending = mode
            val needs = buildList {
                add(Manifest.permission.RECORD_AUDIO)
                if (video) add(Manifest.permission.CAMERA)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
            }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
            if (needs.isEmpty()) CallController.start(context, id, name, mode) else launcher.launch(needs.toTypedArray())
        }
    }
}
