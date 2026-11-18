package com.aird

import android.annotation.SuppressLint
import android.app.Activity
import android.app.KeyguardManager
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.aird.app.AirdRoot
import com.aird.app.Startup
import com.aird.call.CallController
import com.aird.call.CallPhase
import com.aird.chat.data.ChatRepository
import com.aird.chat.ui.EnhancedSecureHub
import kotlinx.coroutines.flow.MutableStateFlow

data class SharedContent(
    val text: String = "",
    val uris: List<Uri> = emptyList(),
)

class MainActivity : ComponentActivity() {
    private var lockWipeGeneration = 0
    private var removeKeyguardListener: (() -> Unit)? = null
    private val credential = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        AppLock.credentialFinished(result.resultCode == Activity.RESULT_OK)
    }
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                AppLock.lockIfIdle()
                scheduleEnhancedLockWipe()
            }
        }
    }
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
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setupLockscreenFlags()
        Startup.restore(this, fresh = savedInstanceState == null)
        splash.setKeepOnScreenCondition { !Startup.ready.value }
        window.decorView.postDelayed({ Startup.markReady() }, 2_500L)
        ContextCompat.registerReceiver(
            this,
            screenOff,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        EnhancedSecureHub.attach(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) watchKeyguard()
        AppForeground.install()
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
        setContent { AirdRoot() }
        handleIntent(intent)
    }

    fun confirmDevice(intent: Intent) {
        credential.launch(intent)
    }

    override fun onDestroy() {
        removeKeyguardListener?.invoke()
        removeKeyguardListener = null
        unregisterReceiver(pipActions)
        unregisterReceiver(screenOff)
        CallProximity.set(this, false)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        val km = getSystemService(KeyguardManager::class.java)
        val stillLocked = km?.isKeyguardLocked == true || km?.isDeviceLocked == true
        if (stillLocked) EnhancedSecureHub.onDeviceLocked() else lockWipeGeneration++
        setupLockscreenFlags()
        WindowFlags.apply(this, secureChat.value, CallController.snapshot.value, isInPictureInPictureMode)
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

    private fun scheduleEnhancedLockWipe() {
        val gen = ++lockWipeGeneration
        fun check() {
            if (gen != lockWipeGeneration || isDestroyed) return
            if (deviceLockedNow()) {
                EnhancedSecureHub.onDeviceLocked()
                return
            }
            val power = getSystemService(PowerManager::class.java)
            if (power?.isInteractive == false) {
                window.decorView.postDelayed({ check() }, 500L)
            }
        }
        window.decorView.post { check() }
    }

    private fun deviceLockedNow(): Boolean {
        val km = getSystemService(KeyguardManager::class.java) ?: return false
        if (km.isKeyguardLocked || km.isDeviceLocked) return true
        val power = getSystemService(PowerManager::class.java)
        val screenOff = power?.isInteractive == false
        val callUp = CallController.snapshot.value.phase != CallPhase.Idle
        return screenOff && !km.isDeviceSecure && !callUp
    }

    @SuppressLint("NewApi")
    private fun watchKeyguard() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val km = getSystemService(KeyguardManager::class.java) ?: return
        val listener = KeyguardManager.KeyguardLockedStateListener { locked ->
            if (locked) EnhancedSecureHub.onDeviceLocked()
        }
        try {
            km.addKeyguardLockedStateListener(ContextCompat.getMainExecutor(this), listener)
            removeKeyguardListener = {
                try {
                    km.removeKeyguardLockedStateListener(listener)
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }

    fun setupLockscreenFlags() {
        val phase = CallController.snapshot.value.phase
        val overLock = phase == CallPhase.Incoming ||
            phase == CallPhase.Live ||
            phase == CallPhase.Outgoing ||
            phase == CallPhase.Joining
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(overLock)
            setTurnScreenOn(overLock)
        }
        @Suppress("DEPRECATION")
        val overlay = android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        if (overLock) window.addFlags(overlay) else window.clearFlags(overlay)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val call = CallController.snapshot.value
        val active = call.phase == CallPhase.Live || call.phase == CallPhase.Outgoing || call.phase == CallPhase.Joining
        if (active && !call.minimized) enterCallPip()
        AppLock.lockIfIdle()
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

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        if (action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE || action == Intent.ACTION_PROCESS_TEXT) {
            val sharedText = when (action) {
                Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
                else -> intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            }
            val uris = mutableListOf<Uri>()
            if (action == Intent.ACTION_SEND) {
                @Suppress("DEPRECATION")
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (uri != null) uris.add(uri)
            } else if (action == Intent.ACTION_SEND_MULTIPLE) {
                @Suppress("DEPRECATION")
                val list = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                if (list != null) uris.addAll(list.filterNotNull())
            }
            if (sharedText.isNotEmpty() || uris.isNotEmpty()) {
                sharedContent.value = SharedContent(text = sharedText, uris = uris)
            }
        }
        if (action == ACTION_OPEN_SEARCH) openSearch.value = true
        val link = intent?.data
        if (action == Intent.ACTION_VIEW && link?.scheme == "aird" && link.host == "chat") {
            val id = link.lastPathSegment.orEmpty()
            if (id.isNotEmpty()) ChatRepository.requestOpen(id)
        }
        if (InternalLaunch.trusted(this, intent)) when (intent.getStringExtra("action")) {
            "open_chat" -> ChatRepository.requestOpen(intent.getStringExtra("conversation_id").orEmpty())
            "open_lists" -> openLists.value = true
            "accept_call" -> {
                CallController.restoreIncoming(
                    this,
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
                CallController.restoreIncoming(this, conversationId, callId, mode, from)
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
        val notificationDenied = MutableStateFlow(false)
        val secureChat = MutableStateFlow(false)
        val openSearch = MutableStateFlow(false)
        val openLists = MutableStateFlow(false)
        const val ACTION_OPEN_SEARCH = "com.aird.OPEN_SEARCH"
    }
}
