package com.aird.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import com.aird.ServerStore
import com.aird.core.discovery.MulticastGate
import com.aird.security.HostPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

data class DiscoveredHost(
    val name: String,
    val url: String,
    val serviceKey: String,
)

object DiscoveryManager {
    private val gate = MulticastGate()
    private var lock: WifiManager.MulticastLock? = null
    private var nsd: NsdManager? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private val resolving = ConcurrentHashMap.newKeySet<String>()
    private val byKey = ConcurrentHashMap<String, DiscoveredHost>()
    private val main = Handler(Looper.getMainLooper())

    private val _hosts = MutableStateFlow<List<DiscoveredHost>>(emptyList())
    val hosts: StateFlow<List<DiscoveredHost>> = _hosts

    fun onResume(context: Context) {
        if (gate.discovering) return
        gate.onResume()
        val app = context.applicationContext
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val held = lock ?: wifi.createMulticastLock("AirdMdnsLock").apply {
            setReferenceCounted(true)
        }.also { lock = it }
        if (!held.isHeld) held.acquire()
        startNsd(app)
    }

    fun onPause() {
        stopNsd()
        lock?.let { if (it.isHeld) it.release() }
        gate.onPause()
        resolving.clear()
    }

    private fun startNsd(context: Context) {
        if (discovery != null) return
        val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                discovery = null
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceFound(service: NsdServiceInfo) {
                resolve(manager, service)
            }
            override fun onServiceLost(service: NsdServiceInfo) {
                val key = serviceKey(service)
                byKey.remove(key)
                publish()
            }
        }
        nsd = manager
        discovery = listener
        manager.discoverServices("_aird._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun resolve(manager: NsdManager, service: NsdServiceInfo) {
        val key = serviceKey(service)
        if (!resolving.add(key)) return
        try {
            manager.resolveService(
                service,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        resolving.remove(key)
                    }

                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        resolving.remove(key)
                        val host = hostAddress(resolved.host) ?: return
                        val port = resolved.port.takeIf { it > 0 } ?: return
                        val url = buildUrl(host, port)
                        if (!ServerStore.isValid(url)) return
                        val label = resolved.serviceName?.takeIf { it.isNotBlank() }
                            ?: host
                        byKey[key] = DiscoveredHost(name = label, url = ServerStore.normalize(url), serviceKey = key)
                        publish()
                    }
                },
            )
        } catch (_: Exception) {
            resolving.remove(key)
        }
    }

    private fun buildUrl(host: String, port: Int): String {
        val bracketed = if (host.contains(':')) "[$host]" else host
        val scheme = if (HostPolicy.allowsCleartext(host)) "http" else "https"
        val defaultPort = if (scheme == "https") 443 else 80
        return if (port == defaultPort) "$scheme://$bracketed" else "$scheme://$bracketed:$port"
    }

    private fun hostAddress(address: InetAddress?): String? {
        if (address == null) return null
        return address.hostAddress?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun serviceKey(service: NsdServiceInfo): String {
        val name = service.serviceName.orEmpty()
        val type = service.serviceType.orEmpty()
        return "$name|$type"
    }

    private fun publish() {
        val next = byKey.values.sortedBy { it.name.lowercase() }
        main.post { _hosts.value = next }
    }

    private fun stopNsd() {
        val listener = discovery ?: return
        discovery = null
        try {
            nsd?.stopServiceDiscovery(listener)
        } catch (_: Exception) {
        }
        byKey.clear()
        publish()
    }
}
