package com.aird.core.discovery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MdnsLifecycleTest {
    @Test
    fun lockIsHeldOnlyBetweenResumeAndPause() {
        val gate = MulticastGate()
        assertFalse(gate.held)
        assertFalse(gate.discovering)
        gate.onResume()
        assertTrue(gate.held)
        assertTrue(gate.discovering)
        gate.onResume()
        assertTrue(gate.held)
        gate.onPause()
        assertFalse(gate.held)
        assertFalse(gate.discovering)
        gate.onPause()
        assertFalse(gate.held)
    }
}
