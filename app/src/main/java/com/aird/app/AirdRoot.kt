package com.aird.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aird.AirdConnectionService
import com.aird.AppLock
import com.aird.MainActivity
import com.aird.WindowFlags
import com.aird.call.AirdTelecom
import com.aird.call.CallController
import com.aird.call.CallPhase
import com.aird.call.ui.CallBubble
import com.aird.call.ui.CallScreen
import com.aird.chat.data.ChatRepository
import com.aird.data.PrivacyPrefs
import com.aird.designsystem.motion.AirdMotion
import com.aird.designsystem.motion.LocalReducedMotion
import com.aird.designsystem.theme.AirdTheme
import com.aird.designsystem.theme.Spacing
import com.aird.session.PasskeyDevice
import com.aird.session.Session
import com.aird.ui.LoginScreen
import com.aird.ui.PasskeyOffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Cold start: restore the last session instantly, then confirm it with the server in the background. */
object Startup {
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    fun restore(context: Context, fresh: Boolean) {
        if (fresh && hasCookie(context)) AppLock.arm()
        if (Session.restore(context)) {
            AppLock.onSignedIn()
            _ready.value = true
        }
    }

    suspend fun confirm(context: Context) {
        val wasSignedIn = Session.signedIn.value
        val ok = Session.probe(context)
        if (!wasSignedIn) {
            if (ok) AppLock.onSignedIn() else AppLock.disarm()
        }
        _ready.value = true
    }

    fun markReady() {
        _ready.value = true
    }

    private fun hasCookie(context: Context): Boolean = try {
        !android.webkit.CookieManager.getInstance().getCookie(com.aird.ServerStore.get(context)).isNullOrBlank()
    } catch (_: Exception) {
        false
    }
}

private enum class Gate { Splash, Login, Passkey, Onboarding, App }

