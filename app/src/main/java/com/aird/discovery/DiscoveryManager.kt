package com.aird.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.wifi.WifiManager
import com.aird.core.discovery.MulticastGate

object DiscoveryManager {
    private val gate = MulticastGate()
    private var lock: WifiManager.MulticastLock? = null
    private var nsd: NsdManager? = null
    private var discovery: NsdManager.DiscoveryListener? = null

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
            override fun onServiceFound(service: android.net.nsd.NsdServiceInfo) = Unit
            override fun onServiceLost(service: android.net.nsd.NsdServiceInfo) = Unit
        }
        nsd = manager
        discovery = listener
        manager.discoverServices("_aird._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun stopNsd() {
        val listener = discovery ?: return
        discovery = null
        try {
            nsd?.stopServiceDiscovery(listener)
        } catch (_: Exception) {
        }
    }
}
