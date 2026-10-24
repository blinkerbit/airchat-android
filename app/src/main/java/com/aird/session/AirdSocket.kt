package com.aird.session

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import com.aird.ServerStore
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.CopyOnWriteArrayList

object AirdSocket {
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val handler = Handler(Looper.getMainLooper())
    private var socket: WebSocket? = null
    private var running = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var appContext: Context? = null

    fun addListener(listener: (String) -> Unit) {
        listeners.add(listener)
    }

    fun start(context: Context) {
        val app = context.applicationContext
        appContext = app
        running = true
        registerNetworkCallback(app)
        connect(app)
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        appContext?.let { unregisterNetworkCallback(it) }
        socket?.cancel()
        socket = null
    }

    fun send(payload: String): Boolean = socket?.send(payload) == true

    private fun connect(context: Context) {
        if (!running) return
        handler.removeCallbacksAndMessages(null)
        CookieManager.getInstance().flush()
        val server = ServerStore.get(context)
        val ws = server.replace("https://", "wss://").replace("http://", "ws://") + "/ws/chat"
        val request = Request.Builder().url(ws).header("Origin", server).build()
        socket?.cancel()
        socket = AirdHttp.client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                listeners.forEach { listener ->
                    try {
                        listener(text)
                    } catch (_: Exception) {
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                schedule(context)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                schedule(context)
            }
        })
    }

    private fun schedule(context: Context) {
        if (!running) return
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ connect(context) }, 3000)
    }

    private fun registerNetworkCallback(context: Context) {
        if (networkCallback != null) return
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (running) handler.post { connect(context) }
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
    }
}