@Composable
fun AirdRoot() {
    val context = LocalContext.current
    val signedIn by Session.signedIn.collectAsStateWithLifecycle()
    val username by Session.username.collectAsStateWithLifecycle()
    val ready by Startup.ready.collectAsStateWithLifecycle()
    var passkeyOffer by remember { mutableStateOf(false) }
    var showOnboarding by remember { mutableStateOf(!PrivacyPrefs.onboardingDone(context)) }
    val call by CallController.snapshot.collectAsStateWithLifecycle()
    val pip by MainActivity.inPip.collectAsStateWithLifecycle()
    val notificationDenied by MainActivity.notificationDenied.collectAsStateWithLifecycle()
    val backgroundOnly by CallController.backgroundOnly.collectAsStateWithLifecycle()
    val closePip by CallController.pipClose.collectAsStateWithLifecycle()
    val micNeeded by CallController.micNeeded.collectAsStateWithLifecycle()
    val locked by AppLock.locked.collectAsStateWithLifecycle()
    val secureChat by MainActivity.secureChat.collectAsStateWithLifecycle()
    val privacy by PrivacyPrefs.settings.collectAsStateWithLifecycle()

    val micPrompt = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        CallController.microphoneAnswered(context, granted)
    }
    val notifyPrompt = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        context.getSharedPreferences("aird", Context.MODE_PRIVATE).edit().putBoolean(NOTIFICATIONS_PROMPTED, true).apply()
        if (!granted) MainActivity.notificationDenied.value = true
    }
    LaunchedEffect(micNeeded) {
        if (micNeeded) micPrompt.launch(Manifest.permission.RECORD_AUDIO)
    }
    LaunchedEffect(closePip) {
        if (closePip == 0) return@LaunchedEffect
        (context as? MainActivity)?.leaveCallPip()
    }
    LaunchedEffect(call.phase, call.mode, call.minimized) {
        val activity = context as? MainActivity ?: return@LaunchedEffect
        activity.setupLockscreenFlags()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.isInPictureInPictureMode) activity.updatePipParams()
    }
    LaunchedEffect(Unit) { Startup.confirm(context) }
    LaunchedEffect(secureChat, privacy.hideRecents, call.phase, call.mode, call.speaker, call.output, pip) {
        val activity = context as? Activity ?: return@LaunchedEffect
        WindowFlags.apply(activity, secureChat, call, pip)
    }
    LaunchedEffect(signedIn, username) {
        passkeyOffer = signedIn && username.isNotBlank() && PasskeyDevice.shouldOffer(context, username)
        if (signedIn) showOnboarding = !PrivacyPrefs.onboardingDone(context)
    }
    LaunchedEffect(signedIn) {
        if (!signedIn) return@LaunchedEffect
        AirdConnectionService.start(context)
        AirdTelecom.register(context)
        ChatRepository.warmStart(context)
        ChatRepository.prepare(context)
        ChatRepository.refresh(context, quiet = true)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !context.getSharedPreferences("aird", Context.MODE_PRIVATE).getBoolean(NOTIFICATIONS_PROMPTED, false)
        ) {
            notifyPrompt.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    AirdTheme {
        val reduced = LocalReducedMotion.current
        val gate = when {
            !signedIn && !ready -> Gate.Splash
            !signedIn -> Gate.Login
            passkeyOffer && !locked -> Gate.Passkey
            showOnboarding && !locked -> Gate.Onboarding
            else -> Gate.App
        }
        val ongoing = call.minimized && call.phase != CallPhase.Idle && !pip && (call.mode != "video" || backgroundOnly)
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Column(Modifier.fillMaxSize()) {
                AnimatedVisibility(ongoing && gate == Gate.App, enter = AirdMotion.reveal(reduced), exit = AirdMotion.hide(reduced)) {
                    OngoingCallBar(peer = call.peer, video = call.mode == "video")
                }
                Box(
                    Modifier
                        .weight(1f)
                        .then(if (ongoing && gate == Gate.App) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier),
                ) {
                    AnimatedContent(
                        targetState = gate,
                        transitionSpec = { AirdMotion.through(reduced) },
                        label = "gate",
                    ) { current ->
                        when (current) {
                            Gate.Splash -> Box(Modifier.fillMaxSize())
                            Gate.Login -> LoginScreen(onSignedIn = {})
                            Gate.Passkey -> PasskeyOffer(username) { passkeyOffer = false }
                            Gate.Onboarding -> OnboardingScreen(onFinished = { showOnboarding = false })
                            Gate.App -> AirdNavHost()
                        }
                    }
                }
            }
            AnimatedVisibility(
                visible = locked && signedIn && call.phase == CallPhase.Idle && !pip,
                enter = EnterTransition.None,
                exit = fadeOut(tween(220)),
            ) { LockGate() }
            val appear = if (reduced || call.phase == CallPhase.Incoming) EnterTransition.None else fadeIn(tween(220))
            val outcome = call.phase == CallPhase.Idle && call.status.isNotEmpty()
            val showCall = outcome || pip || (call.phase != CallPhase.Idle && !call.minimized)
            val showBubble = call.minimized && call.mode == "video" && call.phase != CallPhase.Idle && !pip && !backgroundOnly
            AnimatedVisibility(visible = showCall, modifier = Modifier.fillMaxSize(), enter = appear, exit = ExitTransition.None) { CallScreen() }
            AnimatedVisibility(visible = showBubble, modifier = Modifier.fillMaxSize(), enter = appear, exit = ExitTransition.None) { CallBubble() }
        }
        if (notificationDenied) {
            AlertDialog(
                onDismissRequest = { MainActivity.notificationDenied.value = false },
                title = { Text("Notifications are off") },
                text = { Text("New messages and calls won't alert you. You can turn them on any time in Settings.") },
                confirmButton = {
                    TextButton(onClick = { MainActivity.notificationDenied.value = false }) { Text("OK") }
                },
            )
        }
    }
}

private const val NOTIFICATIONS_PROMPTED = "notifications_prompted"

@Composable
private fun OngoingCallBar(peer: String, video: Boolean) {
    Surface(
        onClick = { CallController.expand() },
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.statusBarsPadding().padding(horizontal = Spacing.lg, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Icon(if (video) Icons.Rounded.Videocam else Icons.Rounded.Call, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(
                "${if (video) "Video" else "Voice"} call with $peer",
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text("Return", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LockGate() {
    val activity = LocalContext.current as Activity
    var nonce by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) { onDispose { AppLock.dismissPrompt() } }
    LaunchedEffect(nonce) {
        AppLock.dismissPrompt()
        AppLock.request(activity)
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(Spacing.xxxl)) {
            Box(
                Modifier.size(112.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(44.dp))
            }
            Spacer(Modifier.height(Spacing.xxl))
            Text("Aird is locked", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(Spacing.sm))
            Text(
                "Unlock with your fingerprint, face or screen lock.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.xxl))
            Button(onClick = { nonce++ }, modifier = Modifier.width(200.dp).height(52.dp)) { Text("Unlock") }
        }
    }
}
