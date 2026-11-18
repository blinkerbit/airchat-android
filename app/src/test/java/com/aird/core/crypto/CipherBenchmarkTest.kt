package com.aird.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CipherBenchmarkTest {
    @Test
    fun selectorFollowsPowerAndThermalState() {
        assertEquals(
            CipherChoice.AES_256_GCM,
            selectCipher(DeviceProfile(flagship = true, onAcPower = true, batteryPercent = 100, thermalThrottling = false)),
        )
        assertEquals(
            CipherChoice.ASCON_128A,
            selectCipher(DeviceProfile(flagship = true, onAcPower = false, batteryPercent = 15, thermalThrottling = false)),
        )
        assertEquals(
            CipherChoice.ASCON_128A,
            selectCipher(DeviceProfile(flagship = true, onAcPower = false, batteryPercent = 80, thermalThrottling = true)),
        )
        assertEquals(
            CipherChoice.ASCON_128A,
            selectCipher(DeviceProfile(flagship = false, onAcPower = false, batteryPercent = 90, thermalThrottling = false)),
        )
    }

    @Test
    fun aesAndAsconRoundTripAndRecordCost() {
        val key = KeyDerivation.pbkdf2("secret".toCharArray(), ByteArray(16) { it.toByte() }, iterations = 10_000)
        assertEquals(32, key.size)
        val plain = ByteArray(256 * 1024) { (it * 17).toByte() }
        val aesStarted = System.nanoTime()
        val aes = ChunkCipher.seal(plain, key, CipherChoice.AES_256_GCM)
        val aesOpened = ChunkCipher.open(aes, key)
        val aesNanos = System.nanoTime() - aesStarted
        val asconStarted = System.nanoTime()
        val ascon = ChunkCipher.seal(plain, key, CipherChoice.ASCON_128A)
        val asconOpened = ChunkCipher.open(ascon, key)
        val asconNanos = System.nanoTime() - asconStarted
        assertArrayEquals(plain, aesOpened)
        assertArrayEquals(plain, asconOpened)
        assertTrue(aesNanos > 0 && asconNanos > 0)
        assertEquals(12 + plain.size + 16, aes.bytes.size)
        assertEquals(16 + plain.size + 16, ascon.bytes.size)
        val known = AsconAead.encrypt(ByteArray(16) { it.toByte() }, ByteArray(16) { it.toByte() }, ByteArray(0), "ascon".encodeToByteArray())
        assertEquals("8602b3e5b8013dcd029ea065835196e772276de766", known.joinToString("") { "%02x".format(it) })
    }
}
