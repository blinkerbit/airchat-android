package com.aird

import android.Manifest
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Rational
import android.app.NotificationManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.aird.ui.LocalAirdReducedMotion
import com.aird.ui.ProvideAirdMotion
import androidx.core.content.ContextCompat
import com.aird.call.AirdTelecom
import com.aird.call.CallController
import com.aird.call.CallPhase
import com.aird.call.ui.CallBubble
import com.aird.call.ui.CallScreen
import com.aird.chat.data.ChatRepository
import com.aird.chat.ui.ChatHome
import com.aird.session.Session
import com.aird.theme.AirdPalettes
import com.aird.theme.ThemeStore
import com.aird.ui.LoginScreen
import com.aird.ui.theme.AirdTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private var privilegeStep = 0
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val pipActions = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                IncomingCallReceiver.ACTION_MUTE -> CallController.toggleMute()
                IncomingCallReceiver.ACTION_HANGUP -> CallController.hangUp(this@MainActivity)
                IncomingCallReceiver.ACTION_EXPAND -> {
                    CallController.expand()
                    leaveCallPip()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        AppForeground.install()
        ThemeStore.load(this)
        ContextCompat.registerReceiver(
            this,
            pipActions,
            IntentFilter().apply {
                addAction(IncomingCallReceiver.ACTION_MUTE)
                addAction(IncomingCallReceiver.ACTION_HANGUP)
                addAction(IncomingCallReceiver.ACTION_EXPAND)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { AirdRoot() }
        handleIntent(intent)
    }

    override fun onDestroy() {
        unregisterReceiver(pipActions)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        askPrivileges()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val call = CallController.snapshot.value
        if (call.phase == CallPhase.Live && call.mode == "video") enterCallPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip.value = isInPictureInPictureMode
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) updatePipParams()
    }

    fun enterCallPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || isInPictureInPictureMode) return
        val call = CallController.snapshot.value
        if (call.phase != CallPhase.Live && call.phase != CallPhase.Outgoing && call.phase != CallPhase.Joining) return
        CallController.expand()
        enterPictureInPictureMode(pipParams())
    }

    private fun pipParams(): PictureInPictureParams {
        val portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val ratio = if (portrait) Rational(9, 16) else Rational(16, 9)
        val builder = PictureInPictureParams.Builder().setAspectRatio(ratio).setActions(
            listOf(
                pipAction(IncomingCallReceiver.ACTION_MUTE, "Mute", android.R.drawable.ic_lock_silent_mode),
                pipAction(IncomingCallReceiver.ACTION_HANGUP, "End", android.R.drawable.ic_menu_close_clear_cancel),
                pipAction(IncomingCallReceiver.ACTION_EXPAND, "Maximize", android.R.drawable.ic_menu_crop),
            ),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val call = CallController.snapshot.value
            builder.setAutoEnterEnabled(call.phase == CallPhase.Live && call.mode == "video")
        }
        return builder.build()
    }

    private fun pipAction(action: String, title: String, icon: Int): RemoteAction {
        val pending = PendingIntent.getBroadcast(
            this,
            action.hashCode(),
            Intent(action).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return RemoteAction(Icon.createWithResource(this, icon), title, title, pending)
    }

    fun updatePipParams() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        setPictureInPictureParams(pipParams())
    }

    fun leaveCallPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !isInPictureInPictureMode) return
        val back = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(back)
    }

    private fun askPrivileges() {
        if (privilegeStep == 0 && Build.VERSION.SDK_INT >= 34) {
            val manager = getSystemService(NotificationManager::class.java)
            if (!manager.canUseFullScreenIntent()) {
                privilegeStep = 1
                startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
                return
            }
        }
        if (privilegeStep < 2) {
            privilegeStep = 2
            val power = getSystemService(PowerManager::class.java)
            val prefs = getSharedPreferences("aird", MODE_PRIVATE)
            if (!power.isIgnoringBatteryOptimizations(packageName) && !prefs.getBoolean("battery_prompted", false)) {
                prefs.edit().putBoolean("battery_prompted", true).apply()
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
        }
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.getStringExtra("action")) {
            "open_chat" -> ChatRepository.requestOpen(intent.getStringExtra("conversation_id").orEmpty())
            "accept_call" -> {
                CallController.restoreIncoming(
                    intent.getStringExtra("conversation_id").orEmpty(),
                    intent.getStringExtra("call_id").orEmpty(),
                    intent.getStringExtra("call_mode").orEmpty(),
                    intent.getStringExtra("call_from").orEmpty(),
                )
                CallController.accept(this)
            }
            "incoming_call" -> {
                CallController.restoreIncoming(
                    intent.getStringExtra("conversation_id").orEmpty(),
                    intent.getStringExtra("call_id").orEmpty(),
                    intent.getStringExtra("call_mode").orEmpty(),
                    intent.getStringExtra("call_from").orEmpty(),
                )
            }
        }
    }

    companion object {
        val inPip = MutableStateFlow(false)
    }
}

@Composable
private fun AirdRoot() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val signedIn by Session.signedIn.collectAsState()
    val themeId by ThemeStore.id.collectAsState()
    val call by CallController.snapshot.collectAsState()
    val pip by MainActivity.inPip.collectAsState()
    val closePip by CallController.pipClose.collectAsState()
    LaunchedEffect(closePip) {
        if (closePip == 0) return@LaunchedEffect
        (context as? MainActivity)?.leaveCallPip()
    }
    LaunchedEffect(call.phase, call.mode) {
        val activity = context as? MainActivity ?: return@LaunchedEffect
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.isInPictureInPictureMode) {
            activity.updatePipParams()
        }
    }
    LaunchedEffect(Unit) { Session.probe(context) }
    LaunchedEffect(signedIn) {
        if (!signedIn) return@LaunchedEffect
        AirdConnectionService.start(context)
        AirdTelecom.register(context)
        ChatRepository.prepare(context)
        ChatRepository.refresh(context)
    }
    ProvideAirdMotion {
        AirdTheme(AirdPalettes.byId(themeId)) {
            val reduced = LocalAirdReducedMotion.current
            val appear = if (reduced) EnterTransition.None else fadeIn(tween(220))
            Box(Modifier.fillMaxSize()) {
                if (signedIn) ChatHome() else LoginScreen(onSignedIn = {})
                val outcome = call.phase == CallPhase.Idle && call.status.isNotEmpty()
                val showCall = outcome || pip || (call.phase != CallPhase.Idle && !call.minimized)
                val showBubble = call.minimized && call.mode == "video" && call.phase != CallPhase.Idle && !pip
                AnimatedVisibility(
                    visible = showCall,
                    modifier = Modifier.fillMaxSize(),
                    enter = appear,
                    exit = ExitTransition.None,
                ) { CallScreen() }
                AnimatedVisibility(
                    visible = showBubble,
                    modifier = Modifier.fillMaxSize(),
                    enter = appear,
                    exit = ExitTransition.None,
                ) { CallBubble() }
            }
        }
    }
}
