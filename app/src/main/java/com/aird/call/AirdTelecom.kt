package com.aird.call

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telecom.VideoProfile

class AirdTelecomService : ConnectionService() {
    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ): Connection {
        return AirdTelecom.attach(applicationContext, request, outgoing = false)
    }

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ): Connection {
        return AirdTelecom.attach(applicationContext, request, outgoing = true)
    }

    override fun onCreateIncomingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ) {
        AirdTelecom.failed()
    }

    override fun onCreateOutgoingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ) {
        AirdTelecom.failed()
    }
}

object AirdTelecom {
    private val main = Handler(Looper.getMainLooper())
    private var registered = false
    private var reporting = false
    private var connection: AirdCallConnection? = null

    fun register(context: Context) {
        if (registered) return
        val app = context.applicationContext
        val telecom = app.getSystemService(TelecomManager::class.java) ?: return
        val extras = Bundle()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            extras.putBoolean(PhoneAccount.EXTRA_LOG_SELF_MANAGED_CALLS, true)
        }
        val account = PhoneAccount.builder(handle(app), "Aird")
            .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
            .setExtras(extras)
            .addSupportedUriScheme(PhoneAccount.SCHEME_SIP)
            .build()
        try {
            telecom.registerPhoneAccount(account)
            registered = true
        } catch (_: Exception) {
        }
    }

    fun reportIncoming(context: Context, peer: String, video: Boolean) {
        onMain {
            val app = context.applicationContext
            if (connection != null || reporting) return@onMain
            register(app)
            val telecom = app.getSystemService(TelecomManager::class.java) ?: return@onMain
            val extras = Bundle().apply {
                putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle(app))
                putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, address(peer))
                putBoolean(EXTRA_VIDEO, video)
            }
            reporting = true
            try {
                telecom.addNewIncomingCall(handle(app), extras)
            } catch (_: Exception) {
                reporting = false
            }
        }
    }

    fun reportOutgoing(context: Context, peer: String, video: Boolean) {
        onMain {
            val app = context.applicationContext
            if (connection != null || reporting) return@onMain
            register(app)
            val telecom = app.getSystemService(TelecomManager::class.java) ?: return@onMain
            val extras = Bundle().apply {
                putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle(app))
                putBoolean(EXTRA_VIDEO, video)
            }
            reporting = true
            try {
                telecom.placeCall(address(peer), extras)
            } catch (_: Exception) {
                reporting = false
            }
        }
    }

    fun attach(context: Context, request: ConnectionRequest?, outgoing: Boolean): Connection {
        val call = AirdCallConnection(context.applicationContext, request)
        if (CallController.snapshot.value.phase == CallPhase.Idle) {
            reporting = false
            call.release(DisconnectCause.CANCELED)
            return call
        }
        connection?.release(DisconnectCause.OTHER)
        connection = call
        reporting = false
        if (outgoing) call.setDialing() else call.setRinging()
        return call
    }

    fun failed() {
        reporting = false
    }

    fun activate() {
        onMain { connection?.setActive() }
    }

    fun drop(call: Connection) {
        if (connection === call) connection = null
    }

    fun end(cause: Int) {
        onMain {
            reporting = false
            val current = connection ?: return@onMain
            connection = null
            current.release(cause)
        }
    }

    private fun handle(context: Context): PhoneAccountHandle {
        return PhoneAccountHandle(ComponentName(context, AirdTelecomService::class.java), ACCOUNT_ID)
    }

    private fun address(peer: String): Uri {
        val name = peer.ifBlank { "Aird" }.replace(" ", "")
        return Uri.fromParts(PhoneAccount.SCHEME_SIP, name, null)
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private const val ACCOUNT_ID = "aird"
    const val EXTRA_VIDEO = "video"
}

private class AirdCallConnection(
    private val app: Context,
    request: ConnectionRequest?,
) : Connection() {
    private var released = false

    init {
        connectionProperties = PROPERTY_SELF_MANAGED
        audioModeIsVoip = true
        val target = request?.address ?: Uri.fromParts(PhoneAccount.SCHEME_SIP, "aird", null)
        setAddress(target, TelecomManager.PRESENTATION_ALLOWED)
        setCallerDisplayName(target.schemeSpecificPart ?: "Aird", TelecomManager.PRESENTATION_ALLOWED)
        val video = request?.extras?.getBoolean(AirdTelecom.EXTRA_VIDEO) == true
        if (video) videoState = VideoProfile.STATE_BIDIRECTIONAL
    }

    fun release(cause: Int) {
        if (released) return
        released = true
        try {
            setDisconnected(DisconnectCause(cause))
            destroy()
        } catch (_: Exception) {
        }
    }

    override fun onAnswer() {
        if (released) return
        setActive()
        CallController.accept(app)
    }

    override fun onReject() {
        if (released) return
        AirdTelecom.drop(this)
        release(DisconnectCause.REJECTED)
        CallController.decline(app)
    }

    override fun onDisconnect() {
        if (released) return
        AirdTelecom.drop(this)
        release(DisconnectCause.LOCAL)
        CallController.hangUp(app)
    }

    override fun onAbort() {
        onDisconnect()
    }
}
