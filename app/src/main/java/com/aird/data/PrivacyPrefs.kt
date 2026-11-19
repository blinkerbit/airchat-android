package com.aird.data

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.WindowManager
import androidx.activity.ComponentActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class NotificationPreviewLevel { ShowAll, SenderOnly, Hidden }

data class PrivacySettings(
    val hideRecents: Boolean = false,
    val notificationPreviewLevel: NotificationPreviewLevel = NotificationPreviewLevel.ShowAll,
)

/** Device privacy: recents screenshots + notification lock-screen previews. */
object PrivacyPrefs {
    private const val PREFS = "aird"
    private const val KEY_HIDE_RECENTS = "privacy_hide_recents"
    private const val KEY_PREVIEW = "privacy_notification_preview"
    const val ONBOARDING_DONE = "onboarding_done"

    private val _settings = MutableStateFlow(PrivacySettings())
    val settings: StateFlow<PrivacySettings> = _settings

    fun load(context: Context) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _settings.value = PrivacySettings(
            hideRecents = sp.getBoolean(KEY_HIDE_RECENTS, false),
            notificationPreviewLevel = when (sp.getString(KEY_PREVIEW, null)) {
                "sender_only" -> NotificationPreviewLevel.SenderOnly
                "hidden" -> NotificationPreviewLevel.Hidden
                else -> NotificationPreviewLevel.ShowAll
            },
        )
    }

    fun setHideRecents(context: Context, on: Boolean) {
        edit(context) { it.copy(hideRecents = on) }
        applyRecents(context, on)
    }

    fun setNotificationPreview(context: Context, level: NotificationPreviewLevel) =
        edit(context) { it.copy(notificationPreviewLevel = level) }

    fun onboardingDone(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ONBOARDING_DONE, false)

    fun setOnboardingDone(context: Context, done: Boolean = true) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(ONBOARDING_DONE, done)
            .apply()
    }

    fun applyRecents(context: Context, hide: Boolean = _settings.value.hideRecents) {
        val activity = context as? Activity ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                activity.setRecentsScreenshotEnabled(!hide)
            } catch (_: Exception) {
            }
        }
        if (hide) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else if (context is ComponentActivity) {
            // Secure chat / other callers re-apply via WindowFlags; clear only when not needed elsewhere.
            // Leave FLAG_SECURE alone here if secure chat owns it — WindowFlags.apply runs next.
        }
    }

    fun applyToWindow(activity: Activity, secureChat: Boolean) {
        val hide = _settings.value.hideRecents || secureChat
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                activity.setRecentsScreenshotEnabled(!_settings.value.hideRecents)
            } catch (_: Exception) {
            }
        }
        if (hide) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun edit(context: Context, change: (PrivacySettings) -> PrivacySettings) {
        _settings.update(change)
        val next = _settings.value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_HIDE_RECENTS, next.hideRecents)
            .putString(
                KEY_PREVIEW,
                when (next.notificationPreviewLevel) {
                    NotificationPreviewLevel.ShowAll -> "show_all"
                    NotificationPreviewLevel.SenderOnly -> "sender_only"
                    NotificationPreviewLevel.Hidden -> "hidden"
                },
            )
            .apply()
    }
}
