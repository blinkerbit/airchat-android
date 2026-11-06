package com.aird.session

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.webkit.CookieManager
import com.aird.ServerStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

object AirdSocket {
    private const val MAX_ATTEMPTS = 3
    private const val RETRY_MS = 5_000L
    private const val SLOW_RETRY_MS = 30_000L

    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val openListeners = CopyOnWriteArrayList<() -> Unit>()
    private val stateListeners = CopyOnWriteArrayList<() -> Unit>()
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client: OkHttpClient by lazy {
        AirdHttp.client.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()
    }

    @Volatile private var socket: WebSocket? = null
    @Volatile private var open = false
    private var generation = 0
    private var running = false
    private var attempts = 0
    private var lastAuthClose = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var screenReceiver: BroadcastReceiver? = null
    private var lastNetwork: Network? = null
    private var appContext: Context? = null

    private val _failed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = _failed

    private val reconnect = Runnable {
        val app = appContext ?: return@Runnable
        connect(app)
    }

    fun addListener(listener: (String) -> Unit) {
        listeners.add(listener)
    }

    fun addOpenListener(listener: () -> Unit) {
        openListeners.add(listener)
    }

    fun addStateListener(listener: () -> Unit) {
        stateListeners.add(listener)
    }

    fun isOpen(): Boolean = open

    fun start(context: Context) {
        val app = context.applicationContext
        appContext = app
        running = true
        attempts = 0
        lastAuthClose = false
        setFailed(false)
        registerNetworkCallback(app)
        registerScreenReceiver(app)
        handler.post { if (socket == null) connect(app) }
    }

    /** Reconnect only when no socket is open or being opened. */
    fun ensureConnected() {
        val app = appContext ?: return
        if (!running) return
        handler.post {
            if (socket != null && (open || !_failed.value)) return@post
            attempts = 0
            lastAuthClose = false
            setFailed(false)
            if (socket == null) connect(app)
        }
    }

    fun stop() {
        running = false
        generation += 1
        open = false
        attempts = 0
        lastAuthClose = false
        setFailed(false)
        handler.removeCallbacks(reconnect)
        appContext?.let {
            unregisterNetworkCallback(it)
            unregisterScreenReceiver(it)
        }
        socket?.cancel()
        socket = null
    }

    fun send(payload: String): Boolean = open && socket?.send(payload) == true

    private fun connect(context: Context) {
        if (!running) return
        handler.removeCallbacks(reconnect)
        CookieManager.getInstance().flush()
        val server = ServerStore.get(context)
        val available = if (screenAvailable(context)) "1" else "0"
        val ws = server.replace("https://", "wss://").replace("http://", "ws://") +
            "/ws/chat?available=$available"
        val requestBuilder = Request.Builder().url(ws).header("Origin", server)
        AirdHttp.attachWebViewCookies(requestBuilder, context, server)
        val request = requestBuilder.build()
        generation += 1
        val mine = generation
        val old = socket
        open = false
        socket = null
        old?.cancel()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (mine != generation) {
                    webSocket.cancel()
                    return
                }
                open = true
                attempts = 0
                lastAuthClose = false
                setFailed(false)
                announceAvailable()
                handler.post {
                    if (mine != generation) return@post
                    openListeners.forEach { listener ->
                        try {
                            listener()
                        } catch (_: Exception) {
                        }
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (mine != generation) return
                handler.post {
                    if (mine != generation) return@post
                    listeners.forEach { listener ->
                        try {
                            listener(text)
                        } catch (_: Exception) {
                        }
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                lost(context, mine, authClose(code, reason))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                lost(context, mine, auth = false)
            }
        })
    }

    private fun lost(context: Context, mine: Int, auth: Boolean) {
        handler.post {
            if (mine != generation) return@post
            open = false
            socket = null
            if (!running) return@post
            if (auth) lastAuthClose = true
            attempts += 1
            handler.removeCallbacks(reconnect)
            if (attempts >= MAX_ATTEMPTS) {
                setFailed(true)
                if (attempts == MAX_ATTEMPTS && lastAuthClose) {
                    scope.launch { Session.probe(context) }
                }
                handler.postDelayed(reconnect, SLOW_RETRY_MS)
                return@post
            }
            handler.postDelayed(reconnect, RETRY_MS)
        }
    }

    private fun authClose(code: Int, reason: String): Boolean {
        if (code != 1008) return false
        return reason.contains("Authentication", ignoreCase = true) ||
            reason.contains("Invalid user", ignoreCase = true)
    }

    private fun setFailed(value: Boolean) {
        if (_failed.value == value) {
            notifyState()
            return
        }
        _failed.value = value
        notifyState()
    }

    private fun notifyState() {
        handler.post {
            stateListeners.forEach { listener ->
                try {
                    listener()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun screenAvailable(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val interactive = power?.isInteractive != false
        val locked = keyguard?.isKeyguardLocked == true
        return interactive && !locked
    }

    private fun announceAvailable() {
        val app = appContext ?: return
        val online = if (screenAvailable(app)) "true" else "false"
        socket?.send("""{"type":"chat_available","online":$online}""")
    }

    private fun registerScreenReceiver(context: Context) {
        if (screenReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                announceAvailable()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        screenReceiver = receiver
    }

    private fun unregisterScreenReceiver(context: Context) {
        val receiver = screenReceiver ?: return
        try {
            context.unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
        screenReceiver = null
    }

    private fun registerNetworkCallback(context: Context) {
        if (networkCallback != null) return
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val changed = lastNetwork != null && lastNetwork != network
                lastNetwork = network
                if (!running) return
                handler.post {
                    if (changed || socket == null) {
                        attempts = 0
                        lastAuthClose = false
                        setFailed(false)
                        connect(context)
                    }
                }
            }

            override fun onLost(network: Network) {
                if (lastNetwork == network) lastNetwork = null
            }
        }
        networkCallback = callback
        try {
            manager.registerDefaultNetworkCallback(callback)
        } catch (_: Exception) {
        }
    }

    private fun unregisterNetworkCallback(context: Context) {
        val callback = networkCallback ?: return
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        try {
            manager.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
        }
        networkCallback = null
        lastNetwork = null
    }
}
