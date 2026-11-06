package com.aird

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.os.PowerManager
import android.view.WindowManager
import com.aird.call.CallPhase
import com.aird.call.CallSnapshot
import com.aird.session.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** App lock stays behind calls and never covers a live or incoming call. */
object AppLock {
    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked
    private var pending = false
    private var busy = false
    private var generation = 0
    private var cancel: CancellationSignal? = null

    fun arm() {
        pending = true
    }

    fun disarm() {
        pending = false
    }

    fun onSignedIn() {
        if (!pending) return
        pending = false
        _locked.value = true
    }

    fun onSignedOut() {
        pending = false
        dismissPrompt()
        _locked.value = false
    }

    fun unlock() {
        _locked.value = false
        dismissPrompt()
    }

    fun lockIfIdle() {
        if (!Session.signedIn.value) return
        if (callActive()) return
        _locked.value = true
    }

    fun dismissPrompt() {
        generation += 1
        busy = false
        val signal = cancel
        cancel = null
        if (signal != null && !signal.isCanceled) signal.cancel()
    }

    fun request(activity: Activity) {
        if (busy || !_locked.value) return
        val keyguard = activity.getSystemService(KeyguardManager::class.java)
        if (keyguard == null || !keyguard.isDeviceSecure) {
            _locked.value = false
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            authenticate(activity)
            return
        }
        val intent = keyguard.createConfirmDeviceCredentialIntent("Unlock Aird", "Confirm it's you")
        val host = activity as? MainActivity
        if (intent == null || host == null) {
            _locked.value = false
            return
        }
        busy = true
        host.confirmDevice(intent)
    }

    fun credentialFinished(ok: Boolean) {
        busy = false
        if (ok) _locked.value = false
    }

    private fun authenticate(activity: Activity) {
        val gen = generation
        busy = true
        val builder = BiometricPrompt.Builder(activity).setTitle("Unlock Aird")
        val prompt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            ).build()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            builder.setDeviceCredentialAllowed(true).build()
        } else {
            builder.setNegativeButton("Cancel", activity.mainExecutor) { _, _ ->
                if (gen == generation) busy = false
            }.build()
        }
        val signal = CancellationSignal()
        cancel = signal
        prompt.authenticate(
            signal,
            activity.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                    if (gen != generation) return
                    _locked.value = false
                    busy = false
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    if (gen != generation) return
                    busy = false
                    if (!_locked.value) return
                    if (errorCode == BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS ||
                        errorCode == BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT ||
                        errorCode == BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE ||
                        errorCode == BiometricPrompt.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL
                    ) {
                        _locked.value = false
                    }
                }
            },
        )
    }

    private fun callActive(): Boolean =
        com.aird.call.CallController.snapshot.value.phase != CallPhase.Idle
}

object CallProximity {
    private var lock: PowerManager.WakeLock? = null

    fun set(context: Context, on: Boolean) {
        if (!on) {
            lock?.let { if (it.isHeld) it.release() }
            lock = null
            return
        }
        if (lock?.isHeld == true) return
        val power = context.getSystemService(PowerManager::class.java) ?: return
        val wake = power.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "aird:call-proximity")
        wake.setReferenceCounted(false)
        wake.acquire()
        lock = wake
    }
}

object WindowFlags {
    fun apply(activity: Activity, secureChat: Boolean, call: CallSnapshot, inPip: Boolean) {
        val window = activity.window
        val callUp = call.phase != CallPhase.Idle
        if (secureChat && !callUp && !inPip) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        val earpiece = !inPip &&
            call.mode != "video" &&
            !call.speaker &&
            (call.output.isEmpty() || call.output == "earpiece") &&
            (call.phase == CallPhase.Live || call.phase == CallPhase.Joining || call.phase == CallPhase.Outgoing)
        CallProximity.set(activity, earpiece)
        if (earpiece) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
