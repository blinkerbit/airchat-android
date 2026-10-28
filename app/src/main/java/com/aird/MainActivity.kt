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

data class SharedContent(
    val text: String = "",
    val uris: List<Uri> = emptyList(),
)

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
                IncomingCallReceiver.ACTION_HIDE -> hideCallToBackground()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setupLockscreenFlags()
        AppForeground.install()
        ThemeStore.load(this)
        ContextCompat.registerReceiver(
            this,
            pipActions,
            IntentFilter().apply {
                addAction(IncomingCallReceiver.ACTION_MUTE)
                addAction(IncomingCallReceiver.ACTION_HANGUP)
                addAction(IncomingCallReceiver.ACTION_EXPAND)
                addAction(IncomingCallReceiver.ACTION_HIDE)
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
        setupLockscreenFlags()
        askPrivileges()
        com.aird.session.AirdSocket.ensureConnected()
        val call = CallController.snapshot.value
        if (call.phase == CallPhase.Incoming) {
            AirdConnectionService.showRinging(
                this,
                conversationId = call.conversationId,
                callId = call.callId,
                peer = call.peer,
                mode = call.mode,
                alert = false,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        setupLockscreenFlags()
        handleIntent(intent)
    }

    fun setupLockscreenFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
            keyguardManager?.requestDismissKeyguard(this, null)
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val call = CallController.snapshot.value
        val active = call.phase == CallPhase.Live || call.phase == CallPhase.Outgoing || call.phase == CallPhase.Joining
        if (active && !call.minimized) enterCallPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip.value = isInPictureInPictureMode
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) updatePipParams()
    }

    fun enterCallPip(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        if (isInPictureInPictureMode) return true
        val call = CallController.snapshot.value
        if (call.phase != CallPhase.Live && call.phase != CallPhase.Outgoing && call.phase != CallPhase.Joining) return false
        CallController.expand()
        return try {
            enterPictureInPictureMode(pipParams())
        } catch (_: IllegalStateException) {
            false
        }
    }

    fun hideCallToBackground() {
        CallController.hideBackground()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) updatePipParams()
        moveTaskToBack(true)
    }

    private fun pipParams(): PictureInPictureParams {
        val call = CallController.snapshot.value
        val portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val ratio = if (call.mode == "video" && portrait) Rational(9, 16) else Rational(16, 9)
        val actions = mutableListOf(
            pipAction(IncomingCallReceiver.ACTION_EXPAND, "Maximize", android.R.drawable.ic_menu_crop),
            pipAction(IncomingCallReceiver.ACTION_HIDE, "Hide", android.R.drawable.arrow_down_float),
            pipAction(IncomingCallReceiver.ACTION_HANGUP, "End", android.R.drawable.ic_menu_close_clear_cancel),
        )
        val limit = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) maxNumPictureInPictureActions else actions.size
        if (limit > 3) actions.add(1, pipAction(IncomingCallReceiver.ACTION_MUTE, "Mute", android.R.drawable.ic_lock_silent_mode))
        val builder = PictureInPictureParams.Builder().setAspectRatio(ratio).setActions(actions.take(limit.coerceAtLeast(1)))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val active = call.phase == CallPhase.Live || call.phase == CallPhase.Outgoing || call.phase == CallPhase.Joining
            builder.setAutoEnterEnabled(active && !call.minimized)
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
        val action = intent?.action
        if (action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE) {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            val uris = mutableListOf<Uri>()
            if (action == Intent.ACTION_SEND) {
                @Suppress("DEPRECATION")
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (uri != null) uris.add(uri)
            } else {
                @Suppress("DEPRECATION")
                val list = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                if (list != null) uris.addAll(list.filterNotNull())
            }
            if (sharedText.isNotEmpty() || uris.isNotEmpty()) {
                sharedContent.value = SharedContent(text = sharedText, uris = uris)
            }
        }
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
            "expand_call" -> {
                CallController.expand()
                leaveCallPip()
            }
            "incoming_call" -> {
                setupLockscreenFlags()
                val conversationId = intent.getStringExtra("conversation_id").orEmpty()
                val callId = intent.getStringExtra("call_id").orEmpty()
                val mode = intent.getStringExtra("call_mode").orEmpty()
                val from = intent.getStringExtra("call_from").orEmpty()
                CallController.restoreIncoming(conversationId, callId, mode, from)
                AirdConnectionService.showRinging(
                    this,
                    conversationId = conversationId,
                    callId = callId,
                    peer = from,
                    mode = mode,
                    alert = false,
                )
            }
        }
    }

    companion object {
        val inPip = MutableStateFlow(false)
        val sharedContent = MutableStateFlow<SharedContent?>(null)
    }
}

@Composable
private fun AirdRoot() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val signedIn by Session.signedIn.collectAsState()
    val themeId by ThemeStore.id.collectAsState()
    val call by CallController.snapshot.collectAsState()
    val pip by MainActivity.inPip.collectAsState()
    val backgroundOnly by CallController.backgroundOnly.collectAsState()
    val closePip by CallController.pipClose.collectAsState()
    LaunchedEffect(closePip) {
        if (closePip == 0) return@LaunchedEffect
        (context as? MainActivity)?.leaveCallPip()
    }
    LaunchedEffect(call.phase, call.mode, call.minimized) {
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
            val appear = if (reduced || call.phase == CallPhase.Incoming) EnterTransition.None else fadeIn(tween(220))
            Box(Modifier.fillMaxSize()) {
                if (signedIn) ChatHome() else LoginScreen(onSignedIn = {})
                val outcome = call.phase == CallPhase.Idle && call.status.isNotEmpty()
                val showCall = outcome || pip || (call.phase != CallPhase.Idle && !call.minimized)
                val showBubble = call.minimized && call.mode == "video" && call.phase != CallPhase.Idle && !pip && !backgroundOnly
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
