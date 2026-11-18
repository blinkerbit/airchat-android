package com.aird.core.discovery

/** Reference-counted multicast window. Held only while a screen is resumed. */
class MulticastGate {
    var held: Boolean = false
        private set
    var discovering: Boolean = false
        private set

    fun onResume() {
        if (!held) held = true
        discovering = true
    }

    fun onPause() {
        discovering = false
        if (held) held = false
    }
}
