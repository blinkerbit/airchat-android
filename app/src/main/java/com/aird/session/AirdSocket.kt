package com.aird.session

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import com.aird.ServerStore
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.random.Random

object AirdSocket {
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val openListeners = CopyOnWriteArrayList<() -> Unit>()
    private val handler = Handler(Looper.getMainLooper())

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
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastNetwork: Network? = null
    private var appContext: Context? = null

    fun addListener(listener: (String) -> Unit) {
        listeners.add(listener)
    }

    fun addOpenListener(listener: () -> Unit) {
        openListeners.add(listener)
    }

    fun isOpen(): Boolean = open

    fun start(context: Context) {
        val app = context.applicationContext
        appContext = app
        running = true
        registerNetworkCallback(app)
        handler.post { if (socket == null) connect(app) }
    }

    /** Reconnect only when no socket is open or being opened. */
    fun ensureConnected() {
        val app = appContext ?: return
        if (running) handler.post { if (socket == null) connect(app) }
    }

    fun stop() {
        running = false
        generation += 1
        open = false
        handler.removeCallbacksAndMessages(null)
        appContext?.let { unregisterNetworkCallback(it) }
        socket?.cancel()
        socket = null
    }

    fun send(payload: String): Boolean = open && socket?.send(payload) == true

    private fun connect(context: Context) {
        if (!running) return
        handler.removeCallbacksAndMessages(null)
        CookieManager.getInstance().flush()
        val server = ServerStore.get(context)
        val ws = server.replace("https://", "wss://").replace("http://", "ws://") + "/ws/chat"
        val request = Request.Builder().url(ws).header("Origin", server).build()
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
                lost(context, mine)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                lost(context, mine)
            }
        })
    }

    private fun lost(context: Context, mine: Int) {
        handler.post {
            if (mine != generation) return@post
            open = false
            socket = null
            if (!running) return@post
            attempts += 1
            val base = minOf(15_000L, 1_000L shl minOf(attempts - 1, 4))
            val delay = base / 2 + Random.nextLong(base / 2 + 1)
            handler.removeCallbacksAndMessages(null)
            handler.postDelayed({ connect(context) }, delay)
        }
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
